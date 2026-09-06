package com.mycode.controller;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.CodeFileDiffResult;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.RepositoryGitService;
import com.mycode.service.code.AgentRunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    private RepositoryGitService repositoryGitService;

    @MockitoBean
    private AgentRunRegistry agentRunRegistry;

    @AfterEach
    void clearProjectModifications() {
        ProjectState.getInstance().setModifications(Map.of());
    }

    @Test
    void contextByClassReadsTheWholeJavaFileNode(@TempDir Path projectPath) throws Exception {
        String relativePath = "src/main/java/example/WholeFile.java";
        Path sourceFile = projectPath.resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join("\n",
                "package example;",
                "class WholeFile {",
                "    int first = 1;",
                "    int last = 2;",
                "}"
        ));
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");

        mockMvc.perform(get("/code/contextByClass").param("classId", relativePath))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("--- " + relativePath)))
                .andExpect(content().string(containsString(" package example;")))
                .andExpect(content().string(containsString("     int last = 2;")));
    }

    @Test
    void contextByClassReadsTheWholePythonFileNode(@TempDir Path projectPath) throws Exception {
        String relativePath = "src/main/python/sample/whole_file.py";
        Path sourceFile = projectPath.resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join("\n",
                "VALUE = 1",
                "",
                "def run():",
                "    return VALUE"
        ));
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "PYTHON");

        mockMvc.perform(get("/code/contextByClass").param("classId", relativePath))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("--- " + relativePath)))
                .andExpect(content().string(containsString(" VALUE = 1")))
                .andExpect(content().string(containsString("     return VALUE")));
    }

    @Test
    void candidateDiffAcceptsGraphNodeWithProjectSourcePrefix(@TempDir Path projectPath) throws Exception {
        String candidatePath = "src/main/java/top/naccl/util/MailUtils.java";
        Path sourceFile = projectPath.resolve(candidatePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package top.naccl.util; class MailUtils {}\n");
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");

        CodeFileDiffResult candidate = new CodeFileDiffResult();
        candidate.setKey(candidatePath);
        candidate.setPath(candidatePath);
        when(candidateCodeService.existingCandidate(candidatePath)).thenReturn(Optional.of(candidate));

        mockMvc.perform(get("/code/candidateDiff")
                        .param("classId", "src.main.java.top.naccl.util.MailUtils")
                        .param("operation", "edit")
                        .param("runId", "run-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(candidatePath)));

        verify(candidateCodeService).existingCandidate(candidatePath);
    }

    @Test
    void candidateDiffResolvesAResourceCandidateFromAnOldDuplicatePrefix(@TempDir Path projectPath) throws Exception {
        String candidatePath = "src/main/resources/mapper/BlogMapper.xml";
        ProjectState.getInstance().setProjectPath(projectPath.toString(), "JAVA");
        ProjectState.getInstance().setModifications(Map.of(candidatePath, "<mapper/>"));

        CodeFileDiffResult candidate = new CodeFileDiffResult();
        candidate.setKey(candidatePath);
        candidate.setPath(candidatePath);
        when(candidateCodeService.existingCandidate(candidatePath)).thenReturn(Optional.of(candidate));

        mockMvc.perform(get("/code/candidateDiff")
                        .param("classId", "src/main/java/" + candidatePath)
                        .param("operation", "edit")
                        .param("runId", "run-duplicate-resource"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(candidatePath)));

        verify(candidateCodeService).existingCandidate(candidatePath);
    }

}
