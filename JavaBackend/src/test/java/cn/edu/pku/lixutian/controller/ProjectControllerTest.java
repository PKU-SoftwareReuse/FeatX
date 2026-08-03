package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.GraphEdgeRepository;
import cn.edu.pku.lixutian.dao.repository.ModuleRepository;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.OperationProgressService;
import cn.edu.pku.lixutian.service.RepoSummaryIndexService;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.service.ProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @MockitoBean
    private AgentRunRegistry agentRunRegistry;

    @MockitoBean
    private CandidateCodeService candidateCodeService;

    @MockitoBean
    private OperationProgressService operationProgressService;

    @MockitoBean
    private RepoSummaryIndexService repoSummaryIndexService;

    @Autowired
    private ProjectController projectController;

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
        ProjectState.clearWorkspace(ProjectState.currentWorkspaceId());
        ProjectState.getInstance().setRepoId(null);
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
    void projectListOnlyUsesUnarchivedRepositoryQuery() throws Exception {
        when(projectInfoRepository.findAllByArchivedFalseOrderByIdAsc()).thenReturn(List.of(project));

        mockMvc.perform(get("/project/getList"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].projectName").value("Old Name"));

        verify(projectInfoRepository).findAllByArchivedFalseOrderByIdAsc();
        verify(projectInfoRepository, never()).findAll();
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

    @Test
    void selectingSummarizedJavaProjectBuildsGraphWithoutBlockingOnIndexWarmup() throws Exception {
        mockMvc.perform(post("/project/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":12}"))
                .andExpect(status().isOk());

        verify(processService).process();
        verify(repoSummaryIndexService, never()).warmRepositoryIndexes(any(ProjectState.class), eq(12));
    }

    @Test
    void reopeningTheCurrentProjectDoesNotInvalidateAnActiveAgentRun() throws Exception {
        ProjectState.getInstance().setRepoId(12);
        when(agentRunRegistry.hasActiveOperation(12)).thenReturn(true);

        mockMvc.perform(post("/project/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":12}"))
                .andExpect(status().isOk());

        verify(agentRunRegistry, never()).hasActiveOperation(12);
        verify(processService, never()).process();
    }

    @Test
    void featxBranchCreationCannotEscapeIntoParentRepository(@TempDir Path parentRepo) throws Exception {
        runGit(parentRepo, "init", "-b", "main");
        Path importedProject = Files.createDirectory(parentRepo.resolve("imported-project"));

        Method createFeatxBranch = ProjectController.class.getDeclaredMethod(
                "createFeatxBranch", Path.class, String.class
        );
        createFeatxBranch.setAccessible(true);

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class,
                () -> createFeatxBranch.invoke(projectController, importedProject, "main")
        );
        assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, exception.getCause());
        assertEquals("main", gitOutput(parentRepo, "branch", "--show-current").trim());
    }

    private void runGit(Path workingDirectory, String... arguments) throws IOException, InterruptedException {
        assertEquals(0, gitProcess(workingDirectory, arguments).waitFor());
    }

    private String gitOutput(Path workingDirectory, String... arguments) throws IOException, InterruptedException {
        Process process = gitProcess(workingDirectory, arguments);
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Process gitProcess(Path workingDirectory, String... arguments) throws IOException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
    }
}
