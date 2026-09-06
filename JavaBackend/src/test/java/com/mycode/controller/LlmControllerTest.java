package com.mycode.controller;

import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.dto.request.AddOrModifyRequest;
import com.mycode.dto.result.AgentRunStartResult;
import com.mycode.dto.result.FeatureResult;
import com.mycode.dto.result.FocusGraphContextResult;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.CodeMapService;
import com.mycode.service.FocusGraphContextService;
import com.mycode.service.JavaGraphContextService;
import com.mycode.service.JavaStaticDeleteContextService;
import com.mycode.service.OperationProgressService;
import com.mycode.service.code.AgentLanguage;
import com.mycode.service.code.AgentRunContext;
import com.mycode.service.code.AgentRunMode;
import com.mycode.service.code.AgentRunRegistry;
import com.mycode.service.code.JavaDeleteAgentService;
import com.mycode.service.code.PythonDeleteAgentService;
import com.mycode.service.llm.LlmClient;
import com.mycode.service.llm.LlmGenerationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        Files.writeString(projectRoot.resolve("application.yml"), "feature: disabled\n");
        Files.writeString(projectRoot.resolve("README.md"), "# Test project\n");
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
        when(llmClient.generateWithSinglePromptResult(anyString(), eq("selected-model")))
                .thenReturn(new LlmGenerationResult("{\"deltaQuery\":\"print greeting\"}", null));

        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(registry, javaGraphService, mock(FocusGraphContextService.class), llmClient);
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureDescription("Old feature plus greeting");
        request.setLanguage(AgentLanguage.EN);
        request.setModel("selected-model");

        AgentRunStartResult result = controller.modifyFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals(AgentRunMode.JAVA_MODIFY.id(), run.mode());
        assertEquals(feature.getFeatureId(), run.featureId());
        assertEquals("complete Java graph context", run.relatedCodes());
        assertTrue(run.allFiles().contains("application.yml"));
        assertTrue(run.allFiles().contains("README.md"));
        assertTrue(run.allFiles().contains("Feature.java"));
        assertEquals(List.of("initial"), run.graphStages().stream().map(FocusGraphContextResult.GraphStage::getId).toList());
        verify(javaGraphService).buildModifyContext(feature, "Old feature", "Old feature plus greeting", "print greeting", AgentLanguage.EN);
        ArgumentCaptor<String> deltaPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithSinglePromptResult(deltaPrompt.capture(), eq("selected-model"));
        assertTrue(deltaPrompt.getValue().contains("比较旧版与新版功能描述"));
    }

    @Test
    void standardJavaProjectUsesRepositoryRelativeAgentPaths(@TempDir Path projectRoot) throws Exception {
        Path javaSource = projectRoot.resolve("src/main/java/demo/Feature.java");
        Path resource = projectRoot.resolve("src/main/resources/application.yml");
        Files.createDirectories(javaSource.getParent());
        Files.createDirectories(resource.getParent());
        Files.writeString(javaSource, "package demo; class Feature {}\n");
        Files.writeString(resource, "feature: disabled\n");
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(43);
        FeatureResult feature = selectedFeature(8, "Old feature");

        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("Java graph context");
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        when(javaGraphService.buildModifyContext(
                any(FeatureResult.class),
                anyString(),
                anyString(),
                anyString(),
                any(AgentLanguage.class)
        )).thenReturn(graphContext);
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithSinglePromptResult(anyString(), eq("selected-model")))
                .thenReturn(new LlmGenerationResult("{\"deltaQuery\":\"enable feature\"}", null));
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                javaGraphService,
                mock(FocusGraphContextService.class),
                llmClient
        );
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureDescription("Enable feature");
        request.setLanguage(AgentLanguage.EN);
        request.setModel("selected-model");

        AgentRunContext run = registry.requireActiveContext(controller.modifyFeature(request).runId());

        assertEquals(projectRoot.resolve("src/main/java").toString(), run.sourceRoot());
        assertEquals(projectRoot.toString(), run.projectRoot());
        assertTrue(run.allFiles().contains("src/main/java/demo/Feature.java"));
        assertTrue(run.allFiles().contains("src/main/resources/application.yml"));
        assertTrue(run.allFiles().contains("pom.xml"));
        assertTrue(!run.allFiles().contains("\ndemo/Feature.java\n"));
        verify(javaGraphService).buildModifyContext(
                feature,
                "Old feature",
                "Enable feature",
                "enable feature",
                AgentLanguage.EN
        );
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

        assertEquals(AgentRunMode.JAVA_ADD.id(), run.mode());
        assertEquals(11, run.moduleId());
        assertEquals("Java add graph context", run.relatedCodes());
        assertEquals(List.of("reasoning"), run.graphStages().stream().map(FocusGraphContextResult.GraphStage::getId).toList());
        verify(javaGraphService).buildAddContext("Add report export", AgentLanguage.EN);
    }

    @Test
    void pythonModifyReceivesEveryFileUnderTheOriginalSourceRoot(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "PYTHON");
        Files.writeString(projectRoot.resolve("settings.yml"), "feature: disabled\n");
        Files.writeString(projectRoot.resolve("README.md"), "# Python project\n");
        FeatureResult feature = selectedFeature(12, "Old Python feature");

        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("complete Python graph context");
        FocusGraphContextService pythonGraphService = mock(FocusGraphContextService.class);
        when(pythonGraphService.buildModifyContext(
                anyInt(),
                anyString(),
                anyString(),
                anyString(),
                anyList()
        )).thenReturn(graphContext);
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithSinglePromptResult(anyString(), eq("selected-model")))
                .thenReturn(new LlmGenerationResult("{\"deltaQuery\":\"enable feature\"}", null));

        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                mock(JavaGraphContextService.class),
                pythonGraphService,
                llmClient
        );
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureDescription("Enable the Python feature");
        request.setLanguage(AgentLanguage.EN);
        request.setModel("selected-model");

        AgentRunStartResult result = controller.modifyFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals(AgentRunMode.PYTHON_MODIFY.id(), run.mode());
        assertEquals(feature.getFeatureId(), run.featureId());
        assertTrue(run.allFiles().contains("Feature.py"));
        assertTrue(run.allFiles().contains("settings.yml"));
        assertTrue(run.allFiles().contains("README.md"));
    }

    @Test
    void javaDeletePreparesAgentWithReasoningAndSafetyContext(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "JAVA");
        FeatureResult feature = selectedFeature(8, "Delete this Java feature");
        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("Java delete reasoning context");
        FocusGraphContextResult.GraphStage reasoning = new FocusGraphContextResult.GraphStage();
        reasoning.setId("reasoning");
        graphContext.setGraphStages(List.of(reasoning));
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        when(javaGraphService.buildDeleteContext(feature, "Delete this Java feature", AgentLanguage.EN))
                .thenReturn(graphContext);
        FocusGraphContextService pythonGraphService = mock(FocusGraphContextService.class);
        JavaStaticDeleteContextService staticDeleteContextService = mock(JavaStaticDeleteContextService.class);
        when(staticDeleteContextService.buildContext()).thenReturn("complete legacy Java static delete diff");
        CodeMapService codeMapService = mock(CodeMapService.class);
        when(codeMapService.sharedCodeMapMethods(anyInt(), anyList()))
                .thenReturn(Set.of("demo.Shared.keep()"));
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                javaGraphService,
                pythonGraphService,
                mock(LlmClient.class),
                codeMapService
        );
        ReflectionTestUtils.setField(controller, "javaStaticDeleteContextService", staticDeleteContextService);
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureId(8);
        request.setLanguage(AgentLanguage.EN);

        AgentRunStartResult result = controller.deleteFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals(AgentRunMode.JAVA_DELETE.id(), run.mode());
        assertEquals(AgentRunRegistry.Status.PREPARED, registry.status(result.runId()));
        assertTrue(run.relatedCodes().contains("Java delete reasoning context"));
        assertTrue(run.relatedCodes().contains("complete legacy Java static delete diff"));
        assertTrue(run.relatedCodes().contains("PROTECTED_SYMBOL: demo.Shared.keep()"));
        assertEquals(List.of("reasoning"), run.graphStages().stream()
                .map(FocusGraphContextResult.GraphStage::getId).toList());
        verify(javaGraphService).buildDeleteContext(feature, "Delete this Java feature", AgentLanguage.EN);
        verify(staticDeleteContextService).buildContext();
        verifyNoInteractions(pythonGraphService);
    }

    @Test
    void pythonDeleteUsesAstBoundaryThenPreparesAgent(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "PYTHON");
        FeatureResult feature = selectedFeature(9, "Delete this feature");
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        FocusGraphContextService pythonGraphService = mock(FocusGraphContextService.class);
        FocusGraphContextResult graphContext = new FocusGraphContextResult();
        graphContext.setContextPrompt("Python delete reasoning context");
        graphContext.setGraphStages(List.of());
        when(pythonGraphService.buildDeleteContext(anyInt(), anyString(), anyList()))
                .thenReturn(graphContext);
        CodeMapService codeMapService = mock(CodeMapService.class);
        when(codeMapService.preparePythonDeleteFeature(anyInt(), anyList())).thenReturn(
                OBJECT_MAPPER.readTree("{\"deletedMethods\":[\"feature_fn\"],"
                        + "\"skippedMethods\":[],\"affectedFiles\":[\"feature.py\"]}")
        );
        when(codeMapService.sharedCodeMapMethods(anyInt(), anyList())).thenReturn(Set.of());
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                javaGraphService,
                pythonGraphService,
                mock(LlmClient.class),
                codeMapService
        );
        AddOrModifyRequest request = new AddOrModifyRequest();
        request.setFeatureId(9);

        AgentRunStartResult result = controller.deleteFeature(request);
        AgentRunContext run = registry.requireActiveContext(result.runId());

        assertEquals(AgentRunMode.PYTHON_DELETE.id(), run.mode());
        assertEquals(feature.getFeatureId(), run.featureId());
        assertEquals(AgentRunRegistry.Status.PREPARED, registry.status(result.runId()));
        assertTrue(run.relatedCodes().contains("Python delete reasoning context"));
        assertTrue(run.relatedCodes().contains("Deterministic Python AST Boundary"));
        verifyNoInteractions(javaGraphService);
        verify(pythonGraphService).buildDeleteContext(eq(9), eq("Delete this feature"), anyList());
        verify(codeMapService).preparePythonDeleteFeature(anyInt(), anyList());
    }

    @Test
    void javaDeleteStreamRoutesToDeleteAgent(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "JAVA");
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.resolveModel("delete-model")).thenReturn("delete-model");
        LlmController controller = controller(
                registry,
                mock(JavaGraphContextService.class),
                mock(FocusGraphContextService.class),
                llmClient
        );
        JavaDeleteAgentService javaDeleteAgentService = mock(JavaDeleteAgentService.class);
        PythonDeleteAgentService pythonDeleteAgentService = mock(PythonDeleteAgentService.class);
        ReflectionTestUtils.setField(controller, "javaDeleteAgentService", javaDeleteAgentService);
        ReflectionTestUtils.setField(controller, "pythonDeleteAgentService", pythonDeleteAgentService);
        AgentRunContext run = preparedDeleteRun(registry, projectRoot, AgentRunMode.JAVA_DELETE);
        SseEmitter emitter = new SseEmitter();
        when(javaDeleteAgentService.runPipeline(run.runId(), "delete-model")).thenReturn(emitter);

        assertSame(emitter, controller.streamResponse(run.runId(), AgentLanguage.EN, "delete-model"));

        verify(javaDeleteAgentService).runPipeline(run.runId(), "delete-model");
        verifyNoInteractions(pythonDeleteAgentService);
    }

    @Test
    void pythonDeleteStreamRoutesToPythonDeleteAgent(@TempDir Path projectRoot) throws Exception {
        prepareProject(projectRoot, "PYTHON");
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.resolveModel("delete-model")).thenReturn("delete-model");
        LlmController controller = controller(
                registry,
                mock(JavaGraphContextService.class),
                mock(FocusGraphContextService.class),
                llmClient
        );
        JavaDeleteAgentService javaDeleteAgentService = mock(JavaDeleteAgentService.class);
        PythonDeleteAgentService pythonDeleteAgentService = mock(PythonDeleteAgentService.class);
        ReflectionTestUtils.setField(controller, "javaDeleteAgentService", javaDeleteAgentService);
        ReflectionTestUtils.setField(controller, "pythonDeleteAgentService", pythonDeleteAgentService);
        AgentRunContext run = preparedDeleteRun(registry, projectRoot, AgentRunMode.PYTHON_DELETE);
        SseEmitter emitter = new SseEmitter();
        when(pythonDeleteAgentService.runPipeline(run.runId(), "delete-model")).thenReturn(emitter);

        assertSame(emitter, controller.streamResponse(run.runId(), AgentLanguage.EN, "delete-model"));

        verify(pythonDeleteAgentService).runPipeline(run.runId(), "delete-model");
        verifyNoInteractions(javaDeleteAgentService);
    }

    @Test
    void differentProjectsBuildReasoningContextsConcurrently(
            @TempDir Path firstRoot,
            @TempDir Path secondRoot
    ) throws Exception {
        Files.writeString(firstRoot.resolve("First.java"), "class First {}\n");
        Files.writeString(secondRoot.resolve("Second.java"), "class Second {}\n");
        ProjectState firstProject = ProjectState.selectWorkspace(
                "llm-parallel-a", 701, firstRoot.toString(), "JAVA"
        );
        ProjectState secondProject = ProjectState.selectWorkspace(
                "llm-parallel-b", 702, secondRoot.toString(), "JAVA"
        );

        CountDownLatch bothInsideReasoning = new CountDownLatch(2);
        JavaGraphContextService javaGraphService = mock(JavaGraphContextService.class);
        when(javaGraphService.buildModifyContext(
                any(FeatureResult.class),
                anyString(),
                anyString(),
                anyString(),
                any(AgentLanguage.class)
        )).thenAnswer(invocation -> {
            bothInsideReasoning.countDown();
            assertTrue(
                    bothInsideReasoning.await(5, TimeUnit.SECONDS),
                    "Both repositories must enter reasoning-context preparation at the same time."
            );
            FocusGraphContextResult result = new FocusGraphContextResult();
            result.setContextPrompt("parallel context");
            result.setGraphStages(List.of());
            return result;
        });
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.generateWithSinglePromptResult(anyString(), eq("selected-model")))
                .thenReturn(new LlmGenerationResult("{\"deltaQuery\":\"parallel change\"}", null));
        AgentRunRegistry registry = new AgentRunRegistry();
        LlmController controller = controller(
                registry,
                javaGraphService,
                mock(FocusGraphContextService.class),
                llmClient
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentRunStartResult> firstRun = executor.submit(
                    () -> prepareModifyInWorkspace(controller, "llm-parallel-a", firstProject, 71)
            );
            Future<AgentRunStartResult> secondRun = executor.submit(
                    () -> prepareModifyInWorkspace(controller, "llm-parallel-b", secondProject, 72)
            );

            AgentRunStartResult firstResult = firstRun.get(10, TimeUnit.SECONDS);
            AgentRunStartResult secondResult = secondRun.get(10, TimeUnit.SECONDS);
            try (ProjectState.Scope ignored = ProjectState.bindProject("llm-parallel-a", firstProject)) {
                assertEquals(701, registry.requireActiveContext(firstResult.runId()).repositoryId());
            }
            try (ProjectState.Scope ignored = ProjectState.bindProject("llm-parallel-b", secondProject)) {
                assertEquals(702, registry.requireActiveContext(secondResult.runId()).repositoryId());
            }
            assertTrue(registry.hasActiveOperation(701));
            assertTrue(registry.hasActiveOperation(702));
        } finally {
            executor.shutdownNow();
            registry.clearRepository(701);
            registry.clearRepository(702);
        }
    }

    private AgentRunStartResult prepareModifyInWorkspace(
            LlmController controller,
            String workspaceId,
            ProjectState project,
            int featureId
    ) throws Exception {
        try (ProjectState.Scope ignored = ProjectState.bindProject(workspaceId, project)) {
            FeatureResult feature = new FeatureResult();
            feature.setFeatureId(featureId);
            feature.setFeatureDescription("Old feature " + featureId);
            ClusterState.getInstance().setCandidateFeature(feature);

            AddOrModifyRequest request = new AddOrModifyRequest();
            request.setFeatureDescription("New feature " + featureId);
            request.setLanguage(AgentLanguage.EN);
            request.setModel("selected-model");
            return controller.modifyFeature(request);
        }
    }

    private AgentRunContext preparedDeleteRun(
            AgentRunRegistry registry,
            Path projectRoot,
            AgentRunMode mode
    ) {
        return registry.prepare(
                mode.id(),
                "Delete selected feature",
                "Selected feature",
                "delete context",
                "Feature.java",
                AgentLanguage.EN,
                projectRoot.toString(),
                projectRoot.toString(),
                42,
                7,
                null,
                List.of()
        );
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
        ReflectionTestUtils.setField(controller, "javaStaticDeleteContextService", mock(JavaStaticDeleteContextService.class));
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
