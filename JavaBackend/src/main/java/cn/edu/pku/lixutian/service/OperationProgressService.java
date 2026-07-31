package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OperationProgressService {
    private final ConcurrentHashMap<Integer, ProgressSnapshot> snapshots = new ConcurrentHashMap<>();

    public void start(String operation, int totalSteps, String stage) {
        start(operation, totalSteps, stage, null);
    }

    public void start(String operation, int totalSteps, String stage, Map<String, Object> messageArgs) {
        snapshots.put(
                ProjectState.currentRepositoryKey(),
                new ProgressSnapshot(
                    operation,
                    stage,
                    messageKey(stage),
                    messageArgs,
                    0,
                    Math.max(totalSteps, 1),
                    true,
                    false,
                    Instant.now().toString()
                )
        );
    }

    public void update(String stage, int step, int totalSteps) {
        update(stage, step, totalSteps, null);
    }

    public void update(String stage, int step, int totalSteps, Map<String, Object> messageArgs) {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    stage,
                    Math.max(step, 0),
                    Math.max(totalSteps, 1),
                    true,
                    false,
                    messageArgs
            );
        });
    }

    public void complete() {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    "complete",
                    snapshot.totalSteps,
                    snapshot.totalSteps,
                    false,
                    false,
                    null
            );
        });
    }

    public void fail() {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    "failed",
                    snapshot.currentStep,
                    snapshot.totalSteps,
                    false,
                    true,
                    null
            );
        });
    }

    public ProgressSnapshot getSnapshot() {
        return snapshots.getOrDefault(ProjectState.currentRepositoryKey(), ProgressSnapshot.idle());
    }

    public void clearRepository(Integer repositoryId) {
        if (repositoryId != null) {
            snapshots.remove(repositoryId);
        }
    }

    private static String messageKey(String stage) {
        String normalizedStage = stage == null || stage.isBlank() ? "idle" : stage.trim();
        return "progress.operation." + normalizedStage;
    }

    public static class ProgressSnapshot {
        private final String operation;
        private final String stage;
        private final String messageKey;
        private final Map<String, Object> messageArgs;
        private final int currentStep;
        private final int totalSteps;
        private final boolean running;
        private final boolean failed;
        private final String updatedAt;

        private ProgressSnapshot(
                String operation,
                String stage,
                String messageKey,
                Map<String, Object> messageArgs,
                int currentStep,
                int totalSteps,
                boolean running,
                boolean failed,
                String updatedAt
        ) {
            this.operation = operation;
            this.stage = stage;
            this.messageKey = messageKey;
            this.messageArgs = messageArgs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(messageArgs);
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
            this.running = running;
            this.failed = failed;
            this.updatedAt = updatedAt;
        }

        private static ProgressSnapshot idle() {
            return new ProgressSnapshot(
                    "idle",
                    "idle",
                    messageKey("idle"),
                    null,
                    0,
                    1,
                    false,
                    false,
                    Instant.now().toString()
            );
        }

        private ProgressSnapshot withUpdate(
                String stage,
                int currentStep,
                int totalSteps,
                boolean running,
                boolean failed,
                Map<String, Object> messageArgs
        ) {
            return new ProgressSnapshot(
                    operation,
                    stage,
                    messageKey(stage),
                    messageArgs,
                    currentStep,
                    totalSteps,
                    running,
                    failed,
                    Instant.now().toString()
            );
        }

        public String getOperation() {
            return operation;
        }

        public String getStage() {
            return stage;
        }

        public String getMessageKey() {
            return messageKey;
        }

        public Map<String, Object> getMessageArgs() {
            return new LinkedHashMap<>(messageArgs);
        }

        public int getCurrentStep() {
            return currentStep;
        }

        public int getTotalSteps() {
            return totalSteps;
        }

        public boolean isRunning() {
            return running;
        }

        public boolean isFailed() {
            return failed;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }
}
