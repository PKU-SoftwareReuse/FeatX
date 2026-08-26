package com.mycode.controller.graphController;

import com.mycode.config.ProjectState;
import com.mycode.controller.FeatureController;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.service.CandidateGraphService;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.CodeMapService;
import com.mycode.service.code.AgentLanguage;
import com.mycode.service.code.AgentRunContext;
import com.mycode.service.code.AgentRunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeatureGraphController.class)
class FeatureGraphControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeMapService codeMapService;

    @MockitoBean
    private FeatureController featureController;

    @MockitoBean
    private CandidateCodeService candidateCodeService;

    @MockitoBean
    private AgentRunRegistry agentRunRegistry;

    @MockitoBean
    private CandidateGraphService candidateGraphService;

    @Test
    void initialGraphUsesTheUnifiedRepoSummaryFileGraph(@TempDir Path projectPath) throws Exception {
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");
        FeatureGraphResult fileGraph = new FeatureGraphResult(
                new LinkedHashSet<>(Set.of(new FeatureGraphResult.Node("src/main/java/example/App.java"))),
                new LinkedHashSet<>()
        );
        when(codeMapService.getRepoSummaryFeatureFileGraph(61)).thenReturn(fileGraph);

        mockMvc.perform(get("/graph/feature/initialGraph").param("featureId", "61"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].id").value("src/main/java/example/App.java"))
                .andExpect(jsonPath("$.nodes[0].type").value("Default"));

        verify(featureController).select(61);
        verify(codeMapService).getRepoSummaryFeatureFileGraph(61);
        verify(codeMapService, never()).getMaxGraph();
        verify(codeMapService, never()).getPythonFeatureGraph(61);
    }

    @Test
    void candidateGraphUsesTheCompletedRunReasoningGraph(@TempDir Path projectPath) throws Exception {
        String candidatePath = "src/main/java/top/naccl/util/MailUtils.java";
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");
        AgentRunContext context = new AgentRunContext(
                "run-1",
                "java-modify",
                "request",
                "old request",
                "context",
                "files",
                AgentLanguage.EN,
                projectPath.toString(),
                projectPath.toString(),
                1,
                2,
                null,
                List.of()
        );
        FeatureGraphResult candidateGraph = new FeatureGraphResult(
                new LinkedHashSet<>(Set.of(new FeatureGraphResult.Node(candidatePath))),
                new LinkedHashSet<>()
        );
        candidateGraph.getNodes().forEach(node -> node.setType("Modify"));
        when(agentRunRegistry.requireCompleted("run-1")).thenReturn(context);
        when(candidateGraphService.buildCandidateGraph(context)).thenReturn(candidateGraph);

        mockMvc.perform(get("/graph/feature/candidateGraph").param("runId", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].id").value(candidatePath))
                .andExpect(jsonPath("$.nodes[0].type").value("Modify"));

        verify(agentRunRegistry).requireCompleted("run-1");
        verify(candidateGraphService).buildCandidateGraph(context);
    }
}
