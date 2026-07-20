package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.helper.JavaFilePath;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

abstract class JavaAgentPipelineSupport extends AgentService {
    private static final int MAX_ADDITIONAL_FILES = 12;
    private static final int MAX_MODIFIED_FILES = 20;
    private static final int MAX_CORE_CONTEXT_CHARS = 120_000;
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 100_000;
    private static final int MAX_PRIOR_GENERATION_CHARS = 80_000;
    private static final int MAX_AGENT3_RETRIES = 5;
    private static final int MAX_RETRY_RESPONSE_CHARS = 20_000;
    private static final long PIPELINE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static class AdditionalFile {
        String filename;
        String recommendReason;
    }

    private static class Agent1ParsedResult {
        boolean needAdditionalFile;
        List<AdditionalFile> additionalFileList = new ArrayList<>();
    }

    private static class ModifiedFile {
        String filename;
        String plan;
        String note;
    }

    private static class Agent2ParsedResult {
        List<ModifiedFile> modifiedFileList = new ArrayList<>();
    }

    protected SseEmitter runJavaPipeline(String runId, String model, boolean addition) {
        AgentRunContext context = agentRunRegistry.claim(runId);
        AgentRunEventStream eventStream = agentRunRegistry.eventStream(runId);
        SseEmitter subscriber = agentRunRegistry.subscribe(runId);
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();

        Runnable cancel = () -> {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            Future<?> future = futureRef.get();
            if (future != null) {
                future.cancel(true);
            }
            agentRunRegistry.fail(runId, new IOException("Agent stream was cancelled or timed out."));
        };

        try {
            Future<?> future = agentPipelineExecutor.submit(() -> executePipeline(
                    context,
                    model,
                    addition,
                    eventStream,
                    terminal
            ));
            futureRef.set(future);
            agentRunRegistry.registerCancellation(runId, cancel);
            CompletableFuture.delayedExecutor(PIPELINE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(cancel);
        } catch (RuntimeException exception) {
            terminal.set(true);
            agentRunRegistry.fail(runId, exception);
            throw exception;
        }
        return subscriber;
    }

    private void executePipeline(
            AgentRunContext context,
            String model,
            boolean addition,
            AgentEventSink eventSink,
            AtomicBoolean terminal
    ) {
        try {
            Set<String> existingJavaFiles = new HashSet<>(ListFileHelper.findJavaFiles(context.sourceRoot()));

            sendStatus(eventSink, context.language().stageOneDescription());
            String agent1Prompt = buildAgent1Prompt(context, addition);
            Agent1ParsedResult agent1 = requestAndParseAgent1(
                    agent1Prompt,
                    eventSink,
                    model,
                    existingJavaFiles,
                    context.language()
            );

            String extraInfo = loadAdditionalContext(context.sourceRoot(), agent1, context.language());
            if (agent1.needAdditionalFile) {
                sendStatus(
                        eventSink,
                        context.language() == AgentLanguage.CN
                                ? "\n# === 阶段 I：补充上下文复核 ===\n"
                                : "\n# === Stage I: Additional Context Recheck ===\n"
                );
                String followUpPrompt = agent1Prompt + "\n\nThe following requested files are now available:\n"
                        + boundedPromptSection(extraInfo, MAX_REFERENCE_CONTEXT_CHARS)
                        + "\nRe-evaluate sufficiency once. Do not request a file already shown above.";
                Agent1ParsedResult followUp = requestAndParseAgent1(
                        followUpPrompt,
                        eventSink,
                        model,
                        existingJavaFiles,
                        context.language()
                );
                if (followUp.needAdditionalFile) {
                    Set<String> firstRound = agent1.additionalFileList.stream()
                            .map(file -> file.filename)
                            .collect(java.util.stream.Collectors.toSet());
                    followUp.additionalFileList.removeIf(file -> firstRound.contains(file.filename));
                    if (agent1.additionalFileList.size() + followUp.additionalFileList.size()
                            > MAX_ADDITIONAL_FILES) {
                        throw new IllegalArgumentException("Agent1 requested too many additional files across rounds.");
                    }
                    if (!followUp.additionalFileList.isEmpty()) {
                        extraInfo = boundedPromptSection(
                                extraInfo + "\n" + loadAdditionalContext(
                                        context.sourceRoot(),
                                        followUp,
                                        context.language()
                                ),
                                MAX_REFERENCE_CONTEXT_CHARS
                        );
                    }
                }
            }

            sendStatus(eventSink, context.language().stageTwoDescription());
            String agent2Prompt = buildAgent2Prompt(context, extraInfo, addition);
            Agent2ParsedResult agent2 = requestAndParseAgent2(
                    agent2Prompt,
                    eventSink,
                    model,
                    context.sourceRoot(),
                    context.language()
            );
            if (agent2.modifiedFileList.isEmpty()) {
                throw new IllegalStateException(
                        "Agent2 planned no Java file changes, so there is no candidate operation to confirm."
                );
            }

            String globalPlan = renderGlobalPlan(agent2.modifiedFileList);
            String referenceContext = boundedPromptSection(
                    context.relatedCodes() + "\n\nAdditional file context:\n" + extraInfo,
                    MAX_REFERENCE_CONTEXT_CHARS
            );
            StringBuilder priorGenerations = new StringBuilder();
            Map<String, String> modifications = new LinkedHashMap<>();

            for (ModifiedFile file : agent2.modifiedFileList) {
                ensureNotInterrupted();
                sendStatus(eventSink, context.language().stageThreeDescription(file.filename));
                Path targetPath = JavaFilePath.resolve(Path.of(context.sourceRoot()), file.filename);
                boolean createMode = !Files.isRegularFile(targetPath);
                String fileContent = createMode
                        ? ""
                        : ListFileHelper.getFileContent(context.sourceRoot(), file.filename);
                if (fileContent.isBlank()) {
                    createMode = true;
                }
                String agent3Prompt = buildAgent3Prompt(
                        context,
                        file,
                        fileContent,
                        createMode,
                        globalPlan,
                        referenceContext,
                        boundedPromptSection(priorGenerations.toString(), MAX_PRIOR_GENERATION_CHARS),
                        addition
                );
                String generatedContent = requestAndApplyAgent3(
                        agent3Prompt,
                        eventSink,
                        model,
                        file.filename,
                        fileContent,
                        createMode,
                        context.language()
                );
                modifications.put(file.filename, generatedContent);
                priorGenerations.append("\nFILE: ").append(file.filename).append("\n")
                        .append(generatedContent).append("\nEND FILE\n");
            }

            agentRunRegistry.complete(context.runId(), modifications);
            terminal.set(true);
            try {
                sendCompleted(eventSink, context.language().pipelineCompleteDescription());
            } catch (IOException disconnected) {
                logger.debug("Agent run completed after its SSE client disconnected: {}", context.runId());
            }
        } catch (Exception exception) {
            if (terminal.get()) {
                try {
                    if (agentRunRegistry.status(context.runId()) == AgentRunRegistry.Status.COMPLETED) {
                        return;
                    }
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // The run was explicitly discarded while this worker was unwinding.
                }
            }
            terminal.set(true);
            agentRunRegistry.fail(context.runId(), exception);
            logger.error("Java Agent pipeline failed for run {}", context.runId(), exception);
            try {
                sendFailed(
                        eventSink,
                        context.language().pipelineErrorDescription() + " " + safeFailureMessage(exception)
                );
            } catch (IOException ignored) {
                logger.debug("Could not deliver Agent failure event for run {}", context.runId());
            }
        }
    }

    private Agent1ParsedResult requestAndParseAgent1(
            String prompt,
            AgentEventSink eventSink,
            String model,
            Set<String> existingFiles,
            AgentLanguage language
    ) throws IOException {
        String response = llmClient.streamGenerateWithPrompt(prompt, eventSink, model);
        try {
            return parseAndValidateAgent1(response, existingFiles);
        } catch (RuntimeException exception) {
            sendStatus(eventSink, retryMessage(language, "Agent1", exception));
            String retried = llmClient.streamGenerateWithPrompt(repairPrompt(prompt, "Agent1"), eventSink, model);
            return parseAndValidateAgent1(retried, existingFiles);
        }
    }

    private Agent2ParsedResult requestAndParseAgent2(
            String prompt,
            AgentEventSink eventSink,
            String model,
            String sourceRoot,
            AgentLanguage language
    ) throws IOException {
        String response = llmClient.streamGenerateWithPrompt(prompt, eventSink, model);
        try {
            return parseAndValidateAgent2(response, sourceRoot);
        } catch (RuntimeException exception) {
            sendStatus(eventSink, retryMessage(language, "Agent2", exception));
            String retried = llmClient.streamGenerateWithPrompt(repairPrompt(prompt, "Agent2"), eventSink, model);
            return parseAndValidateAgent2(retried, sourceRoot);
        }
    }

    protected String requestAndApplyAgent3(
            String prompt,
            AgentEventSink eventSink,
            String model,
            String filename,
            String originalContent,
            boolean createMode,
            AgentLanguage language
    ) throws IOException {
        Exception lastFailure = null;
        String previousResponse = "";
        for (int attempt = 0; attempt <= MAX_AGENT3_RETRIES; attempt++) {
            ensureNotInterrupted();
            String attemptPrompt = attempt == 0
                    ? prompt
                    : buildAgent3RetryPrompt(prompt, attempt, lastFailure, previousResponse);
            if (attempt > 0) {
                sendStatus(eventSink, agent3RetryMessage(language, filename, attempt, lastFailure));
            }

            try {
                previousResponse = llmClient.streamGenerateWithPrompt(attemptPrompt, eventSink, model);
                return applyAgent3Result(previousResponse, filename, originalContent, createMode);
            } catch (IOException | RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Agent pipeline was cancelled.", exception);
                }
                lastFailure = exception;
                if (attempt == MAX_AGENT3_RETRIES) {
                    throw new IOException(
                            "Agent3 failed for " + filename + " after " + (MAX_AGENT3_RETRIES + 1)
                                    + " attempts: " + safeFailureMessage(exception),
                            exception
                    );
                }
            }
        }
        throw new IOException("Agent3 failed for " + filename + ".", lastFailure);
    }

    private Agent1ParsedResult parseAndValidateAgent1(String response, Set<String> existingFiles) {
        JsonNode root = readJsonObject(response);
        JsonNode needNode = root.get("needAdditionalFile");
        JsonNode filesNode = root.get("additionalFileList");
        if (needNode == null || !needNode.isBoolean()) {
            throw new IllegalArgumentException("Agent1 must return boolean needAdditionalFile.");
        }
        if (filesNode == null || !filesNode.isArray()) {
            throw new IllegalArgumentException("Agent1 must return an additionalFileList array.");
        }
        if (filesNode.size() > MAX_ADDITIONAL_FILES) {
            throw new IllegalArgumentException("Agent1 requested too many additional files.");
        }

        Agent1ParsedResult result = new Agent1ParsedResult();
        result.needAdditionalFile = needNode.booleanValue();
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : filesNode) {
            String filename = JavaFilePath.normalize(requiredText(fileNode, "filename", "Agent1"));
            if (!existingFiles.contains(filename)) {
                throw new IllegalArgumentException("Agent1 requested a Java file outside the supplied list: " + filename);
            }
            if (!seen.add(filename)) {
                continue;
            }
            AdditionalFile file = new AdditionalFile();
            file.filename = filename;
            file.recommendReason = requiredText(fileNode, "recommendReason", "Agent1");
            result.additionalFileList.add(file);
        }
        if (result.needAdditionalFile != !result.additionalFileList.isEmpty()) {
            throw new IllegalArgumentException("Agent1 needAdditionalFile does not match additionalFileList.");
        }
        return result;
    }

    private Agent2ParsedResult parseAndValidateAgent2(String response, String sourceRoot) {
        JsonNode root = readJsonObject(response);
        JsonNode filesNode = root.get("modifiedFileList");
        if (filesNode == null || !filesNode.isArray()) {
            throw new IllegalArgumentException("Agent2 must return a modifiedFileList array.");
        }
        if (filesNode.size() > MAX_MODIFIED_FILES) {
            throw new IllegalArgumentException("Agent2 planned too many modified files.");
        }

        Agent2ParsedResult result = new Agent2ParsedResult();
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : filesNode) {
            String filename = JavaFilePath.normalize(requiredText(fileNode, "filename", "Agent2"));
            JavaFilePath.resolve(Path.of(sourceRoot), filename);
            if (!seen.add(filename)) {
                throw new IllegalArgumentException("Agent2 returned a duplicate file: " + filename);
            }
            ModifiedFile file = new ModifiedFile();
            file.filename = filename;
            file.plan = requiredText(fileNode, "plan", "Agent2");
            file.note = optionalText(fileNode, "note");
            result.modifiedFileList.add(file);
        }
        return result;
    }

    protected String applyAgent3Result(
            String response,
            String filename,
            String originalContent,
            boolean createMode
    ) {
        try {
            String javaCode = SearchReplacePatch.apply(response, originalContent, createMode);
            CompilationUnit compilationUnit = StaticJavaParser.parse(javaCode);
            String expectedType = JavaFilePath.simpleTypeName(filename);
            boolean expectedTypePresent = compilationUnit.getTypes().stream()
                    .anyMatch(type -> type.getNameAsString().equals(expectedType));
            if (!expectedTypePresent) {
                throw new IllegalArgumentException(
                        "Generated file " + filename + " does not declare its expected type " + expectedType + "."
                );
            }
            boolean mismatchedPublicType = compilationUnit.getTypes().stream()
                    .anyMatch(type -> type.isPublic() && !type.getNameAsString().equals(expectedType));
            if (mismatchedPublicType) {
                throw new IllegalArgumentException(
                        "Generated file " + filename + " declares a different public top-level type."
                );
            }
            String expectedPackage = JavaFilePath.packageName(filename);
            String actualPackage = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            if (!expectedPackage.equals(actualPackage)) {
                throw new IllegalArgumentException(
                        "Generated file " + filename + " must declare package "
                                + (expectedPackage.isEmpty() ? "<default>" : expectedPackage) + "."
                );
            }

            return javaCode;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Agent3 Search/Replace result is invalid for " + filename + ": "
                            + safeFailureMessage(exception),
                    exception
            );
        }
    }

    private String loadAdditionalContext(
            String sourceRoot,
            Agent1ParsedResult agent1,
            AgentLanguage language
    ) throws IOException {
        if (!agent1.needAdditionalFile) {
            return language.noExtraInformation();
        }
        StringBuilder extra = new StringBuilder();
        for (AdditionalFile file : agent1.additionalFileList) {
            ensureNotInterrupted();
            extra.append("filename: ").append(file.filename).append("\n")
                    .append("recommendReason: ").append(file.recommendReason).append("\n")
                    .append("fileContent:\n")
                    .append(ListFileHelper.getFileContent(sourceRoot, file.filename))
                    .append("\n=======================\n");
        }
        return boundedPromptSection(extra.toString(), MAX_REFERENCE_CONTEXT_CHARS);
    }

    private String renderGlobalPlan(List<ModifiedFile> files) {
        StringBuilder plan = new StringBuilder();
        for (ModifiedFile file : files) {
            plan.append("FILE: ").append(file.filename).append("\n")
                    .append("PLAN: ").append(file.plan).append("\n")
                    .append("NOTE: ").append(file.note).append("\n\n");
        }
        return plan.toString();
    }

    private String buildAgent1Prompt(AgentRunContext context, boolean addition) {
        String originalSection = addition ? "" : """
                Original feature description:
                \"\"\"
                %s
                \"\"\"

                """.formatted(context.oldRequest());
        return """
                You are Agent1. Determine which existing Java source files are required to implement the requested %s.

                Requirement:
                \"\"\"
                %s
                \"\"\"

                %s
                Existing CodeMap context:
                \"\"\"
                %s
                \"\"\"

                Complete Java source-file list. Every filename uses a source-root-relative path such as
                cn/edu/pku/Foo.java. Return filenames in exactly this format:
                \"\"\"
                %s
                \"\"\"

                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "needAdditionalFile": true,
                  "additionalFileList": [
                    {"filename": "cn/edu/pku/Foo.java", "recommendReason": "why it is needed"}
                  ]
                }
                Write recommendReason values in %s.
                If no additional files are needed, return false and an empty array.
                """.formatted(
                addition ? "feature addition" : "feature modification",
                context.newRequest(),
                originalSection,
                boundedPromptSection(context.relatedCodes(), MAX_CORE_CONTEXT_CHARS),
                boundedPromptSection(context.allFiles(), 80_000),
                context.language().promptLanguageName()
        );
    }

    private String buildAgent2Prompt(AgentRunContext context, String extraInfo, boolean addition) {
        return """
                You are Agent2. Produce a complete, internally consistent Java file-level plan for this %s.

                Requirement:
                \"\"\"
                %s
                \"\"\"

                Original feature description, if any:
                \"\"\"
                %s
                \"\"\"

                Existing CodeMap context:
                \"\"\"
                %s
                \"\"\"

                Additional file context:
                \"\"\"
                %s
                \"\"\"

                Every filename must be a source-root-relative Java path such as cn/edu/pku/Foo.java.
                Include every edited or newly created Java file, but no non-Java files. Make shared API names,
                signatures, data types, and call sites explicit so independent file rewrites stay consistent.

                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "modifiedFileList": [
                    {
                      "filename": "cn/edu/pku/Foo.java",
                      "plan": "Concrete steps, including exact shared signatures.",
                      "note": "Constraints and compatibility risks."
                    }
                  ]
                }
                A genuinely no-op change must return an empty modifiedFileList.
                Write plan and note values in %s.
                """.formatted(
                addition ? "feature addition" : "feature modification",
                context.newRequest(),
                context.oldRequest(),
                boundedPromptSection(context.relatedCodes(), MAX_CORE_CONTEXT_CHARS),
                extraInfo,
                context.language().promptLanguageName()
        );
    }

    private String buildAgent3Prompt(
            AgentRunContext context,
            ModifiedFile target,
            String originalFile,
            boolean createMode,
            String globalPlan,
            String referenceContext,
            String priorGenerations,
            boolean addition
    ) {
        String expectedPackage = JavaFilePath.packageName(target.filename);
        String packageDeclaration = expectedPackage.isEmpty()
                ? ""
                : "package " + expectedPackage + ";\n\n";
        String expectedTypeName = JavaFilePath.simpleTypeName(target.filename);
        String outputContract = createMode ? """
                The target is a new or empty file, so return exactly one CREATE block:
                <<<<<<< CREATE
                %spublic class %s {
                }
                >>>>>>> CREATE

                CREATE is the only case where complete-file generation is allowed.
                """.formatted(packageDeclaration, expectedTypeName) : """
                Return only minimal exact Search/Replace blocks in application order:
                <<<<<<< SEARCH
                exact text copied from the current target file
                =======
                replacement text
                >>>>>>> REPLACE

                Rules:
                - Never return the complete existing file.
                - SEARCH must be non-empty and match exactly once in the current file at that step.
                - Include enough unchanged context to make every SEARCH unique.
                - Use additional blocks for additional edits, including package or import changes.
                - Do not use ellipses, line numbers, regexes, explanations, or omitted-code placeholders.
                """;
        return """
                You are Agent3. Produce precise Search/Replace edits for one Java source file in a coordinated
                multi-file %s.

                Requirement:
                \"\"\"
                %s
                \"\"\"

                Original feature description, if any:
                \"\"\"
                %s
                \"\"\"

                Global file plan. Treat exact shared signatures as a contract:
                \"\"\"
                %s
                \"\"\"

                Reference code and dependency context:
                \"\"\"
                %s
                \"\"\"

                Files already generated in this run:
                \"\"\"
                %s
                \"\"\"

                Target file: %s
                Target plan: %s
                Target constraints: %s

                Original complete target content:
                \"\"\"
                %s
                \"\"\"

                %s

                The resulting file must remain syntactically valid Java and declare the type named %s.
                FeatX applies the blocks only in memory and parses the result; it does not compile or run the project.
                Do not wrap the protocol in Markdown fences.
                Write newly added or modified natural-language comments in %s.
                """.formatted(
                addition ? "feature addition" : "feature modification",
                context.newRequest(),
                context.oldRequest(),
                globalPlan,
                referenceContext,
                priorGenerations,
                target.filename,
                target.plan,
                target.note,
                originalFile,
                outputContract,
                expectedTypeName,
                context.language().promptLanguageName()
        );
    }

    private JsonNode readJsonObject(String response) {
        String json = extractJsonObject(response);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Agent response must be a JSON object.");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Agent returned malformed JSON.", exception);
        }
    }

    private String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("Agent returned an empty response.");
        }
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Agent response does not contain a JSON object.");
        }
        return trimmed.substring(start, end + 1);
    }

    private String requiredText(JsonNode node, String field, String agent) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(agent + " must return non-empty " + field + ".");
        }
        return value.asText().trim();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String repairPrompt(String originalPrompt, String agent) {
        return originalPrompt + "\n\nYour previous " + agent
                + " response violated the required contract. Try once more and follow the output schema exactly.";
    }

    private String buildAgent3RetryPrompt(
            String originalPrompt,
            int retryNumber,
            Exception failure,
            String previousResponse
    ) {
        return originalPrompt + "\n\nRetry " + retryNumber + " of " + MAX_AGENT3_RETRIES + ".\n"
                + "The previous attempt failed for this exact reason:\n"
                + safeFailureMessage(failure) + "\n\n"
                + "Previous response:\n"
                + boundedPromptSection(previousResponse, MAX_RETRY_RESPONSE_CHARS) + "\n\n"
                + "Generate a fresh protocol response against the ORIGINAL target content. Do not apply edits "
                + "to the previous response. Correct the reported failure and follow the protocol exactly.";
    }

    private String agent3RetryMessage(
            AgentLanguage language,
            String filename,
            int retryNumber,
            Exception failure
    ) {
        String reason = safeFailureMessage(failure);
        return language == AgentLanguage.CN
                ? "\n# Agent3 " + filename + " 执行失败，正在重试 " + retryNumber + "/"
                        + MAX_AGENT3_RETRIES + "：" + reason + "\n"
                : "\n# Agent3 " + filename + " failed; retry " + retryNumber + "/"
                        + MAX_AGENT3_RETRIES + ": " + reason + "\n";
    }

    private String retryMessage(AgentLanguage language, String agent, Exception exception) {
        return language == AgentLanguage.CN
                ? "\n# " + agent + " 输出校验失败，正在按严格协议重试。\n"
                : "\n# " + agent + " output validation failed; retrying with the strict contract.\n";
    }

    private String safeFailureMessage(Exception exception) {
        if (exception == null) {
            return "Unknown Agent failure";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void ensureNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Agent pipeline was cancelled.");
        }
    }
}
