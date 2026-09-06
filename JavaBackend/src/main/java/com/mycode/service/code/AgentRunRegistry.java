package com.mycode.service.code;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.AgentRunSnapshotResult;
import com.mycode.dto.result.AgentTokenUsageResult;
import com.mycode.dto.result.FocusGraphContextResult;
import com.mycode.service.llm.LlmTokenUsage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stores Agent runs by id while enforcing one active modification per project.
 * Different repository ids have independent lifecycle slots and can run in
 * parallel inside the same backend process.
 */
@Service
public class AgentRunRegistry {
    private static final long SSE_TIMEOUT_MILLIS = 0L;
    private static final int REPLAY_CHUNK_CHARACTERS = 16_384;

    public enum Status {
        PREPARING,
        PREPARED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final Map<String, RunState> runsById = new LinkedHashMap<>();
    private final Map<Integer, String> activeRunByRepository = new LinkedHashMap<>();

    private record AgentEvent(String name, String content) {
    }

    private static final class RunState {
        private final String runId;
        private final Integer repositoryId;
        private final String projectRoot;
        private final String workspaceId;
        private AgentRunContext context;
        private Status status = Status.PREPARING;
        private Map<String, String> modifications = Map.of();
        private String failureMessage;
        private String model;
        private Instant statusChangedAt = Instant.now();
        private Thread preparingThread;
        private Runnable cancellation;
        private int llmCalls;
        private int reportedUsageCalls;
        private long inputTokens;
        private long cachedInputTokens;
        private long outputTokens;
        private long reasoningOutputTokens;
        private long totalTokens;
        private String agentLogPath;
        private boolean metadataOnlyEligible;
        private final List<AgentEvent> events = new ArrayList<>();
        private final Set<SseEmitter> subscribers = new LinkedHashSet<>();

        private RunState(
                String runId,
                Integer repositoryId,
                String projectRoot,
                String workspaceId
        ) {
            this.runId = runId;
            this.repositoryId = repositoryId;
            this.projectRoot = projectRoot;
            this.workspaceId = workspaceId;
            this.preparingThread = Thread.currentThread();
        }
    }

    /** Reserves the current project before any slow graph or LLM preparation. */
    public synchronized String reservePreparation() {
        ProjectState project = requireCurrentProject();
        Integer repositoryId = requireRepositoryId(project);
        ensureRepositoryAvailable(repositoryId);

        String runId = UUID.randomUUID().toString();
        RunState state = new RunState(
                runId,
                repositoryId,
                project.getProjectPath(),
                ProjectState.currentWorkspaceId()
        );
        runsById.put(runId, state);
        activeRunByRepository.put(repositoryId, runId);
        return runId;
    }

    /** Compatibility entry point used by tests and direct service callers. */
    public AgentRunContext prepare(
            String mode,
            String newRequest,
            String oldRequest,
            String relatedCodes,
            String allFiles,
            AgentLanguage language,
            String sourceRoot,
            String projectRoot,
            Integer repositoryId,
            Integer featureId,
            Integer moduleId,
            List<FocusGraphContextResult.GraphStage> graphStages
    ) {
        String runId = reservePreparation();
        try {
            return prepare(
                    runId,
                    mode,
                    newRequest,
                    oldRequest,
                    relatedCodes,
                    allFiles,
                    language,
                    sourceRoot,
                    projectRoot,
                    repositoryId,
                    featureId,
                    moduleId,
                    graphStages
            );
        } catch (RuntimeException exception) {
            fail(runId, exception);
            throw exception;
        }
    }

    public synchronized AgentRunContext prepare(
            String runId,
            String mode,
            String newRequest,
            String oldRequest,
            String relatedCodes,
            String allFiles,
            AgentLanguage language,
            String sourceRoot,
            String projectRoot,
            Integer repositoryId,
            Integer featureId,
            Integer moduleId,
            List<FocusGraphContextResult.GraphStage> graphStages
    ) {
        RunState state = requireRunState(runId);
        if (state.status != Status.PREPARING) {
            throw statusMismatch(state, Status.PREPARING);
        }
        if (!Objects.equals(state.repositoryId, repositoryId) || !samePath(state.projectRoot, projectRoot)) {
            throw new IllegalStateException("The project changed while this Agent run was being prepared.");
        }
        assertCurrentProject(state);

        state.context = new AgentRunContext(
                runId,
                mode,
                newRequest,
                oldRequest,
                relatedCodes,
                allFiles,
                AgentLanguage.orDefault(language),
                sourceRoot,
                projectRoot,
                repositoryId,
                featureId,
                moduleId,
                graphStages
        );
        state.status = Status.PREPARED;
        state.preparingThread = null;
        state.statusChangedAt = Instant.now();
        return state.context;
    }

    /** Completes deterministic operations, such as Delete, without an SSE pipeline. */
    public synchronized void completePrepared(String runId, Map<String, String> modifications) {
        RunState state = requireRunState(runId);
        if (state.status != Status.PREPARED) {
            throw statusMismatch(state, Status.PREPARED);
        }
        boolean metadataOnlyEligible = AgentRunMode.matchesOperation(requireContext(state).mode(), "delete")
                && (modifications == null || modifications.isEmpty());
        publishCompletedState(state, modifications, metadataOnlyEligible);
    }

    public synchronized void selectModel(String runId, String model) {
        RunState state = requireStatus(runId, Status.PREPARED);
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Agent model is required.");
        }
        state.model = model;
    }

    public synchronized String selectedModel(String runId) {
        RunState state = requireRunState(runId);
        if (state.model == null || state.model.isBlank()) {
            throw new IllegalStateException("No model was selected for this Agent run.");
        }
        return state.model;
    }

    public synchronized AgentRunContext claim(String runId) {
        RunState state = requireStatus(runId, Status.PREPARED);
        assertCurrentProjectIfBound(state);
        state.status = Status.RUNNING;
        state.statusChangedAt = Instant.now();
        return requireContext(state);
    }

    public synchronized void registerCancellation(String runId, Runnable cancellation) {
        RunState state = requireRunState(runId);
        if (state.status == Status.RUNNING) {
            state.cancellation = cancellation;
        }
    }

    public synchronized void complete(String runId, Map<String, String> modifications) {
        complete(runId, modifications, false);
    }

    public synchronized void complete(
            String runId,
            Map<String, String> modifications,
            boolean metadataOnlyEligible
    ) {
        RunState state = requireStatus(runId, Status.RUNNING);
        publishCompletedState(state, modifications, metadataOnlyEligible);
    }

    private void publishCompletedState(
            RunState state,
            Map<String, String> modifications,
            boolean metadataOnlyEligible
    ) {
        Map<String, String> published = immutableModifications(modifications);
        state.modifications = published;
        state.metadataOnlyEligible = metadataOnlyEligible && published.isEmpty();
        projectState(state).setModifications(published);
        state.status = Status.COMPLETED;
        state.cancellation = null;
        state.failureMessage = null;
        state.statusChangedAt = Instant.now();
    }

    public synchronized void fail(String runId, Throwable failure) {
        RunState state = runsById.get(runId);
        if (state == null) {
            return;
        }
        state.status = Status.FAILED;
        state.preparingThread = null;
        state.cancellation = null;
        state.modifications = Map.of();
        state.metadataOnlyEligible = false;
        projectState(state).setModifications(Map.of());
        state.failureMessage = failure == null ? "Unknown Agent failure" : failure.getMessage();
        state.statusChangedAt = Instant.now();
    }

    public synchronized AgentRunContext requireCompleted(String runId) {
        RunState state = requireStatus(runId, Status.COMPLETED);
        assertCurrentProjectIfBound(state);
        return requireContext(state);
    }

    public synchronized AgentRunContext requireCompletedOperation(String runId, String operation) {
        AgentRunContext context = requireCompleted(runId);
        boolean matches = AgentRunMode.matchesOperation(context.mode(), operation);
        if (!matches) {
            throw new IllegalStateException(
                    "Agent run " + runId + " does not belong to the requested " + operation + " operation."
            );
        }
        return context;
    }

    public synchronized AgentRunContext requireActiveContext(String runId) {
        RunState state = requireRunState(runId);
        assertCurrentProjectIfBound(state);
        return requireContext(state);
    }

    public synchronized void requireCompletedIfActive(String runId) {
        ProjectState project = requireCurrentProject();
        String activeRunId = activeRunByRepository.get(project.getRepoId());
        if (activeRunId == null) {
            return;
        }
        requireCompleted(runId);
    }

    public synchronized Map<String, String> modifications(String runId) {
        RunState state = requireStatus(runId, Status.COMPLETED);
        return state.modifications;
    }

    public synchronized boolean metadataOnlyEligible(String runId) {
        return requireStatus(runId, Status.COMPLETED).metadataOnlyEligible;
    }

    public synchronized void replaceCompletedModifications(String runId, Map<String, String> modifications) {
        RunState state = requireStatus(runId, Status.COMPLETED);
        Map<String, String> published = immutableModifications(modifications);
        state.modifications = published;
        projectState(state).setModifications(published);
    }

    public synchronized Status status(String runId) {
        return requireRunState(runId).status;
    }

    public synchronized int beginLlmCall(String runId) {
        RunState state = requireRunState(runId);
        if (state.status != Status.PREPARING && state.status != Status.RUNNING) {
            throw new IllegalStateException(
                    "Cannot start an LLM call for Agent run " + runId + " with status " + state.status + "."
            );
        }
        state.llmCalls++;
        return state.llmCalls;
    }

    public synchronized void completeLlmCall(String runId, LlmTokenUsage usage) {
        RunState state = requireRunState(runId);
        if (state.status != Status.PREPARING
                && state.status != Status.RUNNING
                && state.status != Status.COMPLETED) {
            throw new IllegalStateException(
                    "Cannot record LLM usage for Agent run " + runId + " with status " + state.status + "."
            );
        }
        if (usage == null) {
            return;
        }
        state.reportedUsageCalls++;
        state.inputTokens += usage.inputTokens();
        state.cachedInputTokens += usage.cachedInputTokens();
        state.outputTokens += usage.outputTokens();
        state.reasoningOutputTokens += usage.reasoningOutputTokens();
        state.totalTokens += usage.totalTokens();
    }

    public synchronized AgentTokenUsageResult tokenUsage(String runId) {
        return tokenUsage(requireRunState(runId));
    }

    public synchronized void setAgentLogPath(String runId, String path) {
        requireRunState(runId).agentLogPath = path;
    }

    public synchronized AgentRunSnapshotResult snapshot(String runId) {
        RunState state = requireRunState(runId);
        AgentRunContext context = requireContext(state);
        assertCurrentProjectIfBound(state);
        return new AgentRunSnapshotResult(
                context.runId(),
                state.status,
                context.mode(),
                context.newRequest(),
                context.language(),
                context.featureId(),
                context.moduleId(),
                state.model,
                state.failureMessage,
                tokenUsage(state),
                state.agentLogPath,
                state.metadataOnlyEligible
        );
    }

    public synchronized AgentRunEventStream eventStream(String runId) {
        requireActiveContext(runId);
        return new AgentRunEventStream(this, runId);
    }

    public synchronized SseEmitter subscribe(String runId) {
        RunState state = requireRunState(runId);
        requireContext(state);
        assertCurrentProjectIfBound(state);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onCompletion(() -> detach(runId, emitter));
        emitter.onTimeout(() -> detach(runId, emitter));
        emitter.onError(ignored -> detach(runId, emitter));

        try {
            sendToSubscriber(emitter, "reset", "");
            for (AgentEvent event : state.events) {
                sendReplayedEvent(emitter, event);
            }
            if (isTerminal(state.status)) {
                if (!hasTerminalEvent(state)) {
                    String eventName = state.status == Status.COMPLETED ? "completed" : "failed";
                    String content = state.status == Status.COMPLETED
                            ? "\n> Pipeline complete.\n"
                            : state.failureMessage == null ? "Agent generation failed." : state.failureMessage;
                    sendToSubscriber(emitter, eventName, content);
                }
                emitter.complete();
            } else {
                state.subscribers.add(emitter);
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    synchronized void publish(String runId, String eventName, String content) {
        RunState state = requireRunState(runId);
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("Agent event name is required.");
        }
        String safeContent = content == null ? "" : content;
        appendEvent(state, eventName, safeContent);

        Iterator<SseEmitter> subscribers = state.subscribers.iterator();
        while (subscribers.hasNext()) {
            SseEmitter subscriber = subscribers.next();
            try {
                sendToSubscriber(subscriber, eventName, safeContent);
            } catch (IOException | IllegalStateException disconnected) {
                subscribers.remove();
            }
        }
        if ("completed".equals(eventName) || "failed".equals(eventName)) {
            completeSubscribers(state);
        }
    }

    public synchronized String failureMessage(String runId) {
        return requireRunState(runId).failureMessage;
    }

    /** Clears only the project bound to the current request. */
    public synchronized void clear() {
        ProjectState project = requireCurrentProject();
        clearRepository(project.getRepoId());
    }

    public synchronized void clearRepository(Integer repositoryId) {
        String runId = activeRunByRepository.remove(repositoryId);
        if (runId == null) {
            ProjectState.projectForRepository(repositoryId).ifPresent(state -> state.setModifications(Map.of()));
            return;
        }
        RunState state = runsById.remove(runId);
        if (state == null) {
            return;
        }
        Runnable cancellation = state.status == Status.RUNNING ? state.cancellation : null;
        Thread preparingThread = state.status == Status.PREPARING ? state.preparingThread : null;
        projectState(state).setModifications(Map.of());
        completeSubscribers(state);
        if (preparingThread != null && preparingThread != Thread.currentThread()) {
            preparingThread.interrupt();
        }
        if (cancellation != null) {
            cancellation.run();
        }
    }

    public synchronized boolean hasActiveOperation(Integer repositoryId) {
        String runId = activeRunByRepository.get(repositoryId);
        if (runId == null) {
            return false;
        }
        RunState state = runsById.get(runId);
        return state != null && state.status != Status.FAILED;
    }

    private void ensureRepositoryAvailable(Integer repositoryId) {
        String activeRunId = activeRunByRepository.get(repositoryId);
        if (activeRunId == null) {
            return;
        }
        RunState state = runsById.get(activeRunId);
        if (state == null) {
            activeRunByRepository.remove(repositoryId);
            return;
        }
        if (state.status == Status.FAILED) {
            clearRepository(repositoryId);
            return;
        }
        if (state.status == Status.PREPARED
                && Duration.between(state.statusChangedAt, Instant.now()).toMinutes() >= 5) {
            clearRepository(repositoryId);
            return;
        }
        throw new IllegalStateException(
                "Another Agent operation is still active for this project. Commit or discard it before starting a new operation."
        );
    }

    private AgentTokenUsageResult tokenUsage(RunState state) {
        return new AgentTokenUsageResult(
                state.llmCalls,
                state.reportedUsageCalls,
                state.inputTokens,
                state.cachedInputTokens,
                Math.max(0, state.inputTokens - state.cachedInputTokens),
                state.outputTokens,
                state.reasoningOutputTokens,
                state.totalTokens
        );
    }

    private void appendEvent(RunState state, String eventName, String content) {
        if ("delta".equals(eventName) && !state.events.isEmpty()) {
            int lastIndex = state.events.size() - 1;
            AgentEvent previous = state.events.get(lastIndex);
            if ("delta".equals(previous.name())) {
                state.events.set(lastIndex, new AgentEvent("delta", previous.content() + content));
                return;
            }
        }
        state.events.add(new AgentEvent(eventName, content));
    }

    private void sendToSubscriber(SseEmitter emitter, String eventName, String content) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        emitter.send(SseEmitter.event().name(eventName).data(encoded));
    }

    private void sendReplayedEvent(SseEmitter emitter, AgentEvent event) throws IOException {
        if (!"delta".equals(event.name()) || event.content().length() <= REPLAY_CHUNK_CHARACTERS) {
            sendToSubscriber(emitter, event.name(), event.content());
            return;
        }
        int start = 0;
        while (start < event.content().length()) {
            int end = Math.min(event.content().length(), start + REPLAY_CHUNK_CHARACTERS);
            if (end < event.content().length() && Character.isHighSurrogate(event.content().charAt(end - 1))) {
                end--;
            }
            sendToSubscriber(emitter, event.name(), event.content().substring(start, end));
            start = end;
        }
    }

    private boolean hasTerminalEvent(RunState state) {
        if (state.events.isEmpty()) {
            return false;
        }
        String eventName = state.events.get(state.events.size() - 1).name();
        return "completed".equals(eventName) || "failed".equals(eventName);
    }

    private boolean isTerminal(Status status) {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    private synchronized void detach(String runId, SseEmitter emitter) {
        RunState state = runsById.get(runId);
        if (state != null) {
            state.subscribers.remove(emitter);
        }
    }

    private void completeSubscribers(RunState state) {
        for (SseEmitter subscriber : state.subscribers) {
            try {
                subscriber.complete();
            } catch (IllegalStateException ignored) {
                // The servlet container already closed this subscriber.
            }
        }
        state.subscribers.clear();
    }

    private RunState requireStatus(String runId, Status expected) {
        RunState state = requireRunState(runId);
        if (state.status != expected) {
            throw statusMismatch(state, expected);
        }
        return state;
    }

    private IllegalStateException statusMismatch(RunState state, Status expected) {
        return new IllegalStateException(
                "Agent run " + state.runId + " has status " + state.status + ", expected " + expected + "."
        );
    }

    private RunState requireRunState(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required.");
        }
        RunState state = runsById.get(runId);
        if (state == null) {
            throw new IllegalStateException("Agent run is missing, stale, or belongs to another operation.");
        }
        return state;
    }

    private AgentRunContext requireContext(RunState state) {
        if (state.context == null) {
            throw new IllegalStateException("Agent run is still preparing its project context.");
        }
        return state.context;
    }

    private ProjectState requireCurrentProject() {
        ProjectState project = ProjectState.getInstance();
        requireRepositoryId(project);
        if (project.getProjectPath() == null || project.getProjectPath().isBlank()) {
            throw new IllegalStateException("No project is currently selected.");
        }
        return project;
    }

    private Integer requireRepositoryId(ProjectState project) {
        if (project.getRepoId() == null) {
            throw new IllegalStateException("No project is currently selected.");
        }
        return project.getRepoId();
    }

    private void assertCurrentProject(RunState state) {
        ProjectState project = requireCurrentProject();
        if (!Objects.equals(project.getRepoId(), state.repositoryId)
                || !samePath(project.getProjectPath(), state.projectRoot)) {
            throw new IllegalStateException("The selected project changed after this Agent run was reserved.");
        }
    }

    private void assertCurrentProjectIfBound(RunState state) {
        ProjectState.currentProject().ifPresent(project -> {
            if (!Objects.equals(project.getRepoId(), state.repositoryId)
                    || !samePath(project.getProjectPath(), state.projectRoot)) {
                throw new IllegalStateException("This Agent run belongs to another project.");
            }
        });
    }

    private ProjectState projectState(RunState state) {
        return ProjectState.projectForRepository(state.repositoryId)
                .orElseGet(() -> ProjectState.currentProject()
                        .filter(project -> Objects.equals(project.getRepoId(), state.repositoryId))
                        .orElseThrow(() -> new IllegalStateException(
                                "Project runtime is missing for Agent run " + state.runId + "."
                        )));
    }

    private Map<String, String> immutableModifications(Map<String, String> modifications) {
        return modifications == null || modifications.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(modifications));
    }

    private boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return Objects.equals(first, second);
        }
        return Path.of(first).toAbsolutePath().normalize().equals(Path.of(second).toAbsolutePath().normalize());
    }
}
