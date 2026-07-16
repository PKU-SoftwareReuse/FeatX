package cn.edu.pku.lixutian.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OperationProgressService {
    private final Object lock = new Object();
    private ProgressSnapshot snapshot = ProgressSnapshot.idle();

    public void start(String operation, int totalSteps, String stage, String message) {
        synchronized (lock) {
            snapshot = new ProgressSnapshot(
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
            );
        }
    }

    public void update(String stage, String message, int step, int totalSteps) {
        update(stage, message, step, totalSteps, null);
    }

    public void update(String stage, String message, int step, int totalSteps, Map<String, Object> details) {
        synchronized (lock) {
            snapshot = snapshot.withUpdate(
                    stage,
                    message,
                    Math.max(step, 0),
                    Math.max(totalSteps, 1),
                    true,
                    false,
                    null,
                    details
            );
        }
    }

    public void complete(String message) {
        synchronized (lock) {
            snapshot = snapshot.withUpdate(
                    "complete",
                    message,
                    snapshot.totalSteps,
                    snapshot.totalSteps,
                    false,
                    false,
                    null,
                    snapshot.details
            );
        }
    }

    public void fail(String message) {
        synchronized (lock) {
            snapshot = snapshot.withUpdate(
                    "failed",
                    message,
                    snapshot.currentStep,
                    snapshot.totalSteps,
                    false,
                    true,
                    message,
                    snapshot.details
            );
        }
    }

    public ProgressSnapshot getSnapshot() {
        synchronized (lock) {
            return snapshot;
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
