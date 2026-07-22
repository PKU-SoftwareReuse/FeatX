package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.service.code.AddAgentService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.service.code.ModifyAgentService;
import cn.edu.pku.lixutian.service.code.PythonModifyAgentService;
import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import cn.edu.pku.lixutian.dto.result.AgentRunStartResult;
import cn.edu.pku.lixutian.dto.result.AgentRunSnapshotResult;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.FocusGraphContextService;
import cn.edu.pku.lixutian.service.JavaGraphContextService;
import cn.edu.pku.lixutian.service.OperationProgressService;
import cn.edu.pku.lixutian.service.llm.LlmClient;
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

@RestController
@RequestMapping("/llm")
public class LlmController {
    @Autowired
    private ModifyAgentService modifyAgentService;

    @Autowired
    private AddAgentService addAgentService;

    @Autowired
    private PythonModifyAgentService pythonModifyAgentService;

    @Autowired
    private FocusGraphContextService focusGraphContextService;

    @Autowired
    private JavaGraphContextService javaGraphContextService;

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
        ProjectState project = ProjectState.getInstance();
        String runId = agentRunRegistry.reservePreparation();
        try {
            resetCandidateState(project);
            if (project.isPython()) {
                return modifyPythonFeature(request, runId, project);
            }

            progressService.start(
                    "java-modify",
                    8,
                    "start",
                    "Preparing Java Modify Feature request."
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
                    "Extracting the Java feature delta query with LLM.",
                    2,
                    8
            );
            String deltaQuery = buildDeltaQuery(oldRequest, newRequest);
            FocusGraphContextResult graphContext = javaGraphContextService.buildModifyContext(
                    candidateFeature,
                    oldRequest,
                    newRequest,
                    deltaQuery,
                    requestLanguage
            );
            AgentRunContext context = agentRunRegistry.prepare(
                    runId,
                    "modify",
                    newRequest,
                    oldRequest,
                    graphContext.getContextPrompt(),
                    String.join("\n", ListFileHelper.findAllFiles(project.getSrcPath())),
                    requestLanguage,
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    candidateFeature.getFeatureId(),
                    null,
                    graphContext.getGraphStages()
            );
            progressService.complete("Java reasoning graph context is ready. Starting Java Agent code generation.");
            return new AgentRunStartResult(context.runId());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail(exception.getMessage());
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
                "python-modify",
                8,
                "start",
                "Preparing Python Modify Feature request."
        );
        String newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);

        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature == null) {
            progressService.fail("Please select a Python feature before modifying it.");
            throw new UnsupportedOperationException("Please select a Python feature before modifying it.");
        }

        String oldRequest = candidateFeature.getFeatureDescription();

        progressService.update(
                "collect-code-map",
                "Collecting current feature CodeMap methods.",
                1,
                8
        );
        List<String> currentCodeMap = new ArrayList<>();
        if (candidateFeature.getCandidateMethods() != null) {
            candidateFeature.getCandidateMethods().forEach(candidate -> {
                if (candidate.getLxtFull() == null || candidate.getLxtFull().isEmpty()) {
                    if (candidate.getZyfShortSignature() != null) {
                        currentCodeMap.add(candidate.getZyfShortSignature());
                    }
                    return;
                }
                candidate.getLxtFull().forEach(full -> {
                    if (full.getLxtFullSignature() != null) {
                        currentCodeMap.add(full.getLxtFullSignature());
                    }
                });
            });
        }

        progressService.update(
                "delta-query",
                "Extracting the feature delta query with LLM.",
                2,
                8
        );
        String deltaQuery = buildDeltaQuery(oldRequest, newRequest);

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
                "Preparing Python Agent inputs from the reasoning graph context.",
                7,
                8
        );
        String allFiles = projectFileList(project);
        progressService.complete("FocusGraph context is ready. Starting Python Agent code generation.");
        AgentRunContext run = agentRunRegistry.prepare(
                runId,
                "modify-python",
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
                    "java-add",
                    8,
                    "start",
                    "Preparing Java Add Feature request."
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
                    "add",
                    newRequest,
                    "",
                    graphContext.getContextPrompt(),
                    String.join("\n", ListFileHelper.findAllFiles(project.getSrcPath())),
                    requestLanguage,
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    null,
                    request.getModuleId(),
                    graphContext.getGraphStages()
            );
            progressService.complete("Java reasoning graph context is ready. Starting Java Agent code generation.");
            return new AgentRunStartResult(context.runId());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail(exception.getMessage());
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
                "python-add",
                8,
                "start",
                "Preparing Python Add Feature request."
        );
        String newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);
        ClusterState.getInstance().setCandidateModuleId(request.getModuleId());
        codeMapService.selectFeature(null);

        progressService.update(
                "focusgraph-context",
                "Building FocusGraph context from similar features.",
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
                "Preparing Python Agent inputs from the Add Feature reasoning graph context.",
                7,
                8
        );
        String allFiles = projectFileList(project);
        progressService.complete("FocusGraph context is ready. Starting Python Agent code generation.");
        AgentRunContext run = agentRunRegistry.prepare(
                runId,
                "add-python",
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

            if (!project.isPython()) {
                AgentRunContext run = agentRunRegistry.prepare(
                        runId,
                        "delete",
                        "",
                        localizedDescription(
                                candidateFeature.getFeatureDescription(),
                                candidateFeature.getFeatureDescriptionCn(),
                                AgentLanguage.EN
                        ),
                        "",
                        "",
                        AgentLanguage.EN,
                        project.getSrcPath(),
                        project.getProjectPath(),
                        project.getRepoId(),
                        candidateFeature.getFeatureId(),
                        null,
                        List.of()
                );
                agentRunRegistry.completePrepared(runId, Map.of());
                return new AgentRunStartResult(run.runId());
            }

            progressService.start(
                    "python-delete",
                    4,
                    "start",
                    "Preparing deterministic Python Delete Feature diff."
            );
            progressService.update(
                    "collect-code-map",
                    "Collecting current feature CodeMap methods.",
                    1,
                    4
            );
            List<String> currentCodeMap = collectCurrentCodeMap(candidateFeature);

            progressService.update(
                    "ownership-check",
                    "Checking whether CodeMap methods are uniquely owned by this feature.",
                    2,
                    4
            );

            progressService.update(
                    "ast-delete-plan",
                    "Planning Python function/method deletion with AST.",
                    3,
                    4
            );
            JsonNode plan = codeMapService.preparePythonDeleteFeature(
                    candidateFeature.getFeatureId(),
                    currentCodeMap
            );

            AgentRunContext run = agentRunRegistry.prepare(
                    runId,
                    "delete",
                    "",
                    candidateFeature.getFeatureDescription(),
                    "",
                    "",
                    AgentLanguage.orDefault(request == null ? null : request.getLanguage()),
                    project.getSrcPath(),
                    project.getProjectPath(),
                    project.getRepoId(),
                    candidateFeature.getFeatureId(),
                    null,
                    List.of()
            );
            agentRunRegistry.completePrepared(runId, project.getModifications());

            int deletedCount = plan.path("deletedMethods").size();
            int skippedCount = plan.path("skippedMethods").size();
            int fileCount = plan.path("affectedFiles").size();
            progressService.complete("Python delete diff is ready. Deleted "
                    + deletedCount + " methods across " + fileCount
                    + " files; skipped " + skippedCount + " shared or unresolved methods.");
            return new AgentRunStartResult(run.runId());
        } catch (IOException | InterruptedException | RuntimeException e) {
            progressService.fail(e.getMessage());
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

        if (context.mode().equals("modify")) {
            return modifyAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals("modify-python")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Python Agent code generation.",
                    8,
                    8
            );
            return pythonModifyAgentService.runPipeline(runId, selectedModel);
        } else if (context.mode().equals("add-python")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Python Agent code generation.",
                    8,
                    8
            );
            return pythonModifyAgentService.runAddPipeline(runId, selectedModel);
        } else if (context.mode().equals("add")) {
            return addAgentService.runPipeline(runId, selectedModel);
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

    @GetMapping("/testLLM")
    public SseEmitter testLLM() throws IOException {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        llmClient.streamGenerateWithPrompt("你是谁", emitter);
        return emitter;
    }

    @GetMapping("/testLLM2")
    public String testLLM2() throws IOException {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        return llmClient.streamGenerateWithPrompt("你是谁", emitter);
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

    private String buildDeltaQuery(String oldDescription, String newDescription) {
        String prompt = """
                Compare the old and new feature descriptions. Extract only the changed requirement for code retrieval.
                Return JSON only:
                {
                  "added": [],
                  "removed": [],
                  "modified": [],
                  "deltaQuery": "short retrieval query"
                }

                oldFeatureDescription:
                %s

                newFeatureDescription:
                %s
                """;
        String response = llmClient.generateWithSinglePrompt(String.format(prompt, oldDescription, newDescription));
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
            if (candidate.getLxtFull() == null || candidate.getLxtFull().isEmpty()) {
                if (candidate.getZyfShortSignature() != null) {
                    currentCodeMap.add(candidate.getZyfShortSignature());
                }
                return;
            }
            candidate.getLxtFull().forEach(full -> {
                if (full.getLxtFullSignature() != null) {
                    currentCodeMap.add(full.getLxtFullSignature());
                }
            });
        });
        return currentCodeMap;
    }

    private String projectFileList(ProjectState project) {
        StringBuilder fileList = new StringBuilder();
        for (String projectFile : ListFileHelper.findAllFiles(project.getSrcPath())) {
            fileList.append(projectFile).append("\n");
        }
        return fileList.toString();
    }

    private void resetCandidateState(ProjectState project) {
        candidateCodeService.clear();
        project.setModifications(Collections.emptyMap());
    }

}
