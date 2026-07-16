package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class FocusGraphContextService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PROGRESS_PREFIX = "__FOCUSGRAPH_PROGRESS__";

    private final OperationProgressService progressService;

    public FocusGraphContextService(OperationProgressService progressService) {
        this.progressService = progressService;
    }

    public FocusGraphContextResult buildModifyContext(
            Integer featureId,
            String oldFeatureDescription,
            String newFeatureDescription,
            String deltaQuery,
            List<String> currentCodeMap
    ) throws IOException, InterruptedException {
        ProjectState state = ProjectState.getInstance();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", state.getRepoId());
        request.put("repoPath", state.getSrcPath());
        request.put("operation", "modify");
        request.put("language", "python");
        request.put("featureId", String.valueOf(featureId));
        request.put("oldFeatureDescription", oldFeatureDescription == null ? "" : oldFeatureDescription);
        request.put("newFeatureDescription", newFeatureDescription == null ? "" : newFeatureDescription);
        request.put("query", deltaQuery == null || deltaQuery.isBlank() ? newFeatureDescription : deltaQuery);
        request.put("topKFeatures", getPositiveIntEnv("FOCUSGRAPH_TOP_K_FEATURES", 3));
        request.put("topKGraphNodes", getPositiveIntEnv("FOCUSGRAPH_TOP_K_NODES", 15));
        ArrayNode codeMap = request.putArray("currentCodeMap");
        if (currentCodeMap != null) {
            currentCodeMap.stream()
                    .filter(method -> method != null && !method.isBlank())
                    .forEach(codeMap::add);
        }

        return runCli(request);
    }

    public FocusGraphContextResult buildAddContext(
            Integer moduleId,
            String newFeatureDescription
    ) throws IOException, InterruptedException {
        ProjectState state = ProjectState.getInstance();

        ObjectNode request = basePythonRequest(state, "add");
        request.put("moduleId", moduleId == null ? "" : String.valueOf(moduleId));
        request.put("newFeatureDescription", newFeatureDescription == null ? "" : newFeatureDescription);
        request.put("featureDescription", newFeatureDescription == null ? "" : newFeatureDescription);
        request.put("query", newFeatureDescription == null ? "" : newFeatureDescription);
        request.put("topKFeatures", getPositiveIntEnv("FOCUSGRAPH_TOP_K_FEATURES", 3));
        request.put("topKGraphNodes", getPositiveIntEnv("FOCUSGRAPH_TOP_K_NODES", 15));
        request.putArray("currentCodeMap");

        return runCli(request);
    }

    public FocusGraphContextResult buildDeleteContext(
            Integer featureId,
            String featureDescription,
            List<String> currentCodeMap
    ) throws IOException, InterruptedException {
        ProjectState state = ProjectState.getInstance();

        ObjectNode request = basePythonRequest(state, "delete");
        request.put("featureId", featureId == null ? "" : String.valueOf(featureId));
        request.put("oldFeatureDescription", featureDescription == null ? "" : featureDescription);
        request.put("featureDescription", featureDescription == null ? "" : featureDescription);
        request.put("query", "Delete feature and remove related implementation: "
                + (featureDescription == null ? "" : featureDescription));
        request.put("topKFeatures", getPositiveIntEnv("FOCUSGRAPH_TOP_K_FEATURES", 3));
        request.put("topKGraphNodes", getPositiveIntEnv("FOCUSGRAPH_TOP_K_NODES", 15));
        ArrayNode codeMap = request.putArray("currentCodeMap");
        if (currentCodeMap != null) {
            currentCodeMap.stream()
                    .filter(method -> method != null && !method.isBlank())
                    .forEach(codeMap::add);
        }

        return runCli(request);
    }

    private ObjectNode basePythonRequest(ProjectState state, String operation) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", state.getRepoId());
        request.put("repoPath", state.getSrcPath());
        request.put("operation", operation);
        request.put("language", "python");
        return request;
    }

    private FocusGraphContextResult runCli(JsonNode requestJson) throws IOException, InterruptedException {
        String pythonExec = getEnvOrDefault("REPOSUMMARY_PYTHON", "python3");
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");

        ProcessBuilder processBuilder = new ProcessBuilder(pythonExec, "src/focusgraph_cli.py");
        processBuilder.directory(new File(repoSummaryDir));
        Map<String, String> environment = processBuilder.environment();
        environment.putIfAbsent("LOTM_REPO_PATH", LtmConfig.getRepoPath());

        Process process = processBuilder.start();
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readProgressStream(process.getErrorStream()));

        try (OutputStream stdin = process.getOutputStream()) {
            objectMapper.writeValue(stdin, requestJson);
        }

        boolean exited = process.waitFor(600, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }

        String stdout = readCompleted(stdoutFuture);
        String stderr = readCompleted(stderrFuture);
        if (!exited) {
            throw new IOException("FocusGraph CLI timed out.\n" + stderr);
        }
        if (process.exitValue() != 0) {
            throw new IOException("FocusGraph CLI failed with exit code " + process.exitValue()
                    + "\nSTDERR:\n" + stderr
                    + "\nSTDOUT:\n" + stdout);
        }
        return objectMapper.readValue(stdout, FocusGraphContextResult.class);
    }

    private String readStream(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readProgressStream(InputStream inputStream) {
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (line.startsWith(PROGRESS_PREFIX)) {
                    updateProgressFromCli(line.substring(PROGRESS_PREFIX.length()));
                } else if (!line.isBlank()) {
                    System.out.println("[FocusGraphCLI] " + line);
                }
            }
            return all.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void updateProgressFromCli(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String stage = root.path("stage").asText("focusgraph");
            String message = root.path("message").asText(stage);
            int step = root.path("step").asInt(2);
            int total = root.path("total").asInt(8);
            Map<String, Object> details = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!"stage".equals(key) && !"message".equals(key) && !"step".equals(key) && !"total".equals(key)) {
                    JsonNode value = entry.getValue();
                    if (value.isNumber()) {
                        details.put(key, value.numberValue());
                    } else if (value.isBoolean()) {
                        details.put(key, value.booleanValue());
                    } else {
                        details.put(key, value.asText());
                    }
                }
            });
            progressService.update(stage, message, step, total, details);
        } catch (Exception ignored) {
        }
    }

    private String readCompleted(CompletableFuture<String> future) throws IOException, InterruptedException {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException uncheckedIOException) {
                throw uncheckedIOException.getCause();
            }
            throw new IOException("Failed to read FocusGraph CLI output.", cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while reading FocusGraph CLI output.", e);
        }
    }

    private int getPositiveIntEnv(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
