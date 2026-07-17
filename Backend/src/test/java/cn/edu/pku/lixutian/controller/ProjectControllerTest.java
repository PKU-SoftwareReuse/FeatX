package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.GraphEdgeRepository;
import cn.edu.pku.lixutian.dao.repository.ModuleRepository;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.ProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProcessService processService;

    @MockitoBean
    private ProjectInfoRepository projectInfoRepository;

    @MockitoBean
    private ModuleRepository moduleRepository;

    @MockitoBean
    private GraphEdgeRepository graphEdgeRepository;

    @MockitoBean
    private CodeMapService codeMapService;

    private ProjectInfo project;

    @BeforeEach
    void setUp() {
        LtmConfig ltmConfig = new LtmConfig();
        ltmConfig.setRepoPath("/tmp/featx-project-controller-test");
        ltmConfig.init();

        project = new ProjectInfo();
        project.setId(12);
        project.setRepoName("Old Name");
        project.setDescription("Old description");
        project.setProjectType("JAVA");
        project.setSummaryFlag(true);
        project.setLoc(10);
        project.setNoc(2);
        project.setNom(3);
        project.setNof(4);

        when(projectInfoRepository.findById(12)).thenReturn(Optional.of(project));
        when(projectInfoRepository.save(any(ProjectInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updateProjectPersistsBilingualDescriptionsAndGenericGitRemote() throws Exception {
        mockMvc.perform(put("/project/12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectName": "Renamed Project",
                                  "description": "English description",
                                  "descriptionCn": "中文描述",
                                  "gitLink": "https://gitlab.com/team/platform/project.git"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Renamed Project"))
                .andExpect(jsonPath("$.description").value("English description"))
                .andExpect(jsonPath("$.descriptionCn").value("中文描述"))
                .andExpect(jsonPath("$.gitProvider").value("GitLab"))
                .andExpect(jsonPath("$.gitName").value("team/platform/project"))
                .andExpect(jsonPath("$.projectType").value("JAVA"));
    }

    @Test
    void updateProjectRejectsBlankProjectName() throws Exception {
        mockMvc.perform(put("/project/12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectName": "  ",
                                  "description": "Description"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selectProjectUsesStoredPythonTypeInsteadOfDescription() throws Exception {
        project.setDescription("Uploaded repository.");
        project.setProjectType("PYTHON");

        mockMvc.perform(post("/project/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":12}"))
                .andExpect(status().isOk());

        verify(processService, never()).process();
    }
}
