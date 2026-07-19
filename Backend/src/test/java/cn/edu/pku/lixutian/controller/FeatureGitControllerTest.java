package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import cn.edu.pku.lixutian.service.FeatureGitWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeatureGitController.class)
class FeatureGitControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureGitWorkflowService workflowService;

    @Test
    void statusReturnsWorkspaceState() throws Exception {
        GitWorkspaceStatusResult result = new GitWorkspaceStatusResult();
        result.setBranch("featx-dev/main");
        result.setCommitScope("NONE");
        when(workflowService.status()).thenReturn(result);

        mockMvc.perform(get("/code/git/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("featx-dev/main"))
                .andExpect(jsonPath("$.commitScope").value("NONE"));
    }

    @Test
    void stageRejectsMissingCandidateKey() throws Exception {
        mockMvc.perform(post("/code/git/stage")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusReportsMissingProjectAsConflict() throws Exception {
        when(workflowService.status()).thenThrow(new IllegalStateException("No project is currently selected."));

        mockMvc.perform(get("/code/git/status"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No project is currently selected."));
    }
}
