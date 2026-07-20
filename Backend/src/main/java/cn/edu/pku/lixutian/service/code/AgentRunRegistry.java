package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.AgentRunSnapshotResult;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

/**
 * Owns the lifecycle of the single active operation in the currently selected
 * local workspace. A run id prevents the preparation request and the SSE
 * request from being mixed with another browser request.
 */
@Service
public class AgentRunRegistry {
    private static final long SSE_TIMEOUT_MILLIS = 0L;
    private static final int REPLAY_CHUNK_CHARACTERS = 16_384;

    public enum Status {
        PREPARED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private AgentRunContext activeContext;
    private Status activeStatus;
    private Map<String, String> activeModifications = Map.of();
    private String failureMessage;
    private String activeModel;
    private Instant statusChangedAt;
    private Runnable activeCancellation;
    private final List<AgentEvent> activeEvents = new ArrayList<>();
    private final Set<SseEmitter> activeSubscribers = new LinkedHashSet<>();

    private record AgentEvent(String name, String content) {
    }

    public synchronized void ensureCanPrepare() {
        if (activeContext == null || activeStatus == Status.FAILED) {
            return;
        }
        if (activeStatus == Status.PREPARED
                && statusChangedAt != null
                && Duration.between(statusChangedAt, Instant.now()).toMinutes() >= 5) {
            clear();
            return;
        }
        throw new IllegalStateException(
                "Another Agent operation is still active. Commit or discard it before starting a new operation."
        );
    }

    public synchronized AgentRunContext prepare(
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
        ensureCanPrepare();
        String runId = UUID.randomUUID().toString();
        activeContext = new AgentRunContext(
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
        activeStatus = Status.PREPARED;
        activeModifications = Map.of();
        failureMessage = null;
        activeModel = null;
        statusChangedAt = Instant.now();
        activeEvents.clear();
        completeSubscribers();
        return activeContext;
    }

    public synchronized void selectModel(String runId, String model) {
        requireStatus(runId, Status.PREPARED);
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Agent model is required.");
        }
        activeModel = model;
    }

    public synchronized String selectedModel(String runId) {
        requireActive(runId);
        if (activeModel == null || activeModel.isBlank()) {
            throw new IllegalStateException("No model was selected for this Agent run.");
        }
        return activeModel;
    }

    public synchronized AgentRunContext claim(String runId) {
        AgentRunContext context = requireActive(runId);
        if (activeStatus != Status.PREPARED) {
            throw new IllegalStateException("Agent run " + runId + " is not waiting to be started.");
        }
        assertCurrentProject(context);
        activeStatus = Status.RUNNING;
        statusChangedAt = Instant.now();
        return context;
    }

    public synchronized void registerCancellation(String runId, Runnable cancellation) {
        AgentRunContext context = requireActive(runId);
        if (activeStatus == Status.RUNNING) {
            activeCancellation = cancellation;
        }
    }

    public synchronized void complete(String runId, Map<String, String> modifications) {
        AgentRunContext context = requireStatus(runId, Status.RUNNING);
        assertCurrentProject(context);
        Map<String, String> published = modifications == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(modifications));
        activeModifications = published;
        AgentService.modificationMap = published;
        activeStatus = Status.COMPLETED;
        activeCancellation = null;
        failureMessage = null;
        statusChangedAt = Instant.now();
    }

    public synchronized void fail(String runId, Throwable failure) {
        if (activeContext == null || !activeContext.runId().equals(runId)) {
            return;
        }
        activeStatus = Status.FAILED;
        activeCancellation = null;
        activeModifications = Map.of();
        AgentService.modificationMap = Map.of();
        failureMessage = failure == null ? "Unknown Agent failure" : failure.getMessage();
        statusChangedAt = Instant.now();
    }

    public synchronized AgentRunContext requireCompleted(String runId) {
        AgentRunContext context = requireStatus(runId, Status.COMPLETED);
        assertCurrentProject(context);
        return context;
    }

    public synchronized AgentRunContext requireActiveContext(String runId) {
        AgentRunContext context = requireActive(runId);
        assertCurrentProject(context);
        return context;
    }

    public synchronized void requireCompletedIfActive(String runId) {
        if (activeContext != null) {
            requireCompleted(runId);
        }
    }

    public synchronized Map<String, String> modifications(String runId) {
        requireCompleted(runId);
        return activeModifications;
    }

    public synchronized void replaceCompletedModifications(String runId, Map<String, String> modifications) {
        requireCompleted(runId);
        Map<String, String> published = modifications == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(modifications));
        activeModifications = published;
        AgentService.modificationMap = published;
    }

    public synchronized Status status(String runId) {
        requireActive(runId);
        return activeStatus;
    }

    public synchronized AgentRunSnapshotResult snapshot(String runId) {
        AgentRunContext context = requireActiveContext(runId);
        return new AgentRunSnapshotResult(
                context.runId(),
                activeStatus,
                context.mode(),
                context.newRequest(),
                context.language(),
                context.featureId(),
                context.moduleId(),
                activeModel,
                failureMessage
        );
    }

    public synchronized AgentRunEventStream eventStream(String runId) {
        requireActiveContext(runId);
        return new AgentRunEventStream(this, runId);
    }

    /**
     * Attaches a browser to a run and replays everything produced so far. A
     * reset event lets the client replace an earlier partial replay instead of
     * appending duplicate output after EventSource reconnects.
     */
    public synchronized SseEmitter subscribe(String runId) {
        requireActiveContext(runId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onCompletion(() -> detach(emitter));
        emitter.onTimeout(() -> detach(emitter));
        emitter.onError(ignored -> detach(emitter));

        try {
            sendToSubscriber(emitter, "reset", "");
            for (AgentEvent event : activeEvents) {
                sendReplayedEvent(emitter, event);
            }
            if (isTerminal(activeStatus)) {
                if (!hasTerminalEvent()) {
                    String eventName = activeStatus == Status.COMPLETED ? "completed" : "failed";
                    String content = activeStatus == Status.COMPLETED
                            ? "\n# === Pipeline complete! ===\n"
                            : failureMessage == null ? "Agent generation failed." : failureMessage;
                    sendToSubscriber(emitter, eventName, content);
                }
                emitter.complete();
            } else {
                activeSubscribers.add(emitter);
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    synchronized void publish(String runId, String eventName, String content) {
        requireActiveContext(runId);
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("Agent event name is required.");
        }
        String safeContent = content == null ? "" : content;
        appendEvent(eventName, safeContent);

        Iterator<SseEmitter> subscribers = activeSubscribers.iterator();
        while (subscribers.hasNext()) {
            SseEmitter subscriber = subscribers.next();
            try {
                sendToSubscriber(subscriber, eventName, safeContent);
            } catch (IOException | IllegalStateException disconnected) {
                subscribers.remove();
            }
        }
        if ("completed".equals(eventName) || "failed".equals(eventName)) {
            completeSubscribers();
        }
    }

    public synchronized String failureMessage(String runId) {
        requireActive(runId);
        return failureMessage;
    }

    public synchronized void clear() {
        Runnable cancellation = activeStatus == Status.RUNNING ? activeCancellation : null;
        activeContext = null;
        activeStatus = null;
        activeModifications = Map.of();
        AgentService.modificationMap = Map.of();
        failureMessage = null;
        activeModel = null;
        statusChangedAt = null;
        activeCancellation = null;
        activeEvents.clear();
        completeSubscribers();
        if (cancellation != null) {
            cancellation.run();
        }
    }

    private void appendEvent(String eventName, String content) {
        if ("delta".equals(eventName) && !activeEvents.isEmpty()) {
            int lastIndex = activeEvents.size() - 1;
            AgentEvent previous = activeEvents.get(lastIndex);
            if ("delta".equals(previous.name())) {
                activeEvents.set(lastIndex, new AgentEvent("delta", previous.content() + content));
                return;
            }
        }
        activeEvents.add(new AgentEvent(eventName, content));
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

    private boolean hasTerminalEvent() {
        if (activeEvents.isEmpty()) {
            return false;
        }
        String eventName = activeEvents.get(activeEvents.size() - 1).name();
        return "completed".equals(eventName) || "failed".equals(eventName);
    }

    private boolean isTerminal(Status status) {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    private synchronized void detach(SseEmitter emitter) {
        activeSubscribers.remove(emitter);
    }

    private void completeSubscribers() {
        for (SseEmitter subscriber : activeSubscribers) {
            try {
                subscriber.complete();
            } catch (IllegalStateException ignored) {
                // The servlet container already closed this subscriber.
            }
        }
        activeSubscribers.clear();
    }

    private AgentRunContext requireStatus(String runId, Status expected) {
        AgentRunContext context = requireActive(runId);
        if (activeStatus != expected) {
            throw new IllegalStateException(
                    "Agent run " + runId + " has status " + activeStatus + ", expected " + expected + "."
            );
        }
        return context;
    }

    private AgentRunContext requireActive(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required.");
        }
        if (activeContext == null || !activeContext.runId().equals(runId)) {
            throw new IllegalStateException("Agent run is missing, stale, or belongs to another operation.");
        }
        return activeContext;
    }

    private void assertCurrentProject(AgentRunContext context) {
        ProjectState project = ProjectState.getInstance();
        if (!samePath(project.getProjectPath(), context.projectRoot())
                || !java.util.Objects.equals(project.getRepoId(), context.repositoryId())) {
            throw new IllegalStateException("The selected project changed after this Agent run was prepared.");
        }
    }

    private boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return java.util.Objects.equals(first, second);
        }
        return Path.of(first).toAbsolutePath().normalize().equals(Path.of(second).toAbsolutePath().normalize());
    }
}
