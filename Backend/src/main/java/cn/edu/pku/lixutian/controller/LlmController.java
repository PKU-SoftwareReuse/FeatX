package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.service.code.AddAgentService;
import cn.edu.pku.lixutian.service.code.AgentService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import cn.edu.pku.lixutian.service.code.ModifyAgentService;
import cn.edu.pku.lixutian.service.code.PythonModifyAgentService;
import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.FocusGraphContextService;
import cn.edu.pku.lixutian.service.JavaGraphContextService;
import cn.edu.pku.lixutian.service.OperationProgressService;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private GenerateImportLinesService generateImportLinesService;
    @Autowired
    private CodeMapService codeMapService;
    @Autowired
    private LlmClient llmClient;

    @Autowired
    private CandidateCodeService candidateCodeService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void modifyFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        resetCandidateState();
        if (ProjectState.getInstance().isPython()) {
            modifyPythonFeature(request);
            return;
        }

        lastFocusGraphContext = null;
        progressService.start(
                "java-modify",
                8,
                "start",
                "Preparing Java Modify Feature request."
        );
        mode = "modify";
        requestLanguage = AgentLanguage.orDefault(request.getLanguage());
        ClusterState.getInstance().setAgentLanguage(requestLanguage);
        newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);
        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature == null) {
            progressService.fail("Please select a Java feature before modifying it.");
            throw new UnsupportedOperationException("Please select a Java feature before modifying it.");
        }

        try {
            oldRequest = localizedDescription(
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
            FocusGraphContextResult context = javaGraphContextService.buildModifyContext(
                    candidateFeature,
                    oldRequest,
                    newRequest,
                    deltaQuery,
                    requestLanguage
            );
            lastFocusGraphContext = context;
            relatedCodes = context.getContextPrompt();
            allFiles = projectFileList();
            progressService.complete("Java reasoning graph context is ready. Starting Java Agent code generation.");
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail(exception.getMessage());
            throw exception;
        }
    }

    private void modifyPythonFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        progressService.start(
                "python-modify",
                8,
                "start",
                "Preparing Python Modify Feature request."
        );
        mode = "modify-python";
        newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);

        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature == null) {
            progressService.fail("Please select a Python feature before modifying it.");
            throw new UnsupportedOperationException("Please select a Python feature before modifying it.");
        }

        try {
            oldRequest = candidateFeature.getFeatureDescription();

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
            lastFocusGraphContext = context;
            relatedCodes = context.getContextPrompt();

            progressService.update(
                    "prepare-agent",
                    "Preparing Python Agent inputs from the reasoning graph context.",
                    7,
                    8
            );
            allFiles = projectFileList();
            progressService.complete("FocusGraph context is ready. Starting Python Agent code generation.");
        } catch (IOException | InterruptedException | RuntimeException e) {
            progressService.fail(e.getMessage());
            throw e;
        }
    }

    public void addFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        resetCandidateState();
        if (ProjectState.getInstance().isPython()) {
            addPythonFeature(request);
            return;
        }

        lastFocusGraphContext = null;
        progressService.start(
                "java-add",
                8,
                "start",
                "Preparing Java Add Feature request."
        );
        mode = "add";
        requestLanguage = AgentLanguage.orDefault(request.getLanguage());
        ClusterState.getInstance().setAgentLanguage(requestLanguage);
        newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);

        ClusterState.getInstance().setCandidateModuleId(request.getModuleId());
        codeMapService.selectFeature(null);
        try {
            FocusGraphContextResult context = javaGraphContextService.buildAddContext(newRequest, requestLanguage);
            lastFocusGraphContext = context;
            relatedCodes = context.getContextPrompt();
            allFiles = projectFileList();
            progressService.complete("Java reasoning graph context is ready. Starting Java Agent code generation.");
        } catch (IOException | InterruptedException | RuntimeException exception) {
            progressService.fail(exception.getMessage());
            throw exception;
        }
    }

    private void addPythonFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        progressService.start(
                "python-add",
                8,
                "start",
                "Preparing Python Add Feature request."
        );
        mode = "add-python";
        newRequest = request.getFeatureDescription();
        oldRequest = "";
        ClusterState.getInstance().setNewFeatureDescription(newRequest);
        ClusterState.getInstance().setCandidateModuleId(request.getModuleId());
        codeMapService.selectFeature(null);

        try {
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
            lastFocusGraphContext = context;
            relatedCodes = context.getContextPrompt();

            progressService.update(
                    "prepare-agent",
                    "Preparing Python Agent inputs from the Add Feature reasoning graph context.",
                    7,
                    8
            );
            allFiles = projectFileList();
            progressService.complete("FocusGraph context is ready. Starting Python Agent code generation.");
        } catch (IOException | InterruptedException | RuntimeException e) {
            progressService.fail(e.getMessage());
            throw e;
        }
    }

    public void deleteFeature(AddOrModifyRequest request) throws IOException, InterruptedException {
        resetCandidateState();
        if (!ProjectState.getInstance().isPython()) {
            lastFocusGraphContext = null;
            mode = "delete";
            requestLanguage = AgentLanguage.orDefault(request.getLanguage());
            ClusterState.getInstance().setAgentLanguage(requestLanguage);
            if (request.getFeatureId() != null) {
                codeMapService.selectFeature(request.getFeatureId());
            }
            FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
            if (candidateFeature == null) {
                throw new UnsupportedOperationException("Please select a Java feature before deleting it.");
            }
            progressService.start(
                    "java-delete",
                    8,
                    "start",
                    "Preparing Java Delete Feature reasoning graph."
            );
            try {
                oldRequest = localizedDescription(
                        candidateFeature.getFeatureDescription(),
                        candidateFeature.getFeatureDescriptionCn(),
                        requestLanguage
                );
                FocusGraphContextResult context = javaGraphContextService.buildDeleteContext(
                        candidateFeature,
                        oldRequest,
                        requestLanguage
                );
                lastFocusGraphContext = context;
                relatedCodes = context.getContextPrompt();
                allFiles = projectFileList();
                progressService.complete("Java deterministic delete diff and reasoning graph are ready.");
            } catch (IOException | InterruptedException | RuntimeException exception) {
                progressService.fail(exception.getMessage());
                throw exception;
            }
            return;
        }

        lastFocusGraphContext = null;
        progressService.start(
                "python-delete",
                4,
                "start",
                "Preparing deterministic Python Delete Feature diff."
        );
        mode = "delete-python";
        if (request.getFeatureId() != null) {
            codeMapService.selectFeature(request.getFeatureId());
        }

        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature == null) {
            progressService.fail("Please select a Python feature before deleting it.");
            throw new UnsupportedOperationException("Please select a Python feature before deleting it.");
        }

        try {
            oldRequest = candidateFeature.getFeatureDescription();
            newRequest = "Delete feature: " + oldRequest;
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

            relatedCodes = "";
            allFiles = "";
            int deletedCount = plan.path("deletedMethods").size();
            int skippedCount = plan.path("skippedMethods").size();
            int fileCount = plan.path("affectedFiles").size();
            progressService.complete("Python delete diff is ready. Deleted "
                    + deletedCount + " methods across " + fileCount
                    + " files; skipped " + skippedCount + " shared or unresolved methods.");
        } catch (IOException | InterruptedException | RuntimeException e) {
            progressService.fail(e.getMessage());
            throw e;
        }
    }

    private String mode;
    // modify or add
    private String newRequest;
    private String oldRequest;
    private String relatedCodes;
    private String allFiles;
    private AgentLanguage requestLanguage = AgentLanguage.EN;
    private FocusGraphContextResult lastFocusGraphContext;


    @GetMapping("/get")
    public SseEmitter streamResponse(
            HttpServletResponse response,
            @RequestParam(required = false) AgentLanguage language,
            @RequestParam(required = false) String model
    ) throws IOException {
        AgentLanguage responseLanguage = language == null ? requestLanguage : language;
        String selectedModel;
        try {
            selectedModel = llmClient.resolveModel(model);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        if (mode.equals("modify")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Java Agent code generation.",
                    8,
                    8
            );
            return modifyAgentService.runPipeline(
                    newRequest,
                    oldRequest,
                    relatedCodes,
                    allFiles,
                    responseLanguage,
                    selectedModel
            );
        } else if (mode.equals("modify-python")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Python Agent code generation.",
                    8,
                    8
            );
            return pythonModifyAgentService.runPipeline(newRequest, oldRequest, relatedCodes, allFiles);
        } else if (mode.equals("add-python")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Python Agent code generation.",
                    8,
                    8
            );
            return pythonModifyAgentService.runAddPipeline(newRequest, relatedCodes, allFiles);
        } else if (mode.equals("delete-python")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Python Agent code generation.",
                    8,
                    8
            );
            return pythonModifyAgentService.runDeletePipeline(newRequest, oldRequest, relatedCodes, allFiles);
        } else if (mode.equals("add")) {
            progressService.update(
                    "agent-stream",
                    "Streaming Java Agent code generation.",
                    8,
                    8
            );
            return addAgentService.runPipeline(
                    newRequest,
                    relatedCodes,
                    allFiles,
                    responseLanguage,
                    selectedModel
            );
        } else {
            throw new UnsupportedOperationException("非法访问");
        }
    }

    @GetMapping("/models")
    public LlmClient.ModelCatalog getModels() throws IOException {
        return llmClient.getModelCatalog();
    }

    @GetMapping("/focusgraph/stages")
    public List<FocusGraphContextResult.GraphStage> focusGraphStages() {
        if (lastFocusGraphContext == null || lastFocusGraphContext.getGraphStages() == null) {
            return List.of();
        }
        return lastFocusGraphContext.getGraphStages();
    }

    @GetMapping("/progress")
    public OperationProgressService.ProgressSnapshot progress() {
        return progressService.getSnapshot();
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

    private String projectFileList() {
        StringBuilder fileList = new StringBuilder();
        for (String projectFile : ListFileHelper.findAllFiles(ProjectState.getInstance().getSrcPath())) {
            fileList.append(projectFile).append("\n");
        }
        return fileList.toString();
    }

    private void resetCandidateState() {
        candidateCodeService.clear();
        AgentService.modificationMap = Collections.emptyMap();
    }

}
