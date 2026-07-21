package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.AgentRunStartResult;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.FocusGraphContextService;
import cn.edu.pku.lixutian.service.JavaGraphContextService;
import cn.edu.pku.lixutian.service.OperationProgressService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmControllerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void clearState() {
        ClusterState state = ClusterState.getInstance();
        state.setCandidateFeature(null);
        state.setCandidateModuleId(null);
        state.setNewFeatureDescription(null);
        ProjectState.getInstance().setRepoId(null);
    }

    @Test
    void javaModifyStoresReasoningStagesInRunContext(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "JAVA");
        FeatureResult feature = selectedFeature(7, "Old feature");

        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("complete Java graph context");
        FocusGraphContextResult.GraphStage initial = new FocusGraphContextResult.GraphStage();
        initial.setId("initial");
        graphContext.setGraphStages(List.of(initial));

        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        when(javaGraphService.buildModifyContext(
                any(FeatureResult.class),
                anyString(),
                anyString(),
                anyString(),
                any(AgentLanguage.class)
        )).thenReturn(graphContext);
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithSinglePrompt(anyString()))
                .thenReturn("{\"deltaQuery\":\"print greeting\"}");

        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(registry, javaGraphService, mock(FocusGraphContextService.class), llmClient);
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureDescription("Old feature plus greeting");
        request.setLanguage(AgentLanguage.EN);

        AgentRunStartResult result = controller.modifyFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals("modify", run.mode());
        assertEquals(feature.getFeatureId(), run.featureId());
        assertEquals("complete Java graph context", run.relatedCodes());
        assertEquals(List.of("initial"), run.graphStages().stream().map(FocusGraphContextResult.GraphStage::getId).toList());
        verify(javaGraphService).buildModifyContext(feature, "Old feature", "Old feature plus greeting", "print greeting", AgentLanguage.EN);
    }

    @Test
    void javaAddStoresReasoningStagesAndTargetModuleInRunContext(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "JAVA");
        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("Java add graph context");
        FocusGraphContextResult.GraphStage reasoning = new FocusGraphContextResult.GraphStage();
        reasoning.setId("reasoning");
        graphContext.setGraphStages(List.of(reasoning));

        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        when(javaGraphService.buildAddContext("Add report export", AgentLanguage.EN)).thenReturn(graphContext);
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                javaGraphService,
                mock(FocusGraphContextService.class),
                mock(LlmClient.class)
        );
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureDescription("Add report export");
        request.setModuleId(11);
        request.setLanguage(AgentLanguage.EN);

        AgentRunStartResult result = controller.addFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals("add", run.mode());
        assertEquals(11, run.moduleId());
        assertEquals("Java add graph context", run.relatedCodes());
        assertEquals(List.of("reasoning"), run.graphStages().stream().map(FocusGraphContextResult.GraphStage::getId).toList());
        verify(javaGraphService).buildAddContext("Add report export", AgentLanguage.EN);
    }

    @Test
    void javaDeleteDoesNotBuildReasoningStages(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "JAVA");
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        FocusGraphContextService pythonGraphService = mock(FocusGraphContextService.class);
        LlmController controller = controller(
                new AgentRunRegistry(),
                javaGraphService,
                pythonGraphService,
                mock(LlmClient.class)
        );

        controller.deleteFeature(new AddOrModifyRequest());

        verifyNoInteractions(javaGraphService, pythonGraphService);
    }

    @Test
    void pythonDeleteDoesNotBuildReasoningStages(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "PYTHON");
        selectedFeature(9, "Delete this feature");
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        FocusGraphContextService pythonGraphService = mock(FocusGraphContextService.class);
        CodeMapService codeMapService = mock(CodeMapService.class);
        when(codeMapService.preparePythonDeleteFeature(anyInt(), anyList())).thenReturn(
                OBJECT_MAPPER.readTree("{\"deletedMethods\":[],\"skippedMethods\":[],\"affectedFiles\":[]}")
        );
        LlmController controller = controller(
                new AgentRunRegistry(),
                javaGraphService,
                pythonGraphService,
                mock(LlmClient.class),
                codeMapService
        );
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureId(9);

        controller.deleteFeature(request);

        verifyNoInteractions(javaGraphService, pythonGraphService);
        verify(codeMapService).preparePythonDeleteFeature(anyInt(), anyList());
        verify(codeMapService, never()).getFeaturesByModuleId(anyInt());
    }

    private LlmController controller(
            AgentRunRegistry registry,
            JavaGraphContextService javaGraphService,
            FocusGraphContextService pythonGraphService,
            LlmClient llmClient
    ) {
        return controller(registry, javaGraphService, pythonGraphService, llmClient, mock(CodeMapService.class));
    }

    private LlmController controller(
            AgentRunRegistry registry,
            JavaGraphContextService javaGraphService,
            FocusGraphContextService pythonGraphService,
            LlmClient llmClient,
            CodeMapService codeMapService
    ) {
        LlmController controller = new LlmController();
        ReflectionTestUtils.setField(controller, "agentRunRegistry", registry);
        ReflectionTestUtils.setField(controller, "javaGraphContextService", javaGraphService);
        ReflectionTestUtils.setField(controller, "focusGraphContextService", pythonGraphService);
        ReflectionTestUtils.setField(controller, "llmClient", llmClient);
        ReflectionTestUtils.setField(controller, "codeMapService", codeMapService);
        ReflectionTestUtils.setField(controller, "candidateCodeService", mock(CandidateCodeService.class));
        ReflectionTestUtils.setField(controller, "progressService", mock(OperationProgressService.class));
        return controller;
    }

    private void prepareProject(Path projectRoot, String type) throws Exception {
        String extension = "PYTHON".equals(type) ? ".py" : ".java";
        Files.writeString(projectRoot.resolve("Feature" + extension), "");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), type);
        project.setRepoId(42);
    }

    private FeatureResult selectedFeature(int featureId, String description) {
        FeatureResult feature = new FeatureResult();
        feature.setFeatureId(featureId);
        feature.setFeatureDescription(description);
        ClusterState.getInstance().setCandidateFeature(feature);
        return feature;
    }
}
