package com.mycode.service;

import com.mycode.config.LtmConfig;
import com.mycode.config.ProjectState;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.dto.result.FocusGraphContextResult;
import com.mycode.service.code.AgentLanguage;
import com.mycode.service.code.AgentRunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandidateGraphServiceTest {
    @Mock
    private CandidateCodeService candidateCodeService;

    @Mock
    private RepoSummaryFileGraphService repoSummaryFileGraphService;

    @Mock
    private JavaImportAnalyzerService javaImportAnalyzerService;

    @AfterEach
    void clearProjectModifications() {
        ProjectState.getInstance().setModifications(Map.of());
    }

    @Test
    void buildsFileGraphFromReasoningStageAndCandidateState(@TempDir Path projectRoot) throws Exception {
        ProjectState.getInstance().setModifications(Map.of());
        String sourceRoot = projectRoot.resolve("src/main/java").toString();
        FocusGraphContextResult.Node firstMethod = node(
                "example.First.run()",
                "src/main/java/example/First.java"
        );
        FocusGraphContextResult.Node secondMethod = node(
                "example.Second.call()",
                "src/main/java/example/Second.java"
        );
        FocusGraphContextResult.Edge reasoningEdge = new FocusGraphContextResult.Edge();
        reasoningEdge.setFrom(secondMethod.getId());
        reasoningEdge.setTo(firstMethod.getId());
        reasoningEdge.setType("CallArc");
        FocusGraphContextResult.GraphStage reasoningStage = new FocusGraphContextResult.GraphStage();
        reasoningStage.setId("reasoning");
        reasoningStage.setNodes(List.of(firstMethod, secondMethod));
        reasoningStage.setEdges(List.of(reasoningEdge));
        AgentRunContext context = new AgentRunContext(
                "run-1",
                "java-modify",
                "request",
                "old request",
                "context",
                "files",
                AgentLanguage.EN,
                sourceRoot,
                projectRoot.toString(),
                1,
                2,
                null,
                List.of(reasoningStage)
        );

        String secondFile = "src/main/java/example/Second.java";
        String addedFile = "src/main/java/example/Added.java";
        when(candidateCodeService.pendingCandidateProjectPaths()).thenReturn(List.of(secondFile, addedFile));
        when(candidateCodeService.stagedModificationKeys()).thenReturn(Set.of(addedFile));
        when(candidateCodeService.projectPathsForCandidateKeys(Set.of(addedFile))).thenReturn(List.of(addedFile));

        when(repoSummaryFileGraphService.getFileAdjacencyEdges(
                org.mockito.ArgumentMatchers.anyCollection()
        )).thenReturn(Set.of(new FeatureGraphResult.Edge(
                "src/main/java/example/First.java",
                "src/main/java/example/Second.java"
        )));
        when(javaImportAnalyzerService.candidateEdges(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyMap()
        )).thenReturn(Set.of());

        FeatureGraphResult graph = new CandidateGraphService(
                candidateCodeService,
                repoSummaryFileGraphService,
                javaImportAnalyzerService
        ).buildCandidateGraph(context);

        Map<String, FeatureGraphResult.Node> nodes = graph.getNodes().stream()
                .collect(Collectors.toMap(FeatureGraphResult.Node::getId, Function.identity()));
        assertEquals(Set.of(
                "src/main/java/example/First.java",
                secondFile,
                addedFile
        ), nodes.keySet());
        assertEquals("Default", nodes.get("src/main/java/example/First.java").getType());
        assertEquals("Modify", nodes.get(secondFile).getType());
        assertEquals("Staged", nodes.get(addedFile).getType());
        assertEquals(List.of("example.First.run()"), nodes.get("src/main/java/example/First.java").getMethods());
        assertEquals(1, graph.getEdges().size());
        FeatureGraphResult.Edge edge = graph.getEdges().iterator().next();
        assertEquals("src/main/java/example/First.java", edge.getFrom());
        assertEquals(secondFile, edge.getTo());
    }

    @Test
    void addsImportEdgesForCandidateFilesOutsideReasoningGraph(@TempDir Path projectRoot) throws Exception {
        Path firstPath = projectRoot.resolve("src/main/java/example/First.java");
        Path secondPath = projectRoot.resolve("src/main/java/dependency/Second.java");
        Files.createDirectories(firstPath.getParent());
        Files.createDirectories(secondPath.getParent());
        Files.writeString(firstPath, "package example; class First {}\n");
        Files.writeString(secondPath, "package dependency; class Second {}\n");

        String addedPath = "src/main/java/feature/Added.java";
        String addedContent = "package feature;\n"
                + "import dependency.Second;\n"
                + "class Added {}\n";
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");
        ProjectState.getInstance().setModifications(new LinkedHashMap<>(Map.of(addedPath, addedContent)));

        FocusGraphContextResult.Node firstMethod = node(
                "example.First.run()",
                "src/main/java/example/First.java"
        );
        FocusGraphContextResult.Node secondMethod = node(
                "dependency.Second.call()",
                "src/main/java/dependency/Second.java"
        );
        FocusGraphContextResult.GraphStage reasoningStage = new FocusGraphContextResult.GraphStage();
        reasoningStage.setId("reasoning");
        reasoningStage.setNodes(List.of(firstMethod, secondMethod));
        reasoningStage.setEdges(List.of());
        AgentRunContext context = new AgentRunContext(
                "run-import",
                "java-modify",
                "request",
                "old request",
                "context",
                "files",
                AgentLanguage.EN,
                projectRoot.resolve("src/main/java").toString(),
                projectRoot.toString(),
                2,
                3,
                null,
                List.of(reasoningStage)
        );

        when(candidateCodeService.pendingCandidateProjectPaths()).thenReturn(List.of(addedPath));
        when(candidateCodeService.stagedModificationKeys()).thenReturn(Set.of());
        when(candidateCodeService.projectPathsForCandidateKeys(
                org.mockito.ArgumentMatchers.anyCollection()
        )).thenAnswer(invocation -> {
            java.util.Collection<?> keys = invocation.getArgument(0);
            return keys == null || keys.isEmpty() ? List.of() : List.of(addedPath);
        });
        when(repoSummaryFileGraphService.getFileAdjacencyEdges(
                org.mockito.ArgumentMatchers.anyCollection()
        )).thenReturn(Set.of());

        FeatureGraphResult graph = new CandidateGraphService(
                candidateCodeService,
                repoSummaryFileGraphService,
                new JavaImportAnalyzerService(new LtmConfig())
        ).buildCandidateGraph(context);

        Set<String> edgeKeys = graph.getEdges().stream()
                .map(edge -> edge.getFrom() + "->" + edge.getTo())
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("src/main/java/feature/Added.java->src/main/java/dependency/Second.java"),
                edgeKeys
        );
    }

    @Test
    void keepsProjectRelativeResourcePathsInReasoningGraph(@TempDir Path projectRoot) throws Exception {
        Path javaSource = projectRoot.resolve("src/main/java/example/Existing.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "package example; class Existing {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");
        ProjectState.getInstance().setModifications(Map.of());

        String resourcePath = "src/main/resources/mapper/BlogMapper.xml";
        FocusGraphContextResult.Node resourceNode = node("resource", resourcePath);
        FocusGraphContextResult.GraphStage reasoningStage = new FocusGraphContextResult.GraphStage();
        reasoningStage.setId("reasoning");
        reasoningStage.setNodes(List.of(resourceNode));
        reasoningStage.setEdges(List.of());
        AgentRunContext context = new AgentRunContext(
                "run-resource",
                "java-modify",
                "request",
                "old request",
                "context",
                "files",
                AgentLanguage.EN,
                projectRoot.resolve("src/main/java").toString(),
                projectRoot.toString(),
                3,
                4,
                null,
                List.of(reasoningStage)
        );

        when(candidateCodeService.pendingCandidateProjectPaths()).thenReturn(List.of());
        when(candidateCodeService.stagedModificationKeys()).thenReturn(Set.of());
        when(repoSummaryFileGraphService.getFileAdjacencyEdges(
                org.mockito.ArgumentMatchers.anyCollection()
        )).thenReturn(Set.of());
        when(javaImportAnalyzerService.candidateEdges(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyMap()
        )).thenReturn(Set.of());

        FeatureGraphResult graph = new CandidateGraphService(
                candidateCodeService,
                repoSummaryFileGraphService,
                javaImportAnalyzerService
        ).buildCandidateGraph(context);

        assertEquals(Set.of(resourcePath), graph.getNodes().stream()
                .map(FeatureGraphResult.Node::getId)
                .collect(Collectors.toSet()));
    }

    private FocusGraphContextResult.Node node(String id, String file) {
        FocusGraphContextResult.Node node = new FocusGraphContextResult.Node();
        node.setId(id);
        node.setLabel(id);
        node.setCategory("Method");
        node.setMethodSignature(id);
        node.setFuncFile(file);
        return node;
    }
}
