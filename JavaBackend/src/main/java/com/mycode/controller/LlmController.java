package com.mycode.controller;

import com.mycode.service.code.AgentLanguage;
import com.mycode.service.code.AgentRunContext;
import com.mycode.service.code.AgentRunMode;
import com.mycode.service.code.AgentRunRegistry;
import com.mycode.service.code.JavaAddAgentService;
import com.mycode.service.code.JavaDeleteAgentService;
import com.mycode.service.code.JavaModifyAgentService;
import com.mycode.service.code.PythonAddAgentService;
import com.mycode.service.code.PythonDeleteAgentService;
import com.mycode.service.code.PythonModifyAgentService;
import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.dto.request.AddOrModifyRequest;
import com.mycode.dto.result.FeatureResult;
import com.mycode.dto.result.FocusGraphContextResult;
import com.mycode.dto.result.AgentRunStartResult;
import com.mycode.dto.result.AgentRunSnapshotResult;
import com.mycode.helper.ListFileHelper;
import com.mycode.helper.ProjectPathMapping;
import com.mycode.service.CodeMapService;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.FocusGraphContextService;
import com.mycode.service.JavaGraphContextService;
import com.mycode.service.JavaStaticDeleteContextService;
import com.mycode.service.OperationProgressService;
import com.mycode.service.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/llm")
public class LlmController {
    @Autowired
    private JavaModifyAgentService javaModifyAgentService;

    @Autowired
    private JavaAddAgentService javaAddAgentService;

    @Autowired
    private JavaDeleteAgentService javaDeleteAgentService;

    @Autowired
    private PythonAddAgentService pythonAddAgentService;

    @Autowired
    private PythonModifyAgentService pythonModifyAgentService;

    @Autowired
    private PythonDeleteAgentService pythonDeleteAgentService;

    @Autowired
    private FocusGraphContextService focusGraphContextService;

    @Autowired
    private JavaGraphContextService javaGraphContextService;

    @Autowired
    private JavaStaticDeleteContextService javaStaticDeleteContextService;

    @Autowired
    private OperationProgressService progressService;

    @Autowired
    private CodeMapService codeMapService;
    @Autowired
    private LlmClient llmClient;

    @Autowired
    private CandidateCodeService candidateCodeService;

    @Autowired
    private AgentRunRegistry agentRunRegistry;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRunStartResult modifyFeature(AddOrModifyRequest request)
            throws IOException, InterruptedException {
        validateFeatureRequest(request, false);
        if (!hasText(request.getModel())) {
            throw new IllegalArgumentException("Model is required when modifying a feature.");
        }
        ProjectState project = ProjectState.getInstance();
        String runId = agentRunRegistry.reservePreparation();
        try {
            resetCandidateState(project);
            if (project.isPython()) {
                return modifyPythonFeature(request, runId, project);
            }

            progressService.start(
                    AgentRunMode.JAVA_MODIFY.id(),
                    8,
                    "start"
            );
            AgentLanguage requestLanguage = AgentLanguage.orDefault(request.getLanguage());
            ClusterState state = ClusterState.getInstance();
            FeatureResult candidateFeature = state.getCandidateFeature();
            if (candidateFeature == null || candidateFeature.getFeatureId() == null) {
                throw new IllegalStateException("Please select a Java feature before modifying it.");
            }
            state.setAgentLanguage(requestLanguage);
            String newRequest = request.getFeatureDescription();
            state.setNewFeatureDescription(newRequest);
            String oldRequest = localizedDescription(
                    candidateFeature.getFeatureDescription(),
                    candidateFeature.getFeatureDescriptionCn(),
                    requestLanguage
            );
            progressService.update(
                    "delta-query",
                    2,
                    8
            );
            String deltaQuery = buildDeltaQuery(oldRequest, newRequest, request.getModel().trim());
            FocusGraphContextResult graphContext = javaGraphContextService.buildModifyContext(
                    candidateFeature,
                    oldRequest,
                    newRequest,
                    deltaQuery,
                    requestLanguage
            );
            AgentRunContext context = agentRunRegistry.prepare(
                    runId,
                    "java-modify",
                    newRequest,
                    oldRequest,
                    graphContext.getContextPrompt(),
                    String.join("\n", ListFileHelper.findAllFiles(project.getProjectPath())),
                    requestLanguage,
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    candidateFeature.getFeatureId(),
                    null,
                    graphContext.getGraphStages()
            );
            progressService.complete();
            return new AgentRunStartResult(context.runId());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail();
            agentRunRegistry.fail(runId, exception);
            throw exception;
        }
    }

    private AgentRunStartResult modifyPythonFeature(
            AddOrModifyRequest request,
            String runId,
            ProjectState project
    )
            throws IOException, InterruptedException {
        progressService.start(
                AgentRunMode.PYTHON_MODIFY.id(),
                8,
                "start"
        );
        String newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);

        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature == null) {
            progressService.fail();
            throw new UnsupportedOperationException("Please select a Python feature before modifying it.");
        }

        String oldRequest = candidateFeature.getFeatureDescription();

        progressService.update(
                "collect-code-map",
                1,
                8
        );
        List<String> currentCodeMap = new ArrayList<>();
        if (candidateFeature.getCandidateMethods() != null) {
            candidateFeature.getCandidateMethods().forEach(candidate -> {
                if (candidate.getFullCandidateMethods() == null || candidate.getFullCandidateMethods().isEmpty()) {
                    if (candidate.getShortSignature() != null) {
                        currentCodeMap.add(candidate.getShortSignature());
                    }
                    return;
                }
                candidate.getFullCandidateMethods().forEach(full -> {
                    if (full.getFullSignature() != null) {
                        currentCodeMap.add(full.getFullSignature());
                    }
                });
            });
        }

        progressService.update(
                "delta-query",
                2,
                8
        );
        String deltaQuery = buildDeltaQuery(oldRequest, newRequest, request.getModel().trim());

        FocusGraphContextResult context = focusGraphContextService.buildModifyContext(
                candidateFeature.getFeatureId(),
                oldRequest,
                newRequest,
                deltaQuery,
                currentCodeMap
        );
        String relatedCodes = context.getContextPrompt();

        progressService.update(
                "prepare-agent",
                7,
                8
        );
        String allFiles = projectFileList(project);
        progressService.complete();
        AgentRunContext run = agentRunRegistry.prepare(
                runId,
                "python-modify",
                newRequest,
                oldRequest,
                relatedCodes,
                allFiles,
                AgentLanguage.orDefault(request.getLanguage()),
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                candidateFeature.getFeatureId(),
                null,
                context.getGraphStages()
        );
        return new AgentRunStartResult(run.runId());
    }

    public AgentRunStartResult addFeature(AddOrModifyRequest request)
            throws IOException, InterruptedException {
        validateFeatureRequest(request, true);
        ProjectState project = ProjectState.getInstance();
        String runId = agentRunRegistry.reservePreparation();
        try {
            resetCandidateState(project);
            if (project.isPython()) {
                return addPythonFeature(request, runId, project);
            }

            progressService.start(
                    AgentRunMode.JAVA_ADD.id(),
                    8,
                    "start"
            );
            AgentLanguage requestLanguage = AgentLanguage.orDefault(request.getLanguage());
            String newRequest = request.getFeatureDescription();
            ClusterState state = ClusterState.getInstance();
            state.setAgentLanguage(requestLanguage);
            state.setNewFeatureDescription(newRequest);

            state.setCandidateModuleId(request.getModuleId());
            codeMapService.selectFeature(null);
            FocusGraphContextResult graphContext = javaGraphContextService.buildAddContext(newRequest, requestLanguage);
            AgentRunContext context = agentRunRegistry.prepare(
                    runId,
                    "java-add",
                    newRequest,
                    "",
                    graphContext.getContextPrompt(),
                    String.join("\n", ListFileHelper.findAllFiles(project.getProjectPath())),
                    requestLanguage,
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    null,
                    request.getModuleId(),
                    graphContext.getGraphStages()
            );
            progressService.complete();
            return new AgentRunStartResult(context.runId());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail();
            agentRunRegistry.fail(runId, exception);
            throw exception;
        }
    }

    private AgentRunStartResult addPythonFeature(
            AddOrModifyRequest request,
            String runId,
            ProjectState project
    )
            throws IOException, InterruptedException {
        progressService.start(
                AgentRunMode.PYTHON_ADD.id(),
                8,
                "start"
        );
        String newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);
        ClusterState.getInstance().setCandidateModuleId(request.getModuleId());
        codeMapService.selectFeature(null);

        progressService.update(
                "focusgraph-context",
                1,
                8
        );
        FocusGraphContextResult context = focusGraphContextService.buildAddContext(
                request.getModuleId(),
                newRequest
        );
        String relatedCodes = context.getContextPrompt();

        progressService.update(
                "prepare-agent",
                7,
                8
        );
        String allFiles = projectFileList(project);
        progressService.complete();
        AgentRunContext run = agentRunRegistry.prepare(
                runId,
                "python-add",
                newRequest,
                "",
                relatedCodes,
                allFiles,
                AgentLanguage.orDefault(request.getLanguage()),
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                null,
                request.getModuleId(),
                context.getGraphStages()
        );
        return new AgentRunStartResult(run.runId());
    }

    public AgentRunStartResult deleteFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        ProjectState project = ProjectState.getInstance();
        String runId = agentRunRegistry.reservePreparation();
        try {
            resetCandidateState(project);
            if (request != null && request.getFeatureId() != null) {
                codeMapService.selectFeature(request.getFeatureId());
            }

            FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
            if (candidateFeature == null || candidateFeature.getFeatureId() == null) {
                throw new UnsupportedOperationException("Please select a feature before deleting it.");
            }
            AgentLanguage requestLanguage = AgentLanguage.orDefault(request == null ? null : request.getLanguage());
            ClusterState.getInstance().setAgentLanguage(requestLanguage);
            String oldRequest = localizedDescription(
                    candidateFeature.getFeatureDescription(),
                    candidateFeature.getFeatureDescriptionCn(),
                    requestLanguage
            );
            String deleteRequest = "Delete the selected feature while preserving unrelated and shared behavior: "
                    + oldRequest;
            String progressOperation = project.isPython() ? "python-delete" : "java-delete";
            progressService.start(
                    progressOperation,
                    8,
                    "start"
            );
            progressService.update(
                    "collect-code-map",
                    1,
                    8
            );
            List<String> currentCodeMap = collectCurrentCodeMap(candidateFeature);
            Set<String> sharedMethods = codeMapService.sharedCodeMapMethods(
                    candidateFeature.getFeatureId(),
                    currentCodeMap
            );
            progressService.update(
                    "ownership-check",
                    2,
                    8,
                    Map.of("protectedSharedMethods", sharedMethods.size())
            );
            JsonNode deterministicPlan = null;
            String javaStaticDeleteContext = "";
            FocusGraphContextResult graphContext;
            if (project.isPython()) {
                progressService.update(
                        "ast-delete-boundary",
                        3,
                        8
                );
                deterministicPlan = codeMapService.preparePythonDeleteFeature(
                        candidateFeature.getFeatureId(),
                        currentCodeMap
                );
                project.setModifications(Collections.emptyMap());
                project.setPythonModifiedMethods(Collections.emptySet());
                graphContext = focusGraphContextService.buildDeleteContext(
                        candidateFeature.getFeatureId(),
                        oldRequest,
                        currentCodeMap
                );
            } else {
                graphContext = javaGraphContextService.buildDeleteContext(
                        candidateFeature,
                        oldRequest,
                        requestLanguage
                );
                javaStaticDeleteContext = javaStaticDeleteContextService.buildContext();
            }

            String relatedCodes = deletionSafetyContext(
                    graphContext,
                    currentCodeMap,
                    sharedMethods,
                    deterministicPlan,
                    javaStaticDeleteContext
            );

            AgentRunContext run = agentRunRegistry.prepare(
                    runId,
                    project.isPython() ? AgentRunMode.PYTHON_DELETE.id() : AgentRunMode.JAVA_DELETE.id(),
                    deleteRequest,
                    oldRequest,
                    relatedCodes,
                    projectFileList(project),
                    requestLanguage,
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    candidateFeature.getFeatureId(),
                    null,
                    graphContext.getGraphStages()
            );
            progressService.complete();
            return new AgentRunStartResult(run.runId());
        } catch (IOException | InterruptedException | RuntimeException e) {
            progressService.fail();
            agentRunRegistry.fail(runId, e);
            throw e;
        }
    }

    @GetMapping("/get")
    public SseEmitter streamResponse(
            @RequestParam String runId,
            @RequestParam(required = false) AgentLanguage language,
            @RequestParam(required = false) String model
    ) throws IOException {
        AgentRunContext context = agentRunRegistry.requireActiveContext(runId);
        AgentRunRegistry.Status status = agentRunRegistry.status(runId);
        if (status != AgentRunRegistry.Status.PREPARED) {
            return agentRunRegistry.subscribe(runId);
        }
        if (language != null && language != context.language()) {
            agentRunRegistry.fail(runId, new IllegalArgumentException("Agent language does not match the prepared run."));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent language does not match the prepared run.");
        }
        String selectedModel;
        try {
            selectedModel = llmClient.resolveModel(model);
        } catch (IllegalArgumentException e) {
            agentRunRegistry.fail(runId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            agentRunRegistry.fail(runId, e);
            throw e;
        }
        agentRunRegistry.selectModel(runId, selectedModel);

        if (context.mode().equals(AgentRunMode.JAVA_MODIFY.id())) {
            return javaModifyAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals(AgentRunMode.PYTHON_MODIFY.id())) {
            progressService.update(
                    "agent-stream",
                    8,
                    8
            );
            return pythonModifyAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals(AgentRunMode.PYTHON_ADD.id())) {
            progressService.update(
                    "agent-stream",
                    8,
                    8
            );
            return pythonAddAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals(AgentRunMode.JAVA_ADD.id())) {
            return javaAddAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals(AgentRunMode.PYTHON_DELETE.id())) {
            progressService.update(
                    "agent-stream",
                    8,
                    8
            );
            return pythonDeleteAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals(AgentRunMode.JAVA_DELETE.id())) {
            progressService.update(
                    "agent-stream",
                    8,
                    8
            );
            return javaDeleteAgentService.runPipeline(runId, selectedModel);
        } else {
            agentRunRegistry.fail(runId, new IllegalStateException("Unsupported Agent run mode: " + context.mode()));
            throw new UnsupportedOperationException("非法访问");
        }
    }

    @GetMapping("/run")
    public AgentRunSnapshotResult runSnapshot(@RequestParam String runId) {
        return agentRunRegistry.snapshot(runId);
    }

    @GetMapping("/models")
    public LlmClient.ModelCatalog getModels() throws IOException {
        return llmClient.getModelCatalog();
    }

    @GetMapping("/focusgraph/stages")
    public List<FocusGraphContextResult.GraphStage> focusGraphStages(@RequestParam String runId) {
        return agentRunRegistry.requireActiveContext(runId).graphStages();
    }

    @GetMapping("/progress")
    public OperationProgressService.ProgressSnapshot progress() {
        return progressService.getSnapshot();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleOperationConflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    private String localizedDescription(String englishDescription, String chineseDescription, AgentLanguage language) {
        if (language == AgentLanguage.CN && hasText(chineseDescription)) {
            return chineseDescription;
        }
        if (hasText(englishDescription)) {
            return englishDescription;
        }
        return hasText(chineseDescription) ? chineseDescription : "";
    }

    private String deletionSafetyContext(
            FocusGraphContextResult graphContext,
            List<String> currentCodeMap,
            Set<String> sharedMethods,
            JsonNode deterministicPlan,
            String javaStaticDeleteContext
    ) {
        StringBuilder context = new StringBuilder(
                graphContext == null || graphContext.getContextPrompt() == null
                        ? ""
                        : graphContext.getContextPrompt()
        );
        if (hasText(javaStaticDeleteContext)) {
            context.append("\n\n").append(javaStaticDeleteContext.trim()).append('\n');
        }
        context.append("\n\n## FeatX Deterministic Delete Safety Boundary\n\n")
                .append("Only the selected feature may be removed. Preserve unrelated behavior.\n")
                .append("Every PROTECTED_SYMBOL line is enforced after Agent generation.\n\n")
                .append("### Selected Feature CodeMap\n");
        if (currentCodeMap != null) {
            currentCodeMap.stream()
                    .filter(method -> method != null && !method.isBlank())
                    .distinct()
                    .forEach(method -> context.append("FEATURE_SYMBOL: ").append(method).append('\n'));
        }
        context.append("\n### Shared Symbols That Must Remain\n");
        if (sharedMethods == null || sharedMethods.isEmpty()) {
            context.append("None\n");
        } else {
            sharedMethods.stream().sorted()
                    .forEach(method -> context.append("PROTECTED_SYMBOL: ").append(method).append('\n'));
        }
        if (deterministicPlan != null && !deterministicPlan.isMissingNode()) {
            context.append("\n### Deterministic Python AST Boundary\n")
                    .append(deterministicPlan.toPrettyString())
                    .append('\n');
            deterministicPlan.path("deletedFiles").forEach(file -> {
                String path = file.asText("").trim();
                if (!path.isBlank()) {
                    context.append("ALLOWED_DELETE_FILE: ")
                            .append(ProjectPathMapping.sourceRelativeToProject(ProjectState.getInstance(), path))
                            .append('\n');
                }
            });
        }
        return context.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateFeatureRequest(AddOrModifyRequest request, boolean requireModule) {
        if (request == null || !hasText(request.getFeatureDescription())) {
            throw new IllegalArgumentException("Feature description is required.");
        }
        if (requireModule && request.getModuleId() == null) {
            throw new IllegalArgumentException("Module id is required when adding a feature.");
        }
    }

    private String buildDeltaQuery(String oldDescription, String newDescription, String model) {
        String prompt = """
                比较旧版与新版功能描述，只提取发生变化且需要用于代码检索的需求。
                仅返回 JSON：
                {
                  "added": [],
                  "removed": [],
                  "modified": [],
                  "deltaQuery": "简短的检索查询"
                }

                旧版功能描述：
                %s

                新版功能描述：
                %s
                """;
        String response = llmClient.generateWithSinglePrompt(
                String.format(prompt, oldDescription, newDescription),
                model
        );
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(response));
            String deltaQuery = root.path("deltaQuery").asText("");
            if (!deltaQuery.isBlank()) {
                return deltaQuery;
            }
        } catch (Exception ignored) {
        }
        return newDescription;
    }

    private String extractJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        String normalized = text.replace("```json", "```");
        if (normalized.contains("```")) {
            int startFence = normalized.indexOf("```");
            int endFence = normalized.indexOf("```", startFence + 3);
            if (endFence > startFence) {
                normalized = normalized.substring(startFence + 3, endFence);
            }
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private List<String> collectCurrentCodeMap(FeatureResult candidateFeature) {
        List<String> currentCodeMap = new ArrayList<>();
        if (candidateFeature == null || candidateFeature.getCandidateMethods() == null) {
            return currentCodeMap;
        }
        candidateFeature.getCandidateMethods().forEach(candidate -> {
            if (candidate.getFullCandidateMethods() == null || candidate.getFullCandidateMethods().isEmpty()) {
                if (candidate.getShortSignature() != null) {
                    currentCodeMap.add(candidate.getShortSignature());
                }
                return;
            }
            candidate.getFullCandidateMethods().forEach(full -> {
                if (full.getFullSignature() != null) {
                    currentCodeMap.add(full.getFullSignature());
                }
            });
        });
        return currentCodeMap;
    }

    private String projectFileList(ProjectState project) {
        StringBuilder fileList = new StringBuilder();
        for (String projectFile : ListFileHelper.findAllFiles(project.getProjectPath())) {
            fileList.append(projectFile).append("\n");
        }
        return fileList.toString();
    }

    private void resetCandidateState(ProjectState project) {
        candidateCodeService.clear();
        project.setModifications(Collections.emptyMap());
        project.setPythonModifiedMethods(Collections.emptySet());
    }

}
