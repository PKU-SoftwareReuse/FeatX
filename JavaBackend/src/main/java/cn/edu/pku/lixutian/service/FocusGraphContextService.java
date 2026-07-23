package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FocusGraphContextService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final OperationProgressService progressService;
    private final RepoSummaryHttpClient repoSummaryHttpClient;

    public FocusGraphContextService(
            OperationProgressService progressService,
            RepoSummaryHttpClient repoSummaryHttpClient
    ) {
        this.progressService = progressService;
        this.repoSummaryHttpClient = repoSummaryHttpClient;
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
        JsonNode result = repoSummaryHttpClient.postStreaming(
                "/v1/focusgraph/context",
                requestJson,
                this::updateProgress,
                600
        );
        return objectMapper.convertValue(result, FocusGraphContextResult.class);
    }

    private void updateProgress(JsonNode root) {
        try {
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
}
