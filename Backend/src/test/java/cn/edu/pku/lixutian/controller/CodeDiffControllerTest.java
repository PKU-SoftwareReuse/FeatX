package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CodeDiffController.class)
class CodeDiffControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CandidateCodeService candidateCodeService;

    @MockitoBean
    private AgentRunRegistry agentRunRegistry;

    @Test
    void repositoryDiffIncludesTrackedAndUntrackedFiles(@TempDir Path repoPath) throws Exception {
        runGit(repoPath, "init", "-b", "main");
        runGit(repoPath, "config", "user.name", "FeatX Test");
        runGit(repoPath, "config", "user.email", "featx-test@localhost");

        Path trackedFile = repoPath.resolve("Tracked.java");
        Files.writeString(trackedFile, "class Tracked {}\n");
        runGit(repoPath, "add", "Tracked.java");
        runGit(repoPath, "commit", "-m", "baseline");

        Files.writeString(trackedFile, "class Tracked { int changed; }\n");
        Files.writeString(repoPath.resolve("Untracked.java"), "class Untracked {}\n");
        ProjectState.getInstance().setProjectPath(repoPath.toString(), "JAVA");

        mockMvc.perform(get("/code/repositoryDiff"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tracked.java")))
                .andExpect(content().string(containsString("Untracked.java")));
    }

    @Test
    void repositoryDiffRejectsProjectWithoutGit(@TempDir Path projectPath) throws Exception {
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");

        mockMvc.perform(get("/code/repositoryDiff"))
                .andExpect(status().isConflict());
    }

    private void runGit(Path workingDirectory, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
