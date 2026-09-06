package com.mycode.service.code;

import com.mycode.config.ProjectState;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ListFileHelper;
import com.mycode.helper.ProjectFilePath;
import com.mycode.helper.ProjectPathMapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

abstract class ThreeStageAgentPipelineSupport extends AgentService {
    private static final int MAX_AGENT1_CONTEXT_ROUNDS = 5;
    private static final int MAX_MODIFIED_FILES = 20;
    private static final int MAX_CORE_CONTEXT_CHARS = contextLimit(
            "AGENT_MAX_CORE_CONTEXT_CHARS",
            contextLimit("JAVA_AGENT_MAX_CORE_CONTEXT_CHARS", 0)
    );
    private static final int MAX_REFERENCE_CONTEXT_CHARS = contextLimit(
            "AGENT_MAX_REFERENCE_CONTEXT_CHARS",
            contextLimit("JAVA_AGENT_MAX_REFERENCE_CONTEXT_CHARS", 0)
    );
    private static final int MAX_PRIOR_GENERATION_CHARS = 80_000;
    private static final int MAX_AGENT_RETRIES = 5;
    private static final int MAX_RETRY_RESPONSE_CHARS = 20_000;
    private static final long PIPELINE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final String PROTECTED_SYMBOL_MARKER = "PROTECTED_SYMBOL:";
    private static final String ALLOWED_DELETE_FILE_MARKER = "ALLOWED_DELETE_FILE:";
    private static final String FEATURE_SYMBOL_MARKER = "FEATURE_SYMBOL:";
    private static final String NO_CHANGES_REQUIRED = "NO_CHANGES_REQUIRED";
    private static final String SKIP_FILE_SAFELY = "SKIP_FILE_SAFELY";
    private static final int MAX_LIVE_REFERENCE_FILES = 24;
    private static final long MAX_LIVE_REFERENCE_FILE_BYTES = 2L * 1024 * 1024;

    private static class AdditionalFile {
        String filename;
        String recommendReason;
    }

    private static class Agent1ParsedResult {
        boolean needAdditionalFile;
        List<AdditionalFile> additionalFileList = new ArrayList<>();
        List<AdditionalFile> removeContextFileList = new ArrayList<>();

        boolean hasContextChanges() {
            return !additionalFileList.isEmpty() || !removeContextFileList.isEmpty();
        }
    }

    private record LoadedContextFile(String filename, String recommendReason, String content) {
    }

    private record Agent1ContextResult(String coreContext, String additionalContext) {
    }

    private static class ModifiedFile {
        String filename;
        String action;
        String plan;
        String note;
    }

    private static class Agent2ParsedResult {
        List<ModifiedFile> modifiedFileList = new ArrayList<>();
    }

    private record Agent3Result(String content, boolean noChangesRequired, boolean skippedForSafety) {
    }

    protected SseEmitter runThreeStagePipeline(
            String runId,
            String model,
            AgentRunMode mode
    ) {
        String projectLanguage = mode.projectLanguage();
        AgentOperation operation = mode.operation();
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
            ProjectState.CapturedContext projectContext = ProjectState.capture();
            Future<?> future = agentPipelineExecutor.submit(() -> projectContext.run(() -> executePipeline(
                    context,
                    model,
                    projectLanguage,
                    operation,
                    eventStream,
                    terminal
            )));
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
            String projectLanguage,
            AgentOperation operation,
            AgentEventSink eventSink,
            AtomicBoolean terminal
    ) {
        try {
            if ("Python".equals(projectLanguage)) {
                ProjectState.getInstance().setPythonModifiedMethods(Set.of());
            }
            Set<String> existingFiles = new HashSet<>(ListFileHelper.findAllFiles(context.projectRoot()));
            String liveSourceVerification = operation.isDeletion()
                    ? buildLiveSourceVerification(context, existingFiles)
                    : "";

            Agent1ContextResult refinedContext = refineAgent1Context(
                    context,
                    projectLanguage,
                    operation,
                    liveSourceVerification,
                    existingFiles,
                    eventSink,
                    model
            );
            String workingCoreContext = refinedContext.coreContext();
            String extraInfo = refinedContext.additionalContext();

            Set<String> protectedSymbols = markedValues(context.relatedCodes(), PROTECTED_SYMBOL_MARKER);
            Set<String> allowedDeleteFiles = markedValues(context.relatedCodes(), ALLOWED_DELETE_FILE_MARKER).stream()
                    .map(ProjectFilePath::normalize)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            sendStatus(eventSink, context.language().stageTwoDescription());
            String agent2Prompt = buildAgent2Prompt(
                    context,
                    workingCoreContext,
                    extraInfo,
                    projectLanguage,
                    operation,
                    liveSourceVerification
            );
            Agent2ParsedResult agent2 = requestAndParseAgent2(
                    context,
                    "agent2",
                    agent2Prompt,
                    eventSink,
                    model,
                    context.projectRoot(),
                    context.language(),
                    operation,
                    projectLanguage,
                    allowedDeleteFiles,
                    protectedSymbols
            );
            String globalPlan = renderGlobalPlan(agent2.modifiedFileList);
            String referenceContext = boundedPromptSection(
                    workingCoreContext
                            + "\n\n" + liveSourceVerification
                            + "\n\n补充文件上下文：\n" + extraInfo,
                    MAX_REFERENCE_CONTEXT_CHARS
            );
            StringBuilder priorGenerations = new StringBuilder();
            Map<String, String> modifications = new LinkedHashMap<>();
            boolean enforceWholeFileDeleteAllowlist = operation.isDeletion()
                    && "Python".equalsIgnoreCase(projectLanguage);

            sendStatus(eventSink, context.language().stageThreeDescription());
            for (ModifiedFile file : agent2.modifiedFileList) {
                ensureNotInterrupted();
                sendStatus(eventSink, context.language().stageThreeFileDescription(file.filename));
                Path targetPath = ProjectFilePath.resolve(Path.of(context.projectRoot()), file.filename);
                if (operation.isDeletion() && !Files.isRegularFile(targetPath)) {
                    throw new IllegalArgumentException(
                            "The deletion plan references a file that does not exist: " + file.filename
                    );
                }
                if ("delete".equals(file.action)) {
                    if (enforceWholeFileDeleteAllowlist && !allowedDeleteFiles.contains(file.filename)) {
                        throw new IllegalArgumentException(
                                "Delete Agent cannot delete a whole file outside the deterministic deletion boundary: "
                                        + file.filename
                        );
                    }
                    if (operation.isDeletion()) {
                        String originalContent = ListFileHelper.getProjectFileContent(
                                context.projectRoot(),
                                file.filename
                        );
                        validateProtectedSymbols(file.filename, originalContent, "", protectedSymbols);
                    }
                    modifications.put(file.filename, DELETE_FILE_SENTINEL);
                    priorGenerations.append("\n已删除文件：").append(file.filename).append("\n");
                    continue;
                }
                boolean createMode = !Files.isRegularFile(targetPath);
                String fileContent = createMode
                        ? ""
                        : ListFileHelper.getProjectFileContent(context.projectRoot(), file.filename);
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
                        projectLanguage,
                        operation
                );
                Agent3Result generated = requestAndApplyAgent3Result(
                        context,
                        agent3Prompt,
                        eventSink,
                        model,
                        file.filename,
                        fileContent,
                        createMode,
                        context.language(),
                        operation,
                        protectedSymbols
                );
                if (generated.noChangesRequired()) {
                    sendStatus(eventSink, context.language().fileAlreadyCurrentDescription(file.filename));
                    continue;
                }
                if (generated.skippedForSafety()) {
                    sendStatus(eventSink, context.language().fileSkippedForSafetyDescription(file.filename));
                    continue;
                }
                String generatedContent = generated.content();
                modifications.put(file.filename, generatedContent);
                priorGenerations.append("\n文件：").append(file.filename).append("\n")
                        .append(generatedContent).append("\n文件结束\n");
            }

            agentRunRegistry.complete(
                    context.runId(),
                    modifications,
                    operation.isDeletion() && modifications.isEmpty()
            );
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
            String userMessage = userFacingFailureMessage(context.language(), exception);
            agentRunRegistry.fail(context.runId(), new IllegalStateException(userMessage, exception));
            logger.error("{} Agent pipeline failed for run {}", projectLanguage, context.runId(), exception);
            try {
                sendFailed(eventSink, "\n" + userMessage + "\n");
            } catch (IOException ignored) {
                logger.debug("Could not deliver Agent failure event for run {}", context.runId());
            }
        }
    }

    private Agent1ContextResult refineAgent1Context(
            AgentRunContext context,
            String projectLanguage,
            AgentOperation operation,
            String liveSourceVerification,
            Set<String> existingFiles,
            AgentEventSink eventSink,
            String model
    ) throws IOException {
        String originalCoreContext = context.relatedCodes() == null ? "" : context.relatedCodes();
        Set<String> optionalReasoningFiles = reasoningContextFiles(originalCoreContext);
        Set<String> excludedReasoningFiles = new LinkedHashSet<>();
        Map<String, LoadedContextFile> loadedFiles = new LinkedHashMap<>();
        String workingCoreContext = originalCoreContext;

        for (int round = 1; round <= MAX_AGENT1_CONTEXT_ROUNDS; round++) {
            ensureNotInterrupted();
            if (round == 1) {
                sendStatus(eventSink, context.language().stageOneDescription());
            } else {
                sendStatus(eventSink, context.language().stageOneRecheckDescription());
            }

            String additionalContext = renderAdditionalContext(loadedFiles, context.language());
            Set<String> removableFiles = new LinkedHashSet<>(optionalReasoningFiles);
            removableFiles.removeAll(excludedReasoningFiles);
            removableFiles.addAll(loadedFiles.keySet());
            String prompt = buildAgent1Prompt(
                    context,
                    projectLanguage,
                    operation,
                    liveSourceVerification,
                    workingCoreContext,
                    additionalContext,
                    removableFiles,
                    round
            );
            String stage = round == 1
                    ? "agent1"
                    : round == 2 ? "agent1-recheck" : "agent1-recheck-" + (round - 1);
            Agent1ParsedResult result = requestAndParseAgent1(
                    context,
                    stage,
                    prompt,
                    eventSink,
                    model,
                    existingFiles,
                    removableFiles,
                    loadedFiles.keySet(),
                    context.language()
            );
            if (!result.hasContextChanges()) {
                return new Agent1ContextResult(workingCoreContext, additionalContext);
            }

            for (AdditionalFile file : result.removeContextFileList) {
                loadedFiles.remove(file.filename);
                if (optionalReasoningFiles.contains(file.filename)) {
                    excludedReasoningFiles.add(file.filename);
                }
            }
            for (AdditionalFile file : result.additionalFileList) {
                loadedFiles.put(file.filename, new LoadedContextFile(
                        file.filename,
                        file.recommendReason,
                        ListFileHelper.getProjectFileContent(context.projectRoot(), file.filename)
                ));
            }
            workingCoreContext = removeReasoningFileSections(originalCoreContext, excludedReasoningFiles);
        }

        return new Agent1ContextResult(
                workingCoreContext,
                renderAdditionalContext(loadedFiles, context.language())
        );
    }

    private Agent1ParsedResult requestAndParseAgent1(
            AgentRunContext context,
            String stage,
            String prompt,
            AgentEventSink eventSink,
            String model,
            Set<String> existingFiles,
            Set<String> removableContextFiles,
            Set<String> alreadyLoadedFiles,
            AgentLanguage language
    ) throws IOException {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= MAX_AGENT_RETRIES; attempt++) {
            ensureNotInterrupted();
            if (attempt > 0) {
                sendStatus(eventSink, retryMessage(language, "Agent1", attempt));
            }
            try {
                String response = generateAgentResponse(
                        context,
                        attempt == 0 ? stage : stage + "-repair-" + attempt,
                        attempt == 0 ? prompt : repairPrompt(prompt, "Agent1", lastFailure),
                        withoutDeltas(eventSink),
                        model
                );
                Agent1ParsedResult parsed = parseAndValidateAgent1(
                        response,
                        existingFiles,
                        removableContextFiles,
                        alreadyLoadedFiles
                );
                publishValidatedResponse(eventSink, response);
                return parsed;
            } catch (IOException | RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Agent1 was cancelled.", exception);
                }
                lastFailure = exception;
                logger.warn(
                        "Agent1 failed on attempt {}/{}: {}",
                        attempt + 1,
                        MAX_AGENT_RETRIES + 1,
                        safeFailureMessage(exception)
                );
                if (attempt == MAX_AGENT_RETRIES) {
                    throw new IOException(
                            "Agent1 failed after " + (MAX_AGENT_RETRIES + 1)
                                    + " attempts: " + safeFailureMessage(exception),
                            exception
                    );
                }
            }
        }
        throw new IOException("Agent1 failed after all retry attempts.", lastFailure);
    }

    private Agent2ParsedResult requestAndParseAgent2(
            AgentRunContext context,
            String stage,
            String prompt,
            AgentEventSink eventSink,
            String model,
            String sourceRoot,
            AgentLanguage language,
            AgentOperation operation,
            String projectLanguage,
            Set<String> allowedDeleteFiles,
            Set<String> protectedSymbols
    ) throws IOException {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= MAX_AGENT_RETRIES; attempt++) {
            ensureNotInterrupted();
            if (attempt > 0) {
                sendStatus(eventSink, retryMessage(language, "Agent2", attempt));
            }
            try {
                String response = generateAgentResponse(
                        context,
                        attempt == 0 ? stage : stage + "-repair-" + attempt,
                        attempt == 0 ? prompt : agent2RepairPrompt(prompt, lastFailure),
                        withoutDeltas(eventSink),
                        model
                );
                Agent2ParsedResult parsed = parseAndValidateAgent2(
                        response,
                        sourceRoot,
                        operation,
                        projectLanguage,
                        allowedDeleteFiles,
                        protectedSymbols
                );
                publishValidatedResponse(eventSink, response);
                return parsed;
            } catch (IOException | RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Agent2 was cancelled.", exception);
                }
                lastFailure = exception;
                logger.warn(
                        "Agent2 failed on attempt {}/{}: {}",
                        attempt + 1,
                        MAX_AGENT_RETRIES + 1,
                        safeFailureMessage(exception)
                );
                if (attempt == MAX_AGENT_RETRIES) {
                    throw new IOException(
                            "Agent2 failed after " + (MAX_AGENT_RETRIES + 1)
                                    + " attempts: " + safeFailureMessage(exception),
                            exception
                    );
                }
            }
        }
        throw new IOException("Agent2 failed after all retry attempts.", lastFailure);
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
        return requestAndApplyAgent3Result(
                null,
                prompt,
                eventSink,
                model,
                filename,
                originalContent,
                createMode,
                language,
                AgentOperation.MODIFY,
                Set.of()
        ).content();
    }

    private Agent3Result requestAndApplyAgent3Result(
            AgentRunContext context,
            String prompt,
            AgentEventSink eventSink,
            String model,
            String filename,
            String originalContent,
            boolean createMode,
            AgentLanguage language,
            AgentOperation operation,
            Set<String> protectedSymbols
    ) throws IOException {
        Exception lastFailure = null;
        String previousResponse = "";
        for (int attempt = 0; attempt <= MAX_AGENT_RETRIES; attempt++) {
            ensureNotInterrupted();
            String attemptPrompt = attempt == 0
                    ? prompt
                    : buildAgent3RetryPrompt(prompt, attempt, lastFailure, previousResponse);
            if (attempt > 0) {
                sendStatus(eventSink, agent3RetryMessage(language, filename, attempt, lastFailure));
            }

            try {
                previousResponse = context == null
                        ? llmClient.streamGenerateWithPrompt(attemptPrompt, eventSink, model)
                        : generateAgentResponse(
                                context,
                                "agent3-" + filename + "-attempt-" + (attempt + 1),
                                attemptPrompt,
                                withoutDeltas(eventSink),
                                model
                        );
                if (isNoChangesRequired(previousResponse)) {
                    return new Agent3Result(originalContent, true, false);
                }
                if (isSkipFileSafely(previousResponse)) {
                    if (!isSafetyBoundaryFailure(lastFailure)) {
                        throw new IllegalArgumentException(
                                SKIP_FILE_SAFELY + " is only allowed after this file triggered a safety boundary."
                        );
                    }
                    return new Agent3Result(originalContent, false, true);
                }
                String generatedContent = applyAgent3Result(
                        previousResponse,
                        filename,
                        originalContent,
                        createMode,
                        context
                );
                if (operation.isDeletion()) {
                    validateProtectedSymbols(filename, originalContent, generatedContent, protectedSymbols);
                }
                if (context != null) {
                    publishValidatedResponse(eventSink, previousResponse);
                }
                return new Agent3Result(generatedContent, false, false);
            } catch (IOException | RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Agent pipeline was cancelled.", exception);
                }
                lastFailure = exception;
                logger.warn(
                        "Agent3 failed for {} on attempt {}/{}: {}",
                        filename,
                        attempt + 1,
                        MAX_AGENT_RETRIES + 1,
                        safeFailureMessage(exception)
                );
                if (attempt == MAX_AGENT_RETRIES) {
                    throw new IOException(
                            "Agent3 failed for " + filename + " after " + (MAX_AGENT_RETRIES + 1)
                                    + " attempts: " + safeFailureMessage(exception),
                            exception
                    );
                }
            }
        }
        throw new IOException("Agent3 failed for " + filename + ".", lastFailure);
    }

    private AgentEventSink withoutDeltas(AgentEventSink eventSink) {
        return (eventName, content) -> {
            if (!"delta".equals(eventName)) {
                eventSink.send(eventName, content);
            }
        };
    }

    private void publishValidatedResponse(AgentEventSink eventSink, String response) throws IOException {
        String content = response == null ? "" : response.strip();
        if (!content.isEmpty()) {
            sendEvent(eventSink, "delta", content + "\n");
        }
    }

    private boolean isNoChangesRequired(String response) {
        return response != null && NO_CHANGES_REQUIRED.equals(response.trim());
    }

    private boolean isSkipFileSafely(String response) {
        return response != null && SKIP_FILE_SAFELY.equals(response.trim());
    }

    private boolean isSafetyBoundaryFailure(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("protected shared symbol")
                    || message.contains("deterministic deletion boundary"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Agent1ParsedResult parseAndValidateAgent1(
            String response,
            Set<String> existingFiles,
            Set<String> removableContextFiles,
            Set<String> alreadyLoadedFiles
    ) {
        JsonNode root = readJsonObject(response);
        JsonNode needNode = root.get("needAdditionalFile");
        JsonNode filesNode = root.get("additionalFileList");
        JsonNode removeFilesNode = root.get("removeContextFileList");
        if (needNode == null || !needNode.isBoolean()) {
            throw new IllegalArgumentException("Agent1 must return boolean needAdditionalFile.");
        }
        if (filesNode == null || !filesNode.isArray()) {
            throw new IllegalArgumentException("Agent1 must return an additionalFileList array.");
        }
        if (removeFilesNode != null && !removeFilesNode.isArray()) {
            throw new IllegalArgumentException("Agent1 removeContextFileList must be an array.");
        }

        Agent1ParsedResult result = new Agent1ParsedResult();
        result.needAdditionalFile = needNode.booleanValue();
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : filesNode) {
            String filename = ProjectFilePath.normalize(requiredText(fileNode, "filename", "Agent1"));
            if (!existingFiles.contains(filename)) {
                throw new IllegalArgumentException("Agent1 requested a file outside the supplied project list: " + filename);
            }
            if (!seen.add(filename)) {
                continue;
            }
            if (alreadyLoadedFiles.contains(filename)) {
                throw new IllegalArgumentException("Agent1 requested a file already loaded in the working context: " + filename);
            }
            AdditionalFile file = new AdditionalFile();
            file.filename = filename;
            file.recommendReason = requiredText(fileNode, "recommendReason", "Agent1");
            result.additionalFileList.add(file);
        }
        if (result.needAdditionalFile != !result.additionalFileList.isEmpty()) {
            throw new IllegalArgumentException("Agent1 needAdditionalFile does not match additionalFileList.");
        }
        if (removeFilesNode != null) {
            Set<String> removed = new HashSet<>();
            for (JsonNode fileNode : removeFilesNode) {
                String filename = ProjectFilePath.normalize(requiredText(fileNode, "filename", "Agent1"));
                if (!removableContextFiles.contains(filename)) {
                    throw new IllegalArgumentException(
                            "Agent1 attempted to remove a file outside the removable working context: " + filename
                    );
                }
                if (seen.contains(filename)) {
                    throw new IllegalArgumentException(
                            "Agent1 cannot add and remove the same context file in one round: " + filename
                    );
                }
                if (!removed.add(filename)) {
                    continue;
                }
                AdditionalFile file = new AdditionalFile();
                file.filename = filename;
                file.recommendReason = requiredText(fileNode, "recommendReason", "Agent1");
                result.removeContextFileList.add(file);
            }
        }
        return result;
    }

    private Agent2ParsedResult parseAndValidateAgent2(
            String response,
            String sourceRoot,
            AgentOperation operation,
            String projectLanguage,
            Set<String> allowedDeleteFiles,
            Set<String> protectedSymbols
    ) {
        JsonNode root = readAgent2Json(response);
        JsonNode filesNode = root.isArray() ? root : root.get("modifiedFileList");
        if (filesNode == null || !filesNode.isArray()) {
            throw new IllegalArgumentException("Agent2 must return a modifiedFileList array.");
        }
        if (filesNode.size() > MAX_MODIFIED_FILES) {
            throw new IllegalArgumentException("Agent2 planned too many modified files.");
        }

        Agent2ParsedResult result = new Agent2ParsedResult();
        Set<String> seen = new HashSet<>();
        for (JsonNode fileNode : filesNode) {
            String filename = ProjectFilePath.normalize(requiredText(fileNode, "filename", "Agent2"));
            Path resolved = ProjectFilePath.resolve(Path.of(sourceRoot), filename);
            if (!seen.add(filename)) {
                throw new IllegalArgumentException("Agent2 returned a duplicate file: " + filename);
            }
            ModifiedFile file = new ModifiedFile();
            file.filename = filename;
            file.action = fileNode.path("action").asText("rewrite").trim().toLowerCase();
            if (!Set.of("rewrite", "delete").contains(file.action)) {
                throw new IllegalArgumentException("Agent2 returned an unsupported file action: " + file.action);
            }
            if ((operation.isDeletion() || "delete".equals(file.action)) && !Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException(
                        "The plan references a file that does not exist in the current project: " + filename
                );
            }
            if (operation.isDeletion() && "delete".equals(file.action)) {
                boolean enforceWholeFileDeleteAllowlist = "Python".equalsIgnoreCase(projectLanguage);
                if (enforceWholeFileDeleteAllowlist && !allowedDeleteFiles.contains(filename)) {
                    throw new IllegalArgumentException(
                            "Delete Agent cannot delete a whole file outside the deterministic deletion boundary: "
                                    + filename
                    );
                }
                try {
                    String originalContent = ListFileHelper.getProjectFileContent(sourceRoot, filename);
                    validateProtectedSymbols(filename, originalContent, "", protectedSymbols);
                } catch (IOException exception) {
                    throw new IllegalArgumentException(
                            "Agent2 could not inspect the planned deleted file: " + filename,
                            exception
                    );
                }
            }
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
        return applyAgent3Result(response, filename, originalContent, createMode, null);
    }

    private String applyAgent3Result(
            String response,
            String filename,
            String originalContent,
            boolean createMode,
            AgentRunContext context
    ) {
        try {
            if (isNoChangesRequired(response)) {
                return originalContent;
            }
            String generatedContent = SearchReplacePatch.apply(response, originalContent, createMode);
            validateGeneratedContent(context, filename, generatedContent);
            return generatedContent;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Agent3 Search/Replace result is invalid for " + filename + ": "
                            + safeFailureMessage(exception),
                    exception
            );
        }
    }

    private void validateGeneratedContent(AgentRunContext context, String filename, String content) {
        if (filename.endsWith(".java")) {
            validateJavaSource(context, filename, content);
        } else if (filename.endsWith(".py")) {
            validatePythonSyntax(filename, content);
        }
    }

    private void validateJavaSource(AgentRunContext context, String filename, String content) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(content);
        String expectedType = javaTypeName(filename);
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
        expectedJavaPackage(context, filename).ifPresent(expectedPackage -> {
            String actualPackage = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            if (!expectedPackage.equals(actualPackage)) {
                throw new IllegalArgumentException(
                        "Generated file " + filename + " must declare package "
                                + (expectedPackage.isEmpty() ? "<default>" : expectedPackage) + "."
                );
            }
        });
    }

    private Optional<String> expectedJavaPackage(AgentRunContext context, String filename) {
        if (context == null) {
            return Optional.of(JavaFilePath.packageName(filename));
        }
        Path projectRoot = Path.of(context.projectRoot()).toAbsolutePath().normalize();
        Path sourceRoot = Path.of(context.sourceRoot()).toAbsolutePath().normalize();
        return ProjectPathMapping.projectRelativeToSource(projectRoot, sourceRoot, filename)
                .map(JavaFilePath::packageName);
    }

    private String javaTypeName(String filename) {
        String fileName = Path.of(ProjectFilePath.normalize(filename)).getFileName().toString();
        if (!fileName.endsWith(".java") || fileName.length() == ".java".length()) {
            throw new IllegalArgumentException("Invalid Java project path: " + filename);
        }
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private void validatePythonSyntax(String filename, String content) {
        // Spring always injects the shared HTTP client. The small fallback keeps direct, unmanaged
        // service instances used by unit tests from opening a subprocess or requiring a live server.
        if (repoSummaryHttpClient == null) {
            validateBasicPythonSyntax(content);
            return;
        }
        try {
            var request = objectMapper.createObjectNode();
            request.put("filename", filename);
            request.put("content", content);
            repoSummaryHttpClient.postJson("/v1/python/validate", request, 20);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Generated Python syntax is invalid or the RepoSummary validation service is unavailable: "
                            + boundedError(exception.getMessage() == null ? exception.toString() : exception.getMessage()),
                    exception
            );
        }
    }

    private void validateBasicPythonSyntax(String content) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String header = line.startsWith("async ") ? line.substring("async ".length()).stripLeading() : line;
            if ((header.startsWith("def ")
                    || header.startsWith("class ")
                    || header.startsWith("if ")
                    || header.startsWith("elif ")
                    || header.equals("else")
                    || header.startsWith("for ")
                    || header.startsWith("while ")
                    || header.equals("try")
                    || header.startsWith("except")
                    || header.equals("finally")
                    || header.startsWith("with ")
                    || header.startsWith("match ")
                    || header.startsWith("case "))
                    && !header.endsWith(":")) {
                throw new IllegalArgumentException("Generated Python syntax is invalid: block header must end with ':'.");
            }
            boolean singleQuoted = false;
            boolean doubleQuoted = false;
            boolean escaped = false;
            for (int index = 0; index < rawLine.length(); index++) {
                char character = rawLine.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (character == '\\') {
                    escaped = true;
                    continue;
                }
                if (character == '\'' && !doubleQuoted) {
                    singleQuoted = !singleQuoted;
                    continue;
                }
                if (character == '"' && !singleQuoted) {
                    doubleQuoted = !doubleQuoted;
                    continue;
                }
                if (singleQuoted || doubleQuoted || character == '#') {
                    if (character == '#') break;
                    continue;
                }
                if (character == '(') parentheses++;
                if (character == ')') parentheses--;
                if (character == '[') brackets++;
                if (character == ']') brackets--;
                if (character == '{') braces++;
                if (character == '}') braces--;
                if (parentheses < 0 || brackets < 0 || braces < 0) {
                    throw new IllegalArgumentException("Generated Python syntax is invalid: unmatched delimiter.");
                }
            }
        }
        if (parentheses != 0 || brackets != 0 || braces != 0) {
            throw new IllegalArgumentException("Generated Python syntax is invalid: unmatched delimiter.");
        }
    }

    private String boundedError(String error) {
        String singleLine = error.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private String renderAdditionalContext(
            Map<String, LoadedContextFile> loadedFiles,
            AgentLanguage language
    ) throws IOException {
        if (loadedFiles.isEmpty()) {
            return language.noExtraInformation();
        }
        StringBuilder extra = new StringBuilder();
        for (LoadedContextFile file : loadedFiles.values()) {
            ensureNotInterrupted();
            extra.append("文件路径：").append(file.filename()).append("\n")
                    .append("推荐理由：").append(file.recommendReason()).append("\n")
                    .append("文件完整内容：\n")
                    .append(file.content())
                    .append("\n=======================\n");
        }
        return boundedPromptSection(extra.toString(), MAX_REFERENCE_CONTEXT_CHARS);
    }

    private Set<String> reasoningContextFiles(String context) {
        Set<String> files = new LinkedHashSet<>();
        boolean inReasoningContext = false;
        for (String line : context.split("\\R")) {
            if ("## Java Reasoning Context".equals(line.trim())) {
                inReasoningContext = true;
                continue;
            }
            if (inReasoningContext && line.startsWith("## ")) {
                break;
            }
            if (inReasoningContext && line.startsWith("### File: ")) {
                files.add(ProjectFilePath.normalize(line.substring("### File: ".length()).trim()));
            }
        }
        return files;
    }

    private String removeReasoningFileSections(String context, Set<String> excludedFiles) {
        if (excludedFiles.isEmpty() || context == null || context.isBlank()) {
            return context;
        }
        StringBuilder result = new StringBuilder();
        boolean inReasoningContext = false;
        boolean skipFileSection = false;
        for (String line : context.split("\\R", -1)) {
            String trimmed = line.trim();
            if ("## Java Reasoning Context".equals(trimmed)) {
                inReasoningContext = true;
                skipFileSection = false;
            } else if (inReasoningContext && line.startsWith("## ")) {
                inReasoningContext = false;
                skipFileSection = false;
            } else if (inReasoningContext && line.startsWith("### File: ")) {
                String filename = ProjectFilePath.normalize(line.substring("### File: ".length()).trim());
                skipFileSection = excludedFiles.contains(filename);
            }
            if (!skipFileSection) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private String buildLiveSourceVerification(
            AgentRunContext context,
            Set<String> existingFiles
    ) {
        Set<String> featureSymbols = markedValues(context.relatedCodes(), FEATURE_SYMBOL_MARKER);
        if (featureSymbols.isEmpty()) {
            return "";
        }

        Path sourceRoot = Path.of(context.sourceRoot()).toAbsolutePath().normalize();
        Path projectRoot = Path.of(context.projectRoot()).toAbsolutePath().normalize();
        StringBuilder verification = new StringBuilder(
                "## 当前源码校验\n\n"
                        + "本节来自当前源码树，其内容优先于可能已经过时的图数据或 CodeMap 文本。\n"
        );
        for (String symbol : featureSymbols.stream().sorted().toList()) {
            Boolean declared = javaDeclarationExists(sourceRoot, symbol);
            if (declared != null) {
                verification.append("FEATURE_SYMBOL_STATUS: ")
                        .append(declared ? "PRESENT " : "MISSING ")
                        .append(symbol)
                        .append('\n');
            }
        }

        Map<String, List<String>> references = new LinkedHashMap<>();
        List<Pattern> callablePatterns = featureSymbols.stream()
                .map(this::callableName)
                .filter(name -> !name.isBlank())
                .distinct()
                .map(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\("))
                .toList();
        for (String relativePath : existingFiles.stream().sorted().toList()) {
            if (references.size() >= MAX_LIVE_REFERENCE_FILES) {
                break;
            }
            if (!relativePath.endsWith(".java") && !relativePath.endsWith(".py")) {
                continue;
            }
            Path file = ProjectFilePath.resolve(projectRoot, relativePath);
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_LIVE_REFERENCE_FILE_BYTES) {
                    continue;
                }
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\\R", -1);
                List<String> matches = new ArrayList<>();
                for (int index = 0; index < lines.length && matches.size() < 4; index++) {
                    String line = lines[index];
                    if (callablePatterns.stream().anyMatch(pattern -> pattern.matcher(line).find())) {
                        matches.add("第 " + (index + 1) + " 行：" + line.strip());
                    }
                }
                if (!matches.isEmpty()) {
                    references.put(relativePath, matches);
                }
            } catch (IOException | RuntimeException ignored) {
                // One unreadable source file must not block the deletion safety scan.
            }
        }

        verification.append("\n包含当前声明或引用的文件：\n");
        if (references.isEmpty()) {
            verification.append("无\n");
        } else {
            references.forEach((file, matches) -> {
                verification.append("LIVE_REFERENCE_FILE: ").append(file).append('\n');
                matches.forEach(match -> verification.append("  ").append(match).append('\n'));
            });
        }
        return verification.toString();
    }

    private Boolean javaDeclarationExists(Path sourceRoot, String signature) {
        int parameters = signature.indexOf('(');
        String callable = callableName(signature);
        if (parameters < 0 || callable.isBlank()) {
            return null;
        }
        String ownerAndCallable = signature.substring(0, parameters).trim();
        int separator = ownerAndCallable.lastIndexOf('.');
        if (separator <= 0) {
            return null;
        }
        String ownerClass = ownerAndCallable.substring(0, separator);
        try {
            Path sourceFile = JavaFilePath.resolve(sourceRoot, JavaFilePath.fromClassName(ownerClass));
            if (!Files.isRegularFile(sourceFile)) {
                return false;
            }
            return StaticJavaParser.parse(sourceFile).findAll(MethodDeclaration.class).stream()
                    .anyMatch(method -> method.getNameAsString().equals(callable));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String renderGlobalPlan(List<ModifiedFile> files) {
        StringBuilder plan = new StringBuilder();
        for (ModifiedFile file : files) {
            plan.append("文件：").append(file.filename).append("\n")
                    .append("操作：").append(file.action).append("\n")
                    .append("方案：").append(file.plan).append("\n")
                    .append("备注：").append(file.note).append("\n\n");
        }
        return plan.toString();
    }

    private String buildAgent1Prompt(
            AgentRunContext context,
            String projectLanguage,
            AgentOperation operation,
            String liveSourceVerification,
            String workingCoreContext,
            String additionalContext,
            Set<String> removableContextFiles,
            int round
    ) {
        String originalSection = operation.isAddition() ? "" : """
                原功能描述：
                \"\"\"
                %s
                \"\"\"

                """.formatted(context.oldRequest());
        return """
                你是负责 %s 项目的 Agent1。请为本次%s筛选并完善实现所需的工作代码上下文。
                此阶段只调整上下文；从上下文中移除文件绝不会删除项目中的实际文件。

                当前是第 %d/%d 轮上下文调整。

                待实现需求：
                \"\"\"
                %s
                \"\"\"

                %s
                检索得到的图数据与 CodeMap 上下文：
                \"\"\"
                %s
                \"\"\"

                当前补充的文件完整内容上下文：
                \"\"\"
                %s
                \"\"\"

                当前源码校验（如有）：
                \"\"\"
                %s
                \"\"\"

                以下是原项目工作区的完整文件列表，包括源代码、配置、元数据、模板、脚本、测试及其他
                项目文件，不按扩展名过滤。每个文件名都相对于项目根目录；返回的文件名必须严格沿用
                此格式：
                \"\"\"
                %s
                \"\"\"

                后续提示中允许移除的可选上下文文件：
                \"\"\"
                %s
                \"\"\"

                只有仍需查看某个文件的完整内容时才将其加入上下文。只有确认某个文件当前的可选推理内容
                或完整内容与任务无关时才将其移出上下文。未出现在可移除列表中的内容是强制上下文，必须
                保留，包括 Feature CodeMap 声明、确定性删除差异和安全标记。不要再次请求已存在于补充
                文件完整内容上下文中的文件。

                仅返回 JSON，不要输出推理过程或 Markdown 代码围栏：
                {
                  "needAdditionalFile": true,
                  "additionalFileList": [
                    {"filename": "path/to/project.file", "recommendReason": "需要该文件的原因"}
                  ],
                  "removeContextFileList": [
                    {"filename": "path/to/optional-context.file", "recommendReason": "该文件无关的原因"}
                  ]
                }
                recommendReason 字段使用%s书写。
                needAdditionalFile 只描述 additionalFileList 是否非空。如果当前上下文已经足够，请返回 false
                和两个空数组。当两个数组都为空时，上下文调整将提前结束；否则最多执行 %d 轮。
                """.formatted(
                projectLanguage,
                operation.promptLabel(),
                round,
                MAX_AGENT1_CONTEXT_ROUNDS,
                context.newRequest(),
                originalSection,
                boundedPromptSection(workingCoreContext, MAX_CORE_CONTEXT_CHARS),
                additionalContext,
                boundedPromptSection(liveSourceVerification, MAX_REFERENCE_CONTEXT_CHARS),
                context.allFiles(),
                removableContextFiles.isEmpty()
                        ? context.language().noExtraInformation()
                        : String.join("\n", removableContextFiles),
                context.language().promptLanguageName(),
                MAX_AGENT1_CONTEXT_ROUNDS
        );
    }

    private String buildAgent2Prompt(
            AgentRunContext context,
            String workingCoreContext,
            String extraInfo,
            String projectLanguage,
            AgentOperation operation,
            String liveSourceVerification
    ) {
        String wholeFileDeleteRule = "Python".equalsIgnoreCase(projectLanguage)
                ? "只有上下文通过 ALLOWED_DELETE_FILE 明确列出某个路径，且该文件仅服务于此功能时，"
                        + "才能使用 action=delete 删除整个文件。"
                : "对于 Java，如果某个现有文件仅服务于此功能且应被完整移除，可以使用 action=delete。"
                        + "FeatX 仍会校验项目路径和受保护符号的安全边界。";
        String deleteConstraints = operation.isDeletion() ? """

                本次任务是功能删除。必须保留无关行为以及上下文中列出的每个受保护共享符号，优先采用最小
                范围的改写。%s
                删除功能时不得创建文件。计划中的每个文件都必须同时存在于给出的项目文件列表和当前源码树中。
                不要加入假设存在或可选的映射文件。当前源码校验中标记为 MISSING 的符号已经不存在，无需再次
                修改。当 LIVE_REFERENCE_FILE 中的现有调用点会因删除而失效时，必须将其纳入计划。
                """.formatted(wholeFileDeleteRule) : "";
        return """
                你是负责 %s 项目的 Agent2。请为本次%s制定完整且内部一致的文件级修改方案。

                待实现需求：
                \"\"\"
                %s
                \"\"\"

                原功能描述（如有）：
                \"\"\"
                %s
                \"\"\"

                检索得到的图数据与 CodeMap 上下文：
                \"\"\"
                %s
                \"\"\"

                补充文件上下文：
                \"\"\"
                %s
                \"\"\"

                当前源码校验（如有）：
                \"\"\"
                %s
                \"\"\"

                每个 filename 都必须相对于原项目工作区根目录。无论扩展名是什么，都要列出每个需要编辑、
                新建或删除的项目文件。请明确写出共享 API 名称、签名、数据类型、配置键和调用点，使各文件的
                独立修改保持一致。

                仅返回 JSON，不要输出推理过程或 Markdown 代码围栏：
                {
                  "modifiedFileList": [
                    {
                      "filename": "path/to/project.file",
                      "action": "rewrite",
                      "plan": "具体步骤，包括精确的共享签名。",
                      "note": "约束与兼容性风险。"
                    }
                  ]
                }
                如果核对后确认项目已经满足需求，或该需求不需要修改任何项目文件，可以返回空的
                modifiedFileList。否则必须给出完整、具体的计划，不得仅因需求较小或非常规而漏掉文件。
                %s
                plan 和 note 字段使用%s书写。
                """.formatted(
                projectLanguage,
                operation.promptLabel(),
                context.newRequest(),
                context.oldRequest(),
                boundedPromptSection(workingCoreContext, MAX_CORE_CONTEXT_CHARS),
                extraInfo,
                boundedPromptSection(liveSourceVerification, MAX_REFERENCE_CONTEXT_CHARS),
                deleteConstraints,
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
            String projectLanguage,
            AgentOperation operation
    ) {
        boolean javaFile = target.filename.endsWith(".java");
        String expectedPackage = javaFile ? expectedJavaPackage(context, target.filename).orElse("") : "";
        String packageDeclaration = expectedPackage.isEmpty() ? "" : "package " + expectedPackage + ";\n\n";
        String expectedTypeName = javaFile ? javaTypeName(target.filename) : "";
        String outputContract;
        if (createMode && javaFile) {
            outputContract = """
                    当前目标是新文件或空文件，因此只能返回一个 CREATE 块：
                    <<<<<<< CREATE
                    %s
                    >>>>>>> CREATE

                    CREATE 块内必须从第一行开始写真实文件内容，不要写文件名、路径、说明文字或 Markdown 代码围栏。
                    只有 CREATE 场景允许生成完整文件。
                    """.formatted(packageDeclaration + "public class " + expectedTypeName + " {\n}");
        } else if (createMode) {
            outputContract = """
                    当前目标是新文件或空文件，因此只能返回一个 CREATE 块。
                    CREATE 块内必须直接从第一行开始写目标文件的真实完整内容；不要写文件名、路径、说明文字或 Markdown 代码围栏：
                    <<<<<<< CREATE
                    >>>>>>> CREATE

                    只有 CREATE 场景允许生成完整文件。
                    """;
        } else {
            outputContract = """
                如果相关代码本来就不存在，或目标文件已经达到计划要求，请只返回以下标记，不要输出其他内容：
                NO_CHANGES_REQUIRED

                否则只能按应用顺序返回最小且精确的 Search/Replace 块：
                <<<<<<< SEARCH
                从当前目标文件中逐字复制的精确文本
                =======
                替换文本
                >>>>>>> REPLACE

                规则：
                - 绝不返回现有多行文件的完整内容。对于单行文件，替换这一行就是最小有效修改。
                - SEARCH 不得为空，并且在该步骤的当前文件中必须恰好匹配一次。
                - 每个 REPLACE 都必须与对应的 SEARCH 不同，并实现需求的一部分。
                - 每个 SEARCH 都要包含足够的未修改上下文，确保匹配唯一。
                - 其他修改（包括 package 或 import 变更）使用额外的块。
                - 不要使用省略号、行号、正则表达式、解释文字或省略代码的占位符。
                """;
        }
        String deleteConstraints = operation.isDeletion() ? """

                删除安全规则：
                - 保留参考上下文中的每个 PROTECTED_SYMBOL。
                - 只移除属于所选功能的实现，或因该功能删除而失效的引用。
                - 保留无关的公共 API、共享配置和共享行为。
                - 当前目标文件已经存在，不要使用 CREATE。
                """ : "";
        return """
                你是负责 %s 项目的 Agent3。请在本次多文件协同%s中，为一个项目文件生成精确的
                Search/Replace 修改。

                待实现需求：
                \"\"\"
                %s
                \"\"\"

                原功能描述（如有）：
                \"\"\"
                %s
                \"\"\"

                全局文件方案。精确的共享签名属于必须遵守的契约：
                \"\"\"
                %s
                \"\"\"

                参考代码与依赖上下文：
                \"\"\"
                %s
                \"\"\"

                本轮已经生成的文件：
                \"\"\"
                %s
                \"\"\"

                目标文件：%s
                目标方案：%s
                目标约束：%s

                目标文件的原始完整内容：
                \"\"\"
                %s
                \"\"\"

                %s

                %s

                修改后的文件必须符合其文件类型的语法。FeatX 只在内存中应用这些块；它会检查生成的 Java
                和 Python 文件语法，但不会编译或运行项目。不要用 Markdown 代码围栏包裹此协议。
                新增或修改的自然语言注释使用%s书写。
                """.formatted(
                projectLanguage,
                operation.promptLabel(),
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
                deleteConstraints,
                context.language().promptLanguageName()
        );
    }

    private Set<String> markedValues(String context, String marker) {
        if (context == null || context.isBlank()) {
            return Set.of();
        }
        Set<String> values = new java.util.LinkedHashSet<>();
        for (String line : context.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(marker)) {
                String value = trimmed.substring(marker.length()).trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private void validateProtectedSymbols(
            String filename,
            String originalContent,
            String generatedContent,
            Set<String> protectedSymbols
    ) {
        for (String protectedSymbol : protectedSymbols) {
            String callableName = callableName(protectedSymbol);
            if (callableName.isBlank()) {
                continue;
            }
            if (declaresCallable(filename, originalContent, callableName)
                    && !declaresCallable(filename, generatedContent, callableName)) {
                throw new IllegalArgumentException(
                        "Delete Agent attempted to remove protected shared symbol " + protectedSymbol
                                + " from " + filename + "."
                );
            }
        }
    }

    private boolean declaresCallable(String filename, String content, String callableName) {
        if (filename.endsWith(".java")) {
            try {
                return StaticJavaParser.parse(content).findAll(MethodDeclaration.class).stream()
                        .anyMatch(method -> method.getNameAsString().equals(callableName));
            } catch (RuntimeException invalidSource) {
                return false;
            }
        }
        if (filename.endsWith(".py")) {
            Pattern definition = Pattern.compile(
                    "(?m)^\\s*(?:async\\s+)?def\\s+" + Pattern.quote(callableName) + "\\s*\\("
            );
            return definition.matcher(content).find();
        }
        Pattern callable = Pattern.compile("\\b" + Pattern.quote(callableName) + "\\s*\\(");
        return callable.matcher(content).find();
    }

    private String callableName(String signature) {
        String value = signature == null ? "" : signature.trim();
        int parameters = value.indexOf('(');
        if (parameters >= 0) {
            value = value.substring(0, parameters);
        }
        int separator = Math.max(
                Math.max(value.lastIndexOf('.'), value.lastIndexOf('#')),
                Math.max(value.lastIndexOf(':'), value.lastIndexOf(' '))
        );
        return separator >= 0 ? value.substring(separator + 1).trim() : value;
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

    private JsonNode readAgent2Json(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("Agent returned an empty response.");
        }
        String trimmed = response.trim();
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        boolean arrayFirst = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart);
        if (!arrayFirst) {
            return readJsonObject(response);
        }
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayEnd <= arrayStart) {
            throw new IllegalArgumentException("Agent response does not contain a complete JSON array.");
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed.substring(arrayStart, arrayEnd + 1));
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("Agent2 response must be a JSON object or array.");
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
        return repairPrompt(originalPrompt, agent, null);
    }

    private String repairPrompt(String originalPrompt, String agent, Exception failure) {
        return originalPrompt + "\n\n你上一次返回的 " + agent + " 响应违反了指定协议。"
                + (failure == null ? "" : "具体原因如下：\n" + safeFailureMessage(failure))
                + "\n请重新生成，并严格遵循输出结构。";
    }

    private String agent2RepairPrompt(String originalPrompt, Exception failure) {
        return repairPrompt(originalPrompt, "Agent2", failure)
                + "\n只能返回一个 JSON 对象，且顶层只能包含 modifiedFileList 字段。"
                + "filename、plan 和 note 字符串值中的每个双引号都必须使用反斜杠转义。"
                + "不要返回裸数组、说明文字、注释或 Markdown 代码围栏。";
    }

    private String buildAgent3RetryPrompt(
            String originalPrompt,
            int retryNumber,
            Exception failure,
            String previousResponse
    ) {
        String safetyStopOption = isSafetyBoundaryFailure(failure)
                ? "\n本文件的修改越过了安全边界。请先尝试缩小补丁范围并保留受保护代码。"
                        + "如果无法安全修改此文件，只返回 " + SKIP_FILE_SAFELY
                        + "，不要输出其他内容。FeatX 将仅保留此文件不变，并继续处理下一个计划文件。"
                        + "发生安全冲突时不要使用 " + NO_CHANGES_REQUIRED + "。\n"
                : "";
        return originalPrompt + "\n\n当前是第 " + retryNumber + "/" + MAX_AGENT_RETRIES + " 次重试。\n"
                + "上一次尝试失败的具体原因：\n"
                + safeFailureMessage(failure) + "\n\n"
                + "上一次响应：\n"
                + boundedPromptSection(previousResponse, MAX_RETRY_RESPONSE_CHARS) + "\n\n"
                + safetyStopOption
                + "请基于目标文件的原始内容重新生成一份协议响应，不要在上一次响应上继续修改。每个 "
                + "REPLACE 都必须与 SEARCH 不同，修改后的文件也必须与原文件不同。如果请求的目标状态"
                + "已经存在，只返回 " + NO_CHANGES_REQUIRED + "。请修正报告的问题并严格遵循协议。";
    }

    private String agent3RetryMessage(
            AgentLanguage language,
            String filename,
            int retryNumber,
            Exception failure
    ) {
        return language == AgentLanguage.CN
                ? "\n> `" + filename + "` 的补丁未能安全应用，正在重新生成（"
                        + retryNumber + "/" + MAX_AGENT_RETRIES + "）。\n"
                : "\n> The patch for `" + filename + "` could not be applied safely; regenerating ("
                        + retryNumber + "/" + MAX_AGENT_RETRIES + ").\n";
    }

    private String retryMessage(AgentLanguage language, String agent, int retryNumber) {
        return language == AgentLanguage.CN
                ? "\n> " + agent + " 执行失败，正在进行第 " + retryNumber + "/"
                        + MAX_AGENT_RETRIES + " 次重试。\n"
                : "\n> " + agent + " failed; retrying (" + retryNumber + "/"
                        + MAX_AGENT_RETRIES + ").\n";
    }

    private String userFacingFailureMessage(AgentLanguage language, Exception exception) {
        String detail = safeFailureMessage(exception);
        boolean chinese = language == AgentLanguage.CN;
        if (detail.contains("file that does not exist") || detail.contains("does not exist in the current project")) {
            String filename = detail.substring(detail.lastIndexOf(':') + 1).trim();
            return chinese
                    ? "删除方案引用了当前项目中不存在的文件 " + filename
                            + "。本次操作已停止，仓库和数据库均未修改。"
                    : "The deletion plan referenced a file that is not in the current project: " + filename
                            + ". The operation stopped without changing the repository or database.";
        }
        if (detail.contains("protected shared symbol")) {
            return chinese
                    ? "删除方案试图移除其他功能仍在使用的共享代码。本次操作已停止，仓库和数据库均未修改。"
                    : "The deletion plan attempted to remove a protected shared symbol. The operation stopped "
                            + "without changing the repository or database.";
        }
        if (detail.contains("deterministic deletion boundary")) {
            return chinese
                    ? "Python 删除方案试图删除不在确定性 AST 边界内的整个文件。"
                            + "本次操作已停止，仓库和数据库均未修改。"
                    : "The Python deletion plan attempted to delete a whole file outside the deterministic AST "
                            + "boundary. The operation stopped without changing the repository or database.";
        }
        if (detail.contains("Agent2") || detail.contains("malformed JSON")) {
            return chinese
                    ? "阶段 II 的文件修改计划格式无法解析，自动修复后仍未通过校验。"
                            + "本次操作已停止，仓库和数据库均未修改。"
                    : "The Stage II file plan could not be parsed after automatic repair. The operation stopped "
                            + "without changing the repository or database.";
        }
        if (detail.contains("Agent3 failed for")) {
            return chinese
                    ? "无法为其中一个文件生成可安全应用的修改。本次操作已停止，仓库和数据库均未修改。"
                    : "A safe file change could not be generated. The operation stopped without changing the "
                            + "repository or database.";
        }
        return chinese
                ? "无法完成代码生成。本次操作已停止，仓库和数据库均未修改。"
                : "Code generation could not be completed. The operation stopped without changing the repository "
                        + "or database.";
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

    private static int contextLimit(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
