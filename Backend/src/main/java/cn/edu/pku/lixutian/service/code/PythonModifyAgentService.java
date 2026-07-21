package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.helper.ListFileHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

@Service
public class PythonModifyAgentService extends AgentService {
    private static final int MAX_ADDITIONAL_FILES = 12;
    private static final int MAX_MODIFIED_FILES = 20;
    private static final int MAX_AGENT3_RETRIES = 5;
    private static final int MAX_RETRY_RESPONSE_CHARS = 20_000;
    private static final long PYTHON_SYNTAX_TIMEOUT_SECONDS = 20;

    private static class AdditionalFile {
        public String filename;
        public String recommendReason;
    }

    private static class Agent1ParsedResult {
        public boolean needAdditionalFile;
        public List<AdditionalFile> additionalFileList = new ArrayList<>();
    }

    private static class ModifiedFile {
        public String filename;
        public String action;
        public String plan;
        public String note;
    }

    private static class Agent2ParsedResult {
        public List<ModifiedFile> modifiedFileList = new ArrayList<>();
    }

    public SseEmitter runPipeline(String runId, String model) {
        return runPipelineForOperation("modify", runId, model);
    }

    public SseEmitter runAddPipeline(String runId, String model) {
        return runPipelineForOperation("add", runId, model);
    }

    private SseEmitter runPipelineForOperation(
            String operation,
            String runId,
            String model
    ) {
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
            agentRunRegistry.fail(runId, new IOException("Python Agent stream was cancelled or timed out."));
        };
        Future<?> future;
        try {
            future = agentPipelineExecutor.submit(() -> {
            try {
                pythonModifiedMethods = Collections.emptySet();
                AgentLanguage language = context.language();
                Set<String> existingPythonFiles = new HashSet<>(
                        ListFileHelper.findPythonFiles(context.sourceRoot())
                );
                sendStatus(eventStream, language.stageOneDescription());
                String agent1Prompt = buildAgent1Prompt(operation, context, language);
                Agent1ParsedResult agent1ParsedResult = requestAndParseAgent1(
                        agent1Prompt,
                        eventStream,
                        model,
                        existingPythonFiles,
                        language
                );

                String extraInfo = "None";
                if (agent1ParsedResult.needAdditionalFile) {
                    StringBuilder extraBuilder = new StringBuilder();
                    for (AdditionalFile file : agent1ParsedResult.additionalFileList) {
                        String filename = normalizePythonPath(file.filename);
                        String fileContent = ListFileHelper.getPythonFileContent(context.sourceRoot(), filename);
                        extraBuilder.append("filename: ").append(filename).append("\n");
                        extraBuilder.append("recommendReason: ").append(file.recommendReason).append("\n");
                        extraBuilder.append("fileContent: ").append(fileContent).append("\n=======================\n");
                    }
                    extraInfo = extraBuilder.toString();
                }

                sendStatus(eventStream, language.stageTwoDescription());
                String agent2Prompt = buildAgent2Prompt(operation, context, extraInfo, language);
                Agent2ParsedResult agent2ParsedResult = requestAndParseAgent2(
                        agent2Prompt,
                        eventStream,
                        model,
                        language
                );

                Map<String, String> map = new LinkedHashMap<>();
                for (ModifiedFile file : agent2ParsedResult.modifiedFileList) {
                    String filename = normalizePythonPath(file.filename);
                    if ("delete".equalsIgnoreCase(file.action)) {
                        sendStatus(eventStream, language.stageThreeDescription(filename));
                        map.put(filename, DELETE_FILE_SENTINEL);
                        continue;
                    }
                    sendStatus(eventStream, language.stageThreeDescription(filename));
                    boolean createMode = !existingPythonFiles.contains(filename);
                    String fileContent = createMode
                            ? ""
                            : ListFileHelper.getPythonFileContent(context.sourceRoot(), filename);
                    String plan = "filename: " + filename + "\n"
                            + "modificationPlan: " + file.plan + "\n"
                            + "modificationNote: " + file.note + "\n";
                    String agent3Prompt = buildAgent3Prompt(
                            operation,
                            context,
                            plan,
                            fileContent,
                            createMode,
                            language
                    );
                    String generatedContent = requestAndApplyAgent3(
                            agent3Prompt,
                            eventStream,
                            model,
                            filename,
                            fileContent,
                            createMode,
                            language
                    );
                    map.put(filename, generatedContent);
                }

                agentRunRegistry.complete(runId, map);
                terminal.set(true);
                try {
                    sendCompleted(eventStream, language.pipelineCompleteDescription());
                } catch (IOException ignored) {
                    logger.debug("Python Agent run completed after its SSE client disconnected: {}", runId);
                }
            } catch (Exception e) {
                if (terminal.get()) {
                    try {
                        if (agentRunRegistry.status(runId) == AgentRunRegistry.Status.COMPLETED) {
                            return;
                        }
                    } catch (IllegalArgumentException | IllegalStateException ignored) {
                        // The run was explicitly discarded while this worker was unwinding.
                    }
                }
                terminal.set(true);
                agentRunRegistry.fail(runId, e);
                try {
                    sendFailed(eventStream, context.language().pipelineErrorDescription() + " " + safeFailureMessage(e));
                } catch (IOException ignored) {
                }
            }
        });
        } catch (RuntimeException exception) {
            terminal.set(true);
            agentRunRegistry.fail(runId, exception);
            throw exception;
        }
        futureRef.set(future);
        agentRunRegistry.registerCancellation(runId, cancel);
        CompletableFuture.delayedExecutor(30, TimeUnit.MINUTES).execute(cancel);

        return subscriber;
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
            return parseAgent1Result(response, existingFiles);
        } catch (RuntimeException exception) {
            sendStatus(eventSink, retryMessage(language, "Agent1"));
            String retried = llmClient.streamGenerateWithPrompt(
                    repairPrompt(prompt, "Agent1", exception),
                    eventSink,
                    model
            );
            return parseAgent1Result(retried, existingFiles);
        }
    }

    private Agent2ParsedResult requestAndParseAgent2(
            String prompt,
            AgentEventSink eventSink,
            String model,
            AgentLanguage language
    ) throws IOException {
        String response = llmClient.streamGenerateWithPrompt(prompt, eventSink, model);
        try {
            return parseAgent2Result(response);
        } catch (RuntimeException exception) {
            sendStatus(eventSink, retryMessage(language, "Agent2"));
            String retried = llmClient.streamGenerateWithPrompt(
                    repairPrompt(prompt, "Agent2", exception),
                    eventSink,
                    model
            );
            return parseAgent2Result(retried);
        }
    }

    private Agent1ParsedResult parseAgent1Result(String agent1Result, Set<String> existingFiles) {
        JsonNode root = readJsonObject(agent1Result);
        if (!root.path("needAdditionalFile").isBoolean()
                || !root.path("additionalFileList").isArray()) {
            throw new IllegalArgumentException("Agent1 returned an invalid Python file-retrieval schema.");
        }
        if (root.path("additionalFileList").size() > MAX_ADDITIONAL_FILES) {
            throw new IllegalArgumentException("Agent1 requested too many Python files.");
        }
        Agent1ParsedResult result = new Agent1ParsedResult();
        result.needAdditionalFile = root.path("needAdditionalFile").asBoolean(false);
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : root.path("additionalFileList")) {
            String filename = normalizePythonPath(requiredText(fileNode, "filename", "Agent1"));
            if (!existingFiles.contains(filename)) {
                throw new IllegalArgumentException(
                        "Agent1 requested a Python file outside the supplied list: " + filename
                );
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
            throw new IllegalArgumentException("Agent1 Python retrieval flag does not match its file list.");
        }
        return result;
    }

    private Agent2ParsedResult parseAgent2Result(String agent2Result) {
        JsonNode root = readJsonObject(agent2Result);
        if (!root.path("modifiedFileList").isArray()) {
            throw new IllegalArgumentException("Agent2 returned an invalid Python modification schema.");
        }
        if (root.path("modifiedFileList").size() > MAX_MODIFIED_FILES) {
            throw new IllegalArgumentException("Agent2 planned too many Python files.");
        }
        Agent2ParsedResult result = new Agent2ParsedResult();
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : root.path("modifiedFileList")) {
            String filename = normalizePythonPath(requiredText(fileNode, "filename", "Agent2"));
            if (!seen.add(filename)) {
                throw new IllegalArgumentException("Agent2 returned a duplicate Python file: " + filename);
            }
            String action = fileNode.path("action").asText("rewrite").trim().toLowerCase();
            if (!Set.of("rewrite", "delete").contains(action)) {
                throw new IllegalArgumentException("Agent2 returned an unsupported Python action: " + action);
            }
            ModifiedFile file = new ModifiedFile();
            file.filename = filename;
            file.action = action;
            file.plan = requiredText(fileNode, "plan", "Agent2");
            file.note = optionalText(fileNode, "note");
            result.modifiedFileList.add(file);
        }
        if (result.modifiedFileList.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent2 planned no Python file changes for the submitted requirement."
            );
        }
        return result;
    }

    private JsonNode readJsonObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Agent returned an empty response.");
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("Cannot find JSON object in Agent response.");
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(objectStart, objectEnd + 1));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Agent response must be a JSON object.");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent returned malformed JSON.", exception);
        }
    }

    String applyAgent3Result(
            String response,
            String filename,
            String originalContent,
            boolean createMode
    ) {
        try {
            String pythonCode = SearchReplacePatch.apply(response, originalContent, createMode);
            validatePythonSyntax(filename, pythonCode);
            return pythonCode;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Agent3 Search/Replace result is invalid for " + filename + ": "
                            + safeFailureMessage(exception),
                    exception
            );
        }
    }

    String requestAndApplyAgent3(
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
                    throw new IOException("Python Agent pipeline was cancelled.", exception);
                }
                lastFailure = exception;
                logger.warn(
                        "Python Agent3 failed for {} on attempt {}/{}: {}",
                        filename,
                        attempt + 1,
                        MAX_AGENT3_RETRIES + 1,
                        safeFailureMessage(exception)
                );
                if (attempt == MAX_AGENT3_RETRIES) {
                    throw new IOException(
                            "Python Agent3 failed for " + filename + " after "
                                    + (MAX_AGENT3_RETRIES + 1) + " attempts: "
                                    + safeFailureMessage(exception),
                            exception
                    );
                }
            }
        }
        throw new IOException("Python Agent3 failed for " + filename + ".", lastFailure);
    }

    private void validatePythonSyntax(String filename, String content) {
        String pythonExec = environmentOrDefault("REPOSUMMARY_PYTHON", "python3");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    pythonExec,
                    "-c",
                    "import ast,sys; ast.parse(sys.stdin.read(), filename=sys.argv[1])",
                    filename
            ).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(content.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(PYTHON_SYNTAX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Python syntax validation timed out.");
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new IllegalArgumentException(
                        "Generated Python syntax is invalid"
                                + (error.isBlank() ? "." : ": " + boundedError(error))
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalArgumentException("Python syntax validation was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to run Python syntax validation with " + pythonExec + ".",
                    exception
            );
        }
    }

    private String boundedError(String error) {
        String singleLine = error.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private String normalizePythonPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Python filename is required.");
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.contains("../") || !normalized.endsWith(".py")) {
            throw new IllegalArgumentException("Invalid Python filename: " + path);
        }
        return normalized;
    }

    private String buildAgent1Prompt(
            String operation,
            AgentRunContext context,
            AgentLanguage language
    ) {
        String operationGoal = switch (operation) {
            case "add" -> "adding a new feature";
            case "delete" -> "deleting the selected feature and cleaning up direct usages";
            default -> "modifying the selected feature";
        };
        String promptTemplate = """
                You are Agent1 for a Python project. Analyze whether the provided FocusGraph context is enough for %s.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                FocusGraph context:
                \"\"\"
                %s
                \"\"\"

                Complete Python file list, using repository-relative .py paths:
                \"\"\"
                %s
                \"\"\"

                If more files are needed, return only repository-relative .py paths.
                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "needAdditionalFile": true,
                  "additionalFileList": [
                    {"filename": "package/module.py", "recommendReason": "why this file is needed"}
                  ]
                }
                Write recommendReason values in %s.
                If no additional files are needed, return false and an empty array.
                """;
        return String.format(
                promptTemplate,
                operationGoal,
                context.newRequest(),
                context.oldRequest(),
                context.relatedCodes(),
                context.allFiles(),
                language.promptLanguageName()
        );
    }

    private String buildAgent2Prompt(
            String operation,
            AgentRunContext context,
            String extraInfo,
            AgentLanguage language
    ) {
        String operationGuidance = switch (operation) {
            case "add" -> """
                    - Plan the files that must be edited or created to implement the new feature.
                    - Prefer extending existing entry points shown by FocusGraph when that is the natural integration path.
                    - You may include new repository-relative .py paths when a new module/file is required.
                    - Use action="rewrite" for edited or newly created files.
                    """;
            case "delete" -> """
                    - Plan the files that must be edited to remove the selected feature and clean up direct callers/usages.
                    - Preserve unrelated behavior, public APIs, imports, tests hooks, and compatibility code unless they are exclusively part of the deleted feature.
                    - Use action="rewrite" for files that should remain with code removed.
                    - Use action="delete" only when the entire .py file is exclusively owned by the deleted feature and should be removed.
                    """;
            default -> """
                    - Plan the files that must be edited to implement the feature change.
                    - Preserve unrelated behavior and keep the edit scope minimal.
                    - Use action="rewrite" for every changed file.
                    """;
        };
        String promptTemplate = """
                You are Agent2 for a Python project. Produce a concrete file-level plan.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                FocusGraph context:
                \"\"\"
                %s
                \"\"\"

                Extra file context:
                \"\"\"
                %s
                \"\"\"

                Operation-specific rules:
                %s

                The submitted requirement is authoritative and must produce a concrete change. Do not return an
                empty modifiedFileList merely because the requested behavior is small or unconventional.
                Return only repository-relative .py file paths that must be edited, created, or deleted.
                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "modifiedFileList": [
                    {
                      "filename": "package/module.py",
                      "action": "rewrite",
                      "plan": "Detailed modification plan.",
                      "note": "Important constraints."
                    }
                  ]
                }
                Write plan and note values in %s.
                """;
        return String.format(
                promptTemplate,
                context.newRequest(),
                context.oldRequest(),
                context.relatedCodes(),
                extraInfo,
                operationGuidance,
                language.promptLanguageName()
        );
    }

    private String buildAgent3Prompt(
            String operation,
            AgentRunContext context,
            String plan,
            String fileContent,
            boolean createMode,
            AgentLanguage language
    ) {
        String operationGuidance = switch (operation) {
            case "add" -> "Implement the new feature and integrate it with the existing code path described by the plan.";
            case "delete" -> "Remove the selected feature from this file and clean up now-unused imports/helpers only when they are exclusively tied to the deleted feature.";
            default -> "Implement the feature modification.";
        };
        String outputContract = createMode ? """
                The target is a new or empty file, so return exactly one CREATE block:
                <<<<<<< CREATE
                # complete new Python file
                >>>>>>> CREATE

                CREATE is the only case where complete-file generation is allowed.
                """ : """
                Return only minimal exact Search/Replace blocks in application order:
                <<<<<<< SEARCH
                exact text copied from the current target file
                =======
                replacement text
                >>>>>>> REPLACE

                Rules:
                - Never return the complete existing file.
                - SEARCH must be non-empty and match exactly once in the current file at that step.
                - Every REPLACE must differ from its SEARCH and must implement part of the requirement.
                - Include enough unchanged context to make every SEARCH unique.
                - Use additional blocks for additional edits, including import changes.
                - Do not use ellipses, line numbers, regexes, explanations, or omitted-code placeholders.
                """;
        String promptTemplate = """
                You are Agent3. Produce precise Search/Replace edits for one Python source file.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                Operation-specific rule:
                %s

                Target file and plan:
                \"\"\"
                %s
                \"\"\"

                Original full file content:
                \"\"\"
                %s
                \"\"\"

                %s

                The resulting file must differ from the original, remain syntactically valid Python, and concretely
                implement the submitted requirement. Do not wrap the protocol in Markdown fences.
                Write newly added or modified natural-language comments in %s.
                """;
        return String.format(
                promptTemplate,
                context.newRequest(),
                context.oldRequest(),
                operationGuidance,
                plan,
                fileContent,
                outputContract,
                language.promptLanguageName()
        );
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
                + "Generate a fresh protocol response against the ORIGINAL target content. Every REPLACE must "
                + "differ from SEARCH, and the resulting file must differ from the original. Correct the reported "
                + "failure and do not apply edits to the previous response.";
    }

    private String repairPrompt(String originalPrompt, String agent, Exception failure) {
        return originalPrompt + "\n\nYour previous " + agent
                + " response violated the required contract for this reason:\n"
                + safeFailureMessage(failure)
                + "\nTry once more and follow the output schema exactly.";
    }

    private String retryMessage(AgentLanguage language, String agent) {
        return language == AgentLanguage.CN
                ? "\n# " + agent + " 输出校验失败，正在按严格协议重试。\n"
                : "\n# " + agent + " output validation failed; retrying with the strict contract.\n";
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

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private void ensureNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Python Agent pipeline was cancelled.");
        }
    }
}
