package com.mycode.helper;

import com.mycode.dto.result.RepoSummaryProgressResult;
import com.mycode.service.RepoSummaryHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RepoSummaryHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepoSummaryHelper.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RepoSummaryHttpClient HTTP_CLIENT = new RepoSummaryHttpClient();
    private static final Map<Integer, ProgressState> PROGRESS_BY_REPO = new ConcurrentHashMap<>();
    private static final List<StepDefinition> STEP_DEFINITIONS = List.of(
            new StepDefinition("start", "progress.summary.start"),
            new StepDefinition("structure", "progress.summary.structure"),
            new StepDefinition("function-summary", "progress.summary.function-summary"),
            new StepDefinition("clustering", "progress.summary.clustering"),
            new StepDefinition("feature-description", "progress.summary.feature-description"),
            new StepDefinition("module-description", "progress.summary.module-description"),
            new StepDefinition("database", "progress.summary.database"),
            new StepDefinition("embedding-cache", "progress.summary.embedding-cache"),
            new StepDefinition("complete", "progress.summary.complete")
    );
    private static final Pattern PYTHON_CODET5_PATTERN = Pattern.compile("CodeT5 function descriptions .* (\\d+)/(\\d+) \\(([0-9.]+)%\\)");
    private static final Pattern PYTHON_FEATURE_DESC_PATTERN = Pattern.compile("Feature descriptions running .* (\\d+)/(\\d+) \\(([0-9.]+)%\\)");
    private static final Pattern PYTHON_TOTAL_FEATURES_PATTERN = Pattern.compile("Total clustered features: (\\d+)");
    private static final Pattern JAVA_CLUSTER_FUNCTIONS_PATTERN = Pattern.compile("Cluster ID: (\\d+), Functions: \\[(.*)]");
    private static final Pattern JAVA_CLUSTER_FILES_PATTERN = Pattern.compile("Cluster ID: (\\d+), (\\d+) Files: \\[.*]");

    public static void runRepoSummary(Integer repoId, CompletionAction completionAction) throws IOException {
        ProgressState progressState = startProgress(repoId);
        JsonNode request = OBJECT_MAPPER.createObjectNode().put("repoId", repoId);
        JsonNode started;
        try {
            started = HTTP_CLIENT.postJson("/v1/reposummary/jobs", request, 30);
        } catch (IOException exception) {
            LOGGER.error("Could not start RepoSummary job for repository {}", repoId, exception);
            progressState.fail(failureDetail(exception));
            throw exception;
        }
        String jobId = started.path("jobId").asText();
        if (jobId.isBlank()) {
            progressState.fail("RepoSummary HTTP service returned no job id.");
            throw new IOException("RepoSummary HTTP service returned no job id.");
        }

        Thread watcher = new Thread(() -> {
            int cursor = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    JsonNode snapshot = HTTP_CLIENT.getJson(
                            "/v1/reposummary/jobs/" + jobId + "?cursor=" + cursor,
                            30
                    );
                    for (JsonNode logLine : snapshot.path("logs")) {
                        String line = logLine.asText();
                        System.out.println("[RepoSummary] " + line);
                        progressState.handleLogLine(line);
                    }
                    cursor = snapshot.path("nextCursor").asInt(cursor);
                    String status = snapshot.path("status").asText();
                    if ("complete".equals(status)) {
                        if (completionAction != null) {
                            progressState.activateStep(
                                    "embedding-cache",
                                    Map.of()
                            );
                            try {
                                completionAction.run();
                                progressState.finishStep("embedding-cache");
                            } catch (Exception exception) {
                                LOGGER.error(
                                        "RepoSummary embedding-cache build failed for repository {}",
                                        repoId,
                                        exception
                                );
                                progressState.fail(failureDetail(exception));
                                return;
                            }
                        }
                        progressState.complete();
                        return;
                    }
                    if ("failed".equals(status)) {
                        String error = snapshot.path("error").asText();
                        if (error.isBlank()) {
                            error = "RepoSummary worker reported a failed job.";
                        }
                        LOGGER.error("RepoSummary job failed for repository {}: {}", repoId, error);
                        progressState.fail(error);
                        return;
                    }
                    Thread.sleep(500L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("RepoSummary watcher interrupted for repository {}", repoId, exception);
                progressState.fail("RepoSummary watcher was interrupted.");
            } catch (Exception exception) {
                LOGGER.error("RepoSummary watcher failed for repository {}", repoId, exception);
                progressState.fail(failureDetail(exception));
            }
        }, "reposummary-job-" + repoId);
        watcher.setDaemon(true);
        watcher.start();

        System.out.println("RepoSummary HTTP job started (repoId=" + repoId + ", jobId=" + jobId + ")");
    }

    private static String failureDetail(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String detail = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
        return detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
    }

    @FunctionalInterface
    public interface CompletionAction {
        void run() throws Exception;
    }

    public static RepoSummaryProgressResult getProgress(Integer repoId) {
        ProgressState state = PROGRESS_BY_REPO.get(repoId);
        return state == null ? null : state.toResult();
    }

    public static Map<Integer, RepoSummaryProgressResult> getAllProgress() {
        Map<Integer, RepoSummaryProgressResult> result = new LinkedHashMap<>();
        PROGRESS_BY_REPO.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().toResult()));
        return result;
    }

    private static ProgressState startProgress(Integer repoId) {
        ProgressState state = new ProgressState(repoId);
        PROGRESS_BY_REPO.put(repoId, state);
        state.activateStep("start", Map.of());
        return state;
    }

    private record StepDefinition(String id, String messageKey) {
    }

    private static class ProgressStep {
        private final String id;
        private final String messageKey;
        private String status = "pending";
        private Map<String, Object> messageArgs = new LinkedHashMap<>();
        private long startedAtEpochMs = 0L;
        private Long finishedAtEpochMs = null;
        private Double percent = null;

        private ProgressStep(StepDefinition definition) {
            this.id = definition.id();
            this.messageKey = definition.messageKey();
        }

        private RepoSummaryProgressResult.StepResult toResult(long now) {
            RepoSummaryProgressResult.StepResult result = new RepoSummaryProgressResult.StepResult();
            result.setId(id);
            result.setStatus(status);
            result.setMessageKey(messageKey);
            result.setMessageArgs(new LinkedHashMap<>(messageArgs));
            result.setStartedAtEpochMs(startedAtEpochMs);
            result.setFinishedAtEpochMs(finishedAtEpochMs);
            result.setElapsedMs(startedAtEpochMs == 0L ? 0L : ((finishedAtEpochMs == null ? now : finishedAtEpochMs) - startedAtEpochMs));
            result.setPercent(percent);
            return result;
        }
    }

    private static class ProgressState {
        private final Integer repoId;
        private final long startedAtEpochMs = System.currentTimeMillis();
        private final Map<String, ProgressStep> steps = new LinkedHashMap<>();
        private String status = "running";
        private String currentStage = "start";
        private String messageKey = "progress.summary.start";
        private Map<String, Object> messageArgs = new LinkedHashMap<>();
        private long updatedAtEpochMs = startedAtEpochMs;
        private Long finishedAtEpochMs = null;
        private Double currentPercent = null;

        private ProgressState(Integer repoId) {
            this.repoId = repoId;
            for (StepDefinition definition : STEP_DEFINITIONS) {
                steps.put(definition.id(), new ProgressStep(definition));
            }
        }

        private synchronized void handleLogLine(String line) {
            if (status.equals("failed") || status.equals("complete")) {
                return;
            }
            updatedAtEpochMs = System.currentTimeMillis();

            Matcher codeT5Matcher = PYTHON_CODET5_PATTERN.matcher(line);
            if (codeT5Matcher.find()) {
                int done = parseInt(codeT5Matcher.group(1));
                int total = parseInt(codeT5Matcher.group(2));
                double percent = parseDouble(codeT5Matcher.group(3));
                activateStep("function-summary", Map.of("done", done, "total", total));
                setStepPercent("function-summary", percent);
                currentPercent = percent;
                if (done >= total) {
                    finishStep("function-summary");
                }
                return;
            }

            Matcher featureDescMatcher = PYTHON_FEATURE_DESC_PATTERN.matcher(line);
            if (featureDescMatcher.find()) {
                int done = parseInt(featureDescMatcher.group(1));
                int total = parseInt(featureDescMatcher.group(2));
                double percent = parseDouble(featureDescMatcher.group(3));
                activateStep("feature-description", Map.of("done", done, "total", total));
                setStepPercent("feature-description", percent);
                currentPercent = percent;
                if (done >= total) {
                    finishStep("feature-description");
                }
                return;
            }

            Matcher totalFeaturesMatcher = PYTHON_TOTAL_FEATURES_PATTERN.matcher(line);
            if (totalFeaturesMatcher.find()) {
                finishStep("clustering");
                activateStep("feature-description", Map.of(
                        "featureCount", parseInt(totalFeaturesMatcher.group(1))
                ));
                return;
            }

            Matcher javaClusterFilesMatcher = JAVA_CLUSTER_FILES_PATTERN.matcher(line);
            if (javaClusterFilesMatcher.find()) {
                finishStep("structure");
                activateStep("clustering", Map.of(
                        "clusterId", parseInt(javaClusterFilesMatcher.group(1)),
                        "fileCount", parseInt(javaClusterFilesMatcher.group(2))
                ));
                return;
            }

            Matcher javaClusterFunctionsMatcher = JAVA_CLUSTER_FUNCTIONS_PATTERN.matcher(line);
            if (javaClusterFunctionsMatcher.find()) {
                finishStep("structure");
                activateStep("clustering", Map.of(
                        "clusterId", parseInt(javaClusterFunctionsMatcher.group(1)),
                        "functionCount", countListItems(javaClusterFunctionsMatcher.group(2))
                ));
                return;
            }

            if (line.contains("Attached ") && line.contains("Python functions to files")) {
                finishStep("function-summary");
                activateStep("clustering", Map.of());
                return;
            }
            if (line.contains("File clustering gamma")) {
                finishStep("function-summary");
                activateStep("clustering", Map.of());
                return;
            }
            if (line.contains("Generating descriptions for") && line.contains("clustered features")) {
                finishStep("clustering");
                activateStep("feature-description", Map.of());
                return;
            }
            if (line.contains("Writing summary to database")) {
                finishStep("module-description");
                finishStep("feature-description");
                activateStep("database", Map.of());
                return;
            }
            if (line.contains("Database write complete")) {
                finishStep("database");
                activateStep("embedding-cache", Map.of());
                return;
            }
            if (line.startsWith("Feature ID:") || line.startsWith("Feature ID ")) {
                finishStep("structure");
                activateStep("feature-description", Map.of());
                return;
            }
            if (line.startsWith("Module ID:")) {
                finishStep("feature-description");
                activateStep("module-description", Map.of());
                return;
            }
            if (line.contains("method_adj_matrix.csv") || line.contains("file_adj_matrix.csv")) {
                finishStep("start");
                activateStep("structure", Map.of());
                return;
            }
            if (line.contains("LLM call failed; retrying")) {
                messageKey = "progress.summary.llm-retry";
                messageArgs = new LinkedHashMap<>();
                return;
            }
            if (line.toLowerCase().contains("error") || line.toLowerCase().contains("exception")) {
                messageKey = "progress.summary.warning";
                messageArgs = new LinkedHashMap<>();
            }
        }

        private synchronized void activateStep(String stepId, Map<String, Object> args) {
            int targetIndex = indexOf(stepId);
            long now = System.currentTimeMillis();
            for (int i = 0; i < targetIndex; i++) {
                ProgressStep previous = stepAt(i);
                if (previous.status.equals("pending") || previous.status.equals("running")) {
                    previous.status = "done";
                    previous.finishedAtEpochMs = now;
                    previous.percent = 100.0;
                }
            }

            ProgressStep step = steps.get(stepId);
            if (step == null) {
                return;
            }
            if (step.status.equals("pending")) {
                step.status = "running";
                step.startedAtEpochMs = now;
            }
            step.messageArgs = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
            currentStage = stepId;
            messageKey = step.messageKey;
            messageArgs = new LinkedHashMap<>(step.messageArgs);
            currentPercent = step.percent;
            updatedAtEpochMs = now;
        }

        private synchronized void finishStep(String stepId) {
            ProgressStep step = steps.get(stepId);
            if (step == null || step.status.equals("done")) {
                return;
            }
            step.status = "done";
            step.finishedAtEpochMs = System.currentTimeMillis();
            step.percent = 100.0;
        }

        private synchronized void setStepPercent(String stepId, double percent) {
            ProgressStep step = steps.get(stepId);
            if (step != null) {
                step.percent = percent;
            }
        }

        private synchronized void complete() {
            long now = System.currentTimeMillis();
            for (ProgressStep step : steps.values()) {
                if (step.status.equals("pending")) {
                    step.startedAtEpochMs = now;
                }
                if (!step.status.equals("done")) {
                    step.status = "done";
                    step.finishedAtEpochMs = now;
                }
                step.percent = 100.0;
            }
            status = "complete";
            currentStage = "complete";
            messageKey = "progress.summary.complete";
            messageArgs = new LinkedHashMap<>();
            currentPercent = 100.0;
            updatedAtEpochMs = now;
            finishedAtEpochMs = now;
        }

        private synchronized void fail(String error) {
            long now = System.currentTimeMillis();
            Map<String, Object> failureArgs = error == null || error.isBlank()
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(Map.of("error", error));
            ProgressStep step = steps.get(currentStage);
            if (step != null) {
                step.status = "failed";
                step.messageArgs = new LinkedHashMap<>(failureArgs);
                step.finishedAtEpochMs = now;
            }
            status = "failed";
            messageKey = "progress.summary.failed";
            messageArgs = new LinkedHashMap<>(failureArgs);
            updatedAtEpochMs = now;
            finishedAtEpochMs = now;
        }

        private synchronized RepoSummaryProgressResult toResult() {
            long now = System.currentTimeMillis();
            RepoSummaryProgressResult result = new RepoSummaryProgressResult();
            result.setRepoId(repoId);
            result.setStatus(status);
            result.setCurrentStage(currentStage);
            result.setMessageKey(messageKey);
            result.setMessageArgs(new LinkedHashMap<>(messageArgs));
            result.setStartedAtEpochMs(startedAtEpochMs);
            result.setUpdatedAtEpochMs(updatedAtEpochMs);
            result.setFinishedAtEpochMs(finishedAtEpochMs);
            result.setElapsedMs((finishedAtEpochMs == null ? now : finishedAtEpochMs) - startedAtEpochMs);
            result.setCurrentStep(Math.max(1, indexOf(currentStage) + 1));
            result.setTotalSteps(STEP_DEFINITIONS.size());
            result.setPercent(currentPercent);
            List<RepoSummaryProgressResult.StepResult> stepResults = new ArrayList<>();
            for (ProgressStep step : steps.values()) {
                stepResults.add(step.toResult(now));
            }
            result.setSteps(stepResults);
            return result;
        }

        private int indexOf(String stepId) {
            for (int i = 0; i < STEP_DEFINITIONS.size(); i++) {
                if (STEP_DEFINITIONS.get(i).id().equals(stepId)) {
                    return i;
                }
            }
            return 0;
        }

        private ProgressStep stepAt(int index) {
            return steps.get(STEP_DEFINITIONS.get(index).id());
        }

        private int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private double parseDouble(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }

        private int countListItems(String rawItems) {
            String trimmed = rawItems == null ? "" : rawItems.trim();
            if (trimmed.isEmpty()) {
                return 0;
            }
            return trimmed.split("\\s*,\\s*").length;
        }
    }

}
