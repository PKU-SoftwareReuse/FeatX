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

    public void start(String operation, int totalSteps, String stage, String message) {
        snapshots.put(
                ProjectState.currentRepositoryKey(),
                new ProgressSnapshot(
                    operation,
                    stage,
                    message,
                    0,
                    Math.max(totalSteps, 1),
                    true,
                    false,
                    null,
                    Instant.now().toString(),
                    new LinkedHashMap<>()
                )
        );
    }

    public void update(String stage, String message, int step, int totalSteps) {
        update(stage, message, step, totalSteps, null);
    }

    public void update(String stage, String message, int step, int totalSteps, Map<String, Object> details) {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    stage,
                    message,
                    Math.max(step, 0),
                    Math.max(totalSteps, 1),
                    true,
                    false,
                    null,
                    details
            );
        });
    }

    public void complete(String message) {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    "complete",
                    message,
                    snapshot.totalSteps,
                    snapshot.totalSteps,
                    false,
                    false,
                    null,
                    snapshot.details
            );
        });
    }

    public void fail(String message) {
        snapshots.compute(ProjectState.currentRepositoryKey(), (ignored, current) -> {
            ProgressSnapshot snapshot = current == null ? ProgressSnapshot.idle() : current;
            return snapshot.withUpdate(
                    "failed",
                    message,
                    snapshot.currentStep,
                    snapshot.totalSteps,
                    false,
                    true,
                    message,
                    snapshot.details
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

    public static class ProgressSnapshot {
        private final String operation;
        private final String stage;
        private final String message;
        private final int currentStep;
        private final int totalSteps;
        private final boolean running;
        private final boolean failed;
        private final String error;
        private final String updatedAt;
        private final Map<String, Object> details;

        private ProgressSnapshot(
                String operation,
                String stage,
                String message,
                int currentStep,
                int totalSteps,
                boolean running,
                boolean failed,
                String error,
                String updatedAt,
                Map<String, Object> details
        ) {
            this.operation = operation;
            this.stage = stage;
            this.message = message;
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
            this.running = running;
            this.failed = failed;
            this.error = error;
            this.updatedAt = updatedAt;
            this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        }

        private static ProgressSnapshot idle() {
            return new ProgressSnapshot(
                    "idle",
                    "idle",
                    "No operation is running.",
                    0,
                    1,
                    false,
                    false,
                    null,
                    Instant.now().toString(),
                    new LinkedHashMap<>()
            );
        }

        private ProgressSnapshot withUpdate(
                String stage,
                String message,
                int currentStep,
                int totalSteps,
                boolean running,
                boolean failed,
                String error,
                Map<String, Object> details
        ) {
            return new ProgressSnapshot(
                    operation,
                    stage,
                    message,
                    currentStep,
                    totalSteps,
                    running,
                    failed,
                    error,
                    Instant.now().toString(),
                    details
            );
        }

        public String getOperation() {
            return operation;
        }

        public String getStage() {
            return stage;
        }

        public String getMessage() {
            return message;
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

        public String getError() {
            return error;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }
}
