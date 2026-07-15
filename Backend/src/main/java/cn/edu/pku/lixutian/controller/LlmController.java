package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.service.code.AddAgentService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import cn.edu.pku.lixutian.service.code.ModifyAgentService;
import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dao.Feature;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.FeatureGraphResult;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.ContextHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.GraphAggregationHelper;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.github.javaparser.ast.body.TypeDeclaration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/llm")
public class LlmController {
    @Autowired
    private ModifyAgentService modifyAgentService;

    @Autowired
    private AddAgentService addAgentService;

    @Autowired
    private GenerateImportLinesService generateImportLinesService;
    @Autowired
    private CodeMapService codeMapService;
    @Autowired
    private LlmClient llmClient;

    public void modifyFeature(AddOrModifyRequest request) {
        mode = "modify";
        requestLanguage = AgentLanguage.orDefault(request.getLanguage());
        ClusterState.getInstance().setAgentLanguage(requestLanguage);
        newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);
        oldRequest = localizedDescription(
                ClusterState.getInstance().getCandidateFeature().getFeatureDescription(),
                ClusterState.getInstance().getCandidateFeature().getFeatureDescriptionCn(),
                requestLanguage
        );

        relatedCodes = "";
        SKG maxGraph = SKG.getInstance().getMaxGraph();
        VertexMap vertexMap = VertexMap.getInstance();

        new FeatureGraphResult(maxGraph).getNodes().forEach(node -> {
            Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(node.getId());
            GraphAggregationHelper contextHelper = new ContextHelper(maxGraph, classVertex, ClusterState.getInstance().getClusterIds());
            String contextCode = contextHelper.generateCode();
            relatedCodes += node.getId() + ":\n" + contextCode + "\n=======================\n";
        });


        allFiles = "";
        List<String> javaFiles = ListFileHelper.findJavaFiles(ProjectState.getInstance().getSrcPath());
        for (String javaFile : javaFiles) {
            allFiles += javaFile + "\n";
        }
    }

    public void addFeature(AddOrModifyRequest request) {
        mode = "add";
        requestLanguage = AgentLanguage.orDefault(request.getLanguage());
        ClusterState.getInstance().setAgentLanguage(requestLanguage);
        newRequest = request.getFeatureDescription();
        ClusterState.getInstance().setNewFeatureDescription(newRequest);

        ClusterState.getInstance().setCandidateModuleId(request.getModuleId());
        List<Feature> features = codeMapService.getFeaturesByModuleId(request.getModuleId());

        relatedCodes = "";
        for (int i = 0; i < features.size(); i++) {
            Feature feature = features.get(i);
            if (i > 3) {
                break;
            }

            String featureDescription = localizedDescription(
                    feature.getFeatureDesc(),
                    feature.getFeatureDescCN(),
                    requestLanguage
            );
            relatedCodes += requestLanguage.featureLabel() + "\n" + "\"" + featureDescription + "\": \n\n";
            codeMapService.selectFeature(feature.getId());
            SKG maxGraph = SKG.getInstance().getMaxGraph();
            VertexMap vertexMap = VertexMap.getInstance();

            new FeatureGraphResult(maxGraph).getNodes().forEach(node -> {
                Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(node.getId());
                GraphAggregationHelper contextHelper = new ContextHelper(maxGraph, classVertex, ClusterState.getInstance().getClusterIds());
                String contextCode = contextHelper.generateCode();
                relatedCodes += node.getId() + ":\n" + contextCode + "\n-----------------------\n";
            });
            relatedCodes += "\n=======================\n\n";
        }

        allFiles = "";
        List<String> javaFiles = ListFileHelper.findJavaFiles(ProjectState.getInstance().getSrcPath());
        for (String javaFile : javaFiles) {
            allFiles += javaFile + "\n";
        }

        codeMapService.selectFeature(null);
    }

    private String mode;
    // modify or add
    private String newRequest;
    private String oldRequest;
    private String relatedCodes;
    private String allFiles;
    private AgentLanguage requestLanguage = AgentLanguage.EN;


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
            return modifyAgentService.runPipeline(
                    newRequest,
                    oldRequest,
                    relatedCodes,
                    allFiles,
                    responseLanguage,
                    selectedModel
            );
        } else if (mode.equals("add")) {
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

}
