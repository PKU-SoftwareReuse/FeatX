package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.CodeFileDiffResult;
import cn.edu.pku.lixutian.service.code.AgentService;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateCodeServiceTest {
    @AfterEach
    void resetCandidateMap() {
        AgentService.modificationMap = null;
    }

    @Test
    void javaCandidateUsesGitDiffAndKeepsSourceUntouched(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        String original = "package demo;\n\nclass Example {\n    int value = 1;\n}\n";
        Files.writeString(sourceFile, original);
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        GenerateImportLinesService importService = mock(GenerateImportLinesService.class);
        when(importService.generate(anyString(), anyString(), anyString())).thenReturn(List.of());
        CandidateCodeService service = new CandidateCodeService(importService);
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("demo.Example", "class Example { int value = 2; }");

        CodeFileDiffResult result = service.prepareJavaCandidate(
                "demo.Example",
                "edit",
                AgentService.modificationMap.get("demo.Example")
        );

        assertEquals("src/main/java/demo/Example.java", result.getPath());
        assertTrue(result.getDiff().contains("diff --git"));
        assertTrue(result.getDiff().contains("-    int value = 1;"));
        assertTrue(result.getDiff().contains("+    int value = 2;"));
        assertEquals(original, Files.readString(sourceFile));
    }

    @Test
    void savedJavaCandidateBecomesAuthoritativeWithoutWritingWorktree(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        String original = "package demo;\n\nclass Example {}\n";
        Files.writeString(sourceFile, original);
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        GenerateImportLinesService importService = mock(GenerateImportLinesService.class);
        when(importService.generate(anyString(), anyString(), anyString())).thenReturn(List.of());
        CandidateCodeService service = new CandidateCodeService(importService);
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("demo.Example", "class Example {}");
        service.prepareJavaCandidate("demo.Example", "edit", "class Example {}");

        String edited = "package demo;\n\nimport java.util.List;\n\nclass Example { List<String> values; }\n";
        CodeFileDiffResult saved = service.updateCandidate("demo.Example", "edit", edited);

        assertFalse(saved.getDiff().isBlank());
        assertEquals(edited, service.authoritativeJavaContent("demo.Example").orElseThrow());
        assertEquals(original, Files.readString(sourceFile));
        assertTrue(AgentService.modificationMap.get("demo.Example").contains("List<String> values"));
    }

    @Test
    void pythonNewAndDeletedFileDiffsUseRepositoryPaths(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("removed.py"), "print('old')\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "PYTHON");

        CandidateCodeService service = new CandidateCodeService(mock(GenerateImportLinesService.class));
        CodeFileDiffResult added = service.preparePythonCandidate(
                "added.py",
                "added.py",
                "print('new')\n"
        );
        CodeFileDiffResult deleted = service.preparePythonCandidate(
                "removed.py",
                "removed.py",
                AgentService.DELETE_FILE_SENTINEL
        );

        assertTrue(added.getDiff().contains("diff --git a/added.py b/added.py"));
        assertTrue(added.getDiff().contains("--- /dev/null"));
        assertFalse(added.getDiff().contains("after/added.py"));
        assertTrue(deleted.getDiff().contains("diff --git a/removed.py b/removed.py"));
        assertTrue(deleted.getDiff().contains("+++ /dev/null"));
        assertFalse(deleted.getDiff().contains("before/removed.py"));
    }
}
