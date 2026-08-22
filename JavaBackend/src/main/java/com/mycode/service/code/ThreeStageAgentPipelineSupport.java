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
    private static final int MAX_ADDITIONAL_FILES = 12;
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
            String projectLanguage,
            AgentOperation operation
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
            if (agent2.modifiedFileList.isEmpty()) {
                throw new IllegalStateException(
                        "Agent2 planned no project file changes, so there is no candidate operation to confirm."
                );
            }

            String globalPlan = renderGlobalPlan(agent2.modifiedFileList);
            String referenceContext = boundedPromptSection(
                    workingCoreContext
                            + "\n\n" + liveSourceVerification
                            + "\n\nAdditional file context:\n" + extraInfo,
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
                    priorGenerations.append("\nFILE DELETED: ").append(file.filename).append("\n");
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
                priorGenerations.append("\nFILE: ").append(file.filename).append("\n")
                        .append(generatedContent).append("\nEND FILE\n");
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
        Set<String> everAddedFiles = new LinkedHashSet<>();
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
                if (everAddedFiles.add(file.filename) && everAddedFiles.size() > MAX_ADDITIONAL_FILES) {
                    throw new IllegalArgumentException("Agent1 requested too many additional files across rounds.");
                }
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
                Agent2ParsedResult parsed = requirePlannedFiles(
                        parseAndValidateAgent2(
                                response,
                                sourceRoot,
                                operation,
                                projectLanguage,
                                allowedDeleteFiles,
                                protectedSymbols
                        )
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

    private Agent2ParsedResult requirePlannedFiles(Agent2ParsedResult result) {
        if (result.modifiedFileList.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent2 planned no project file changes for the submitted requirement."
            );
        }
        return result;
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
        if (filesNode.size() > MAX_ADDITIONAL_FILES) {
            throw new IllegalArgumentException("Agent1 requested too many additional files.");
        }
        if (removeFilesNode != null && !removeFilesNode.isArray()) {
            throw new IllegalArgumentException("Agent1 removeContextFileList must be an array.");
        }
        if (removeFilesNode != null && removeFilesNode.size() > MAX_ADDITIONAL_FILES) {
            throw new IllegalArgumentException("Agent1 requested too many context file removals.");
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
            extra.append("filename: ").append(file.filename()).append("\n")
                    .append("recommendReason: ").append(file.recommendReason()).append("\n")
                    .append("fileContent:\n")
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
                "## Live Source Verification\n\n"
                        + "This section comes from the current source tree and overrides stale graph or CodeMap text.\n"
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
                        matches.add("line " + (index + 1) + ": " + line.strip());
                    }
                }
                if (!matches.isEmpty()) {
                    references.put(relativePath, matches);
                }
            } catch (IOException | RuntimeException ignored) {
                // One unreadable source file must not block the deletion safety scan.
            }
        }

        verification.append("\nFiles containing current declarations or references:\n");
        if (references.isEmpty()) {
            verification.append("None\n");
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
            plan.append("FILE: ").append(file.filename).append("\n")
                    .append("ACTION: ").append(file.action).append("\n")
                    .append("PLAN: ").append(file.plan).append("\n")
                    .append("NOTE: ").append(file.note).append("\n\n");
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
                Original feature description:
                \"\"\"
                %s
                \"\"\"

                """.formatted(context.oldRequest());
        return """
                You are Agent1 for a %s project. Refine the working code context required to implement the requested %s.
                This is context selection only; removing a context file never deletes that project file.

                Context refinement round: %d of %d.

                Requirement:
                \"\"\"
                %s
                \"\"\"

                %s
                Retrieved graph and CodeMap context:
                \"\"\"
                %s
                \"\"\"

                Current additional full-file context:
                \"\"\"
                %s
                \"\"\"

                Live source verification, if available:
                \"\"\"
                %s
                \"\"\"

                Complete project file list from the original project workspace. It includes source code, configuration,
                metadata, templates, scripts, tests, and other project files without filtering by extension.
                Every filename is project-root-relative. Return filenames in exactly this format:
                \"\"\"
                %s
                \"\"\"

                Optional context files that may be removed from subsequent prompts:
                \"\"\"
                %s
                \"\"\"

                Add a file when its complete content is still required. Remove a file only when its current optional
                reasoning or full-file content is irrelevant. Files omitted from the removable list are mandatory and
                must remain, including Feature CodeMap declarations, deterministic deletion diffs, and safety markers.
                Do not request a file already present in the additional full-file context.

                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "needAdditionalFile": true,
                  "additionalFileList": [
                    {"filename": "path/to/project.file", "recommendReason": "why it is needed"}
                  ],
                  "removeContextFileList": [
                    {"filename": "path/to/optional-context.file", "recommendReason": "why it is irrelevant"}
                  ]
                }
                Write recommendReason values in %s.
                needAdditionalFile describes additionalFileList only. If the current context is sufficient, return false
                and two empty arrays. Context refinement stops early when both arrays are empty and otherwise runs for at
                most %d rounds.
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
                ? "Use action=delete for a whole file only when the context explicitly lists that path as "
                        + "ALLOWED_DELETE_FILE and the file is dedicated to this feature."
                : "For Java, use action=delete when an existing file is dedicated to this feature and should be "
                        + "removed completely. FeatX will still enforce project-path and protected-symbol safety.";
        String deleteConstraints = operation.isDeletion() ? """

                This is a feature deletion. Preserve unrelated behavior and every protected shared symbol listed
                in the supplied context. Prefer minimal rewrites. %s
                Do not create files during deletion. Every planned file must exist in the supplied project list and
                current source tree. Never include hypothetical or optional mapping files. A symbol marked MISSING by
                live source verification is already absent and does not need another edit. Include current
                LIVE_REFERENCE_FILE call sites when their references become invalid.
                """.formatted(wholeFileDeleteRule) : "";
        return """
                You are Agent2 for a %s project. Produce a complete, internally consistent file-level plan for this %s.

                Requirement:
                \"\"\"
                %s
                \"\"\"

                Original feature description, if any:
                \"\"\"
                %s
                \"\"\"

                Retrieved graph and CodeMap context:
                \"\"\"
                %s
                \"\"\"

                Additional file context:
                \"\"\"
                %s
                \"\"\"

                Live source verification, if available:
                \"\"\"
                %s
                \"\"\"

                Every filename must be relative to the original project workspace root. Include every edited, newly created,
                or deleted project file regardless of extension. Make shared API names, signatures, data types,
                configuration keys, and call sites explicit so independent edits stay consistent.

                Return JSON only. Do not include reasoning or Markdown fences:
                {
                  "modifiedFileList": [
                    {
                      "filename": "path/to/project.file",
                      "action": "rewrite",
                      "plan": "Concrete steps, including exact shared signatures.",
                      "note": "Constraints and compatibility risks."
                    }
                  ]
                }
                The submitted requirement is authoritative and must produce a concrete change. Do not return an
                empty modifiedFileList merely because the requested behavior is small or unconventional.
                %s
                Write plan and note values in %s.
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
        String createExample = javaFile
                ? packageDeclaration + "public class " + expectedTypeName + " {\n}"
                : "complete content for " + target.filename;
        String outputContract = createMode ? """
                The target is a new or empty file, so return exactly one CREATE block:
                <<<<<<< CREATE
                %s
                >>>>>>> CREATE

                CREATE is the only case where complete-file generation is allowed.
                """.formatted(createExample) : """
                If the target already satisfies its plan because the relevant code is absent or already has the
                required state, return exactly this token and nothing else:
                NO_CHANGES_REQUIRED

                Otherwise return only minimal exact Search/Replace blocks in application order:
                <<<<<<< SEARCH
                exact text copied from the current target file
                =======
                replacement text
                >>>>>>> REPLACE

                Rules:
                - Never return the complete existing multi-line file. For a single-line file, replacing its one
                  line is the minimal valid edit.
                - SEARCH must be non-empty and match exactly once in the current file at that step.
                - Every REPLACE must differ from its SEARCH and must implement part of the requirement.
                - Include enough unchanged context to make every SEARCH unique.
                - Use additional blocks for additional edits, including package or import changes.
                - Do not use ellipses, line numbers, regexes, explanations, or omitted-code placeholders.
                """;
        String deleteConstraints = operation.isDeletion() ? """

                Deletion safety rules:
                - Preserve every PROTECTED_SYMBOL from the reference context.
                - Remove only implementation that belongs to the selected feature or references made obsolete by it.
                - Preserve unrelated public APIs, shared configuration, and shared behavior.
                - This target already exists; do not use CREATE.
                """ : "";
        return """
                You are Agent3 for a %s project. Produce precise Search/Replace edits for one project file in a
                coordinated multi-file %s.

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

                %s

                The resulting file must remain valid for its file type. FeatX applies the blocks only in memory;
                it syntax-checks generated Java and Python files but does not compile or run the project.
                Do not wrap the protocol in Markdown fences.
                Write newly added or modified natural-language comments in %s.
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
        return originalPrompt + "\n\nYour previous " + agent
                + " response violated the required contract."
                + (failure == null ? "" : " The exact reason was:\n" + safeFailureMessage(failure))
                + "\nTry once more and follow the output schema exactly.";
    }

    private String agent2RepairPrompt(String originalPrompt, Exception failure) {
        return repairPrompt(originalPrompt, "Agent2", failure)
                + "\nReturn exactly one JSON object whose only top-level field is modifiedFileList. "
                + "Escape every double quote inside filename, plan, and note string values with a backslash. "
                + "Do not return a bare array, prose, comments, or Markdown fences.";
    }

    private String buildAgent3RetryPrompt(
            String originalPrompt,
            int retryNumber,
            Exception failure,
            String previousResponse
    ) {
        String safetyStopOption = isSafetyBoundaryFailure(failure)
                ? "\nThis file crossed a safety boundary. First try a narrower patch that preserves the protected "
                        + "code. If this file cannot be changed safely, return exactly " + SKIP_FILE_SAFELY
                        + " and nothing else. FeatX will leave only this file unchanged and continue with the next "
                        + "planned file. Do not use " + NO_CHANGES_REQUIRED + " for a safety conflict.\n"
                : "";
        return originalPrompt + "\n\nRetry " + retryNumber + " of " + MAX_AGENT_RETRIES + ".\n"
                + "The previous attempt failed for this exact reason:\n"
                + safeFailureMessage(failure) + "\n\n"
                + "Previous response:\n"
                + boundedPromptSection(previousResponse, MAX_RETRY_RESPONSE_CHARS) + "\n\n"
                + safetyStopOption
                + "Generate a fresh protocol response against the ORIGINAL target content. Do not apply edits "
                + "to the previous response. Every REPLACE must differ from SEARCH, and the resulting file must "
                + "differ from the original. If the requested target state is already present, return exactly "
                + NO_CHANGES_REQUIRED + ". Correct the reported failure and follow the protocol exactly.";
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
