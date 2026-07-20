package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.CodeFileDiffResult;
import cn.edu.pku.lixutian.service.code.AgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put(
                "demo/Example.java",
                "package demo;\n\nclass Example {\n    int value = 2;\n}\n"
        );

        CodeFileDiffResult result = service.prepareJavaCandidate(
                "demo/Example.java",
                "edit",
                AgentService.modificationMap.get("demo/Example.java")
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

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("demo/Example.java", original);
        service.prepareJavaCandidate("demo/Example.java", "edit", original);

        String edited = "package demo;\n\nimport java.util.List;\n\nclass Example { List<String> values; }\n";
        CodeFileDiffResult saved = service.updateCandidate("demo/Example.java", "edit", edited);

        assertFalse(saved.getDiff().isBlank());
        assertEquals(edited, service.authoritativeJavaContent("demo/Example.java").orElseThrow());
        assertEquals(original, Files.readString(sourceFile));
        assertTrue(AgentService.modificationMap.get("demo/Example.java").contains("List<String> values"));
    }

    @Test
    void materializeJavaCandidateWritesOnlyTheConfirmedFile(@TempDir Path projectRoot) throws Exception {
        Path firstFile = projectRoot.resolve("src/main/java/demo/First.java");
        Path secondFile = projectRoot.resolve("src/main/java/demo/Second.java");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "package demo;\n\nclass First {}\n");
        Files.writeString(secondFile, "package demo;\n\nclass Second {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("demo/First.java", "package demo;\n\nclass First { int changed; }\n");
        AgentService.modificationMap.put("demo/Second.java", "package demo;\n\nclass Second { int untouched; }\n");
        service.prepareJavaCandidate("demo/First.java", "edit", AgentService.modificationMap.get("demo/First.java"));

        service.materializeCandidate("demo/First.java");

        assertTrue(Files.readString(firstFile).contains("int changed"));
        assertEquals("package demo;\n\nclass Second {}\n", Files.readString(secondFile));
    }

    @Test
    void completeValidationAcceptsAReviewedJavaCandidate(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\n\nclass Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put(
                "demo/Example.java",
                "package demo;\n\nclass Example { int changed; }\n"
        );
        service.beginOperation("test", AgentService.modificationMap.keySet());
        service.prepareJavaCandidate(
                "demo/Example.java",
                "edit",
                AgentService.modificationMap.get("demo/Example.java")
        );

        service.validateCompleteCandidateSet();
    }

    @Test
    void candidatePreparationRejectsInvalidJavaSyntax(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\n\nclass Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("demo/Example.java", "package demo; class Example {");
        service.beginOperation("test", AgentService.modificationMap.keySet());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.prepareJavaCandidate(
                        "demo/Example.java",
                        "edit",
                        AgentService.modificationMap.get("demo/Example.java")
                )
        );
        assertTrue(error.getMessage().contains("valid Java source"));
    }

    @Test
    void completeValidationRejectsATypeThatDoesNotMatchItsJavaPath(@TempDir Path projectRoot) throws Exception {
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        AgentService.modificationMap = new LinkedHashMap<>();
        AgentService.modificationMap.put("cn/edu/pku/Foo.java", "public class Bar {}");
        service.beginOperation("test", AgentService.modificationMap.keySet());
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.prepareJavaCandidate(
                        "cn/edu/pku/Foo.java",
                        "add",
                        AgentService.modificationMap.get("cn/edu/pku/Foo.java")
                )
        );
        assertTrue(error.getMessage().contains("Foo"));
    }

    @Test
    void pythonNewAndDeletedFileDiffsUseRepositoryPaths(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("removed.py"), "print('old')\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "PYTHON");

        CandidateCodeService service = new CandidateCodeService();
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
