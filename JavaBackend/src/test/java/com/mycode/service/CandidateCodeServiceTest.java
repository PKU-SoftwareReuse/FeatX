package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.CodeFileDiffResult;
import com.mycode.service.code.AgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateCodeServiceTest {
    @AfterEach
    void resetCandidateMap() {
        ProjectState.getInstance().setModifications(Map.of());
    }

    @Test
    void javaCandidateUsesGitDiffAndKeepsSourceUntouched(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        String original = "package demo;\n\nclass Example {\n    int value = 1;\n}\n";
        Files.writeString(sourceFile, original);
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification(
                "src/main/java/demo/Example.java",
                "package demo;\n\nclass Example {\n    int value = 2;\n}\n"
        );

        CodeFileDiffResult result = service.prepareProjectCandidate(
                "src/main/java/demo/Example.java",
                ProjectState.getInstance().getModifications().get("src/main/java/demo/Example.java")
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
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification("src/main/java/demo/Example.java", original);
        service.prepareProjectCandidate("src/main/java/demo/Example.java", original);

        String edited = "package demo;\n\nimport java.util.List;\n\nclass Example { List<String> values; }\n";
        CodeFileDiffResult saved = service.updateCandidate("src/main/java/demo/Example.java", "edit", edited);

        assertFalse(saved.getDiff().isBlank());
        assertEquals(edited, service.authoritativeJavaContent("src/main/java/demo/Example.java").orElseThrow());
        assertEquals(original, Files.readString(sourceFile));
        assertTrue(ProjectState.getInstance().getModifications().get("src/main/java/demo/Example.java").contains("List<String> values"));
    }

    @Test
    void manualJavaCandidateBecomesPendingOnlyAfterItIsEdited(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        String original = "package demo;\n\nclass Example {}\n";
        Files.writeString(sourceFile, original);
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        CodeFileDiffResult opened = service.prepareManualProjectCandidate("src/main/java/demo/Example.java");

        assertTrue(opened.getDiff().isBlank());
        assertTrue(service.pendingModificationKeys().isEmpty());

        service.updateCandidate(
                "src/main/java/demo/Example.java",
                "edit",
                "package demo;\n\nclass Example { int manuallyEdited; }\n"
        );

        assertEquals(java.util.Set.of("src/main/java/demo/Example.java"), service.pendingModificationKeys());
        assertFalse(ProjectState.getInstance().getModifications().isEmpty());
    }

    @Test
    void autoSaveAllowsTemporarilyInvalidJavaUntilCommitValidation(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        String original = "package demo;\n\nclass Example {}\n";
        Files.writeString(sourceFile, original);
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        service.prepareManualProjectCandidate("src/main/java/demo/Example.java");

        String incompleteEdit = "package demo;\n\nclass Example { int value = ; }\n";
        CodeFileDiffResult saved = service.updateCandidate(
                "src/main/java/demo/Example.java",
                "edit",
                incompleteEdit
        );

        assertEquals(incompleteEdit, saved.getModifiedContent());
        assertEquals(incompleteEdit, ProjectState.getInstance().getModifications().get("src/main/java/demo/Example.java"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                service::validateCompleteCandidateSet
        );
        assertTrue(error.getMessage().contains("valid Java source"));
    }

    @Test
    void stagingWritesTemporarilyInvalidJavaLikeGitAdd(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\n\nclass Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        service.prepareManualProjectCandidate("src/main/java/demo/Example.java");
        String incompleteEdit = "package demo;\n\nclass Example { int value = ; }\n";
        service.updateCandidate("src/main/java/demo/Example.java", "edit", incompleteEdit);

        service.materializeCandidate("src/main/java/demo/Example.java");

        assertEquals(incompleteEdit, Files.readString(sourceFile));
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
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification("src/main/java/demo/First.java", "package demo;\n\nclass First { int changed; }\n");
        putModification("src/main/java/demo/Second.java", "package demo;\n\nclass Second { int untouched; }\n");
        service.prepareProjectCandidate("src/main/java/demo/First.java", ProjectState.getInstance().getModifications().get("src/main/java/demo/First.java"));

        service.materializeCandidate("src/main/java/demo/First.java");

        assertTrue(Files.readString(firstFile).contains("int changed"));
        assertEquals("package demo;\n\nclass Second {}\n", Files.readString(secondFile));
    }

    @Test
    void javaGraphCandidateKeysAreReportedRelativeToTheRepository(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\n\nclass Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        service.beginOperation("delete", Set.of("src/main/java/demo/Example.java"));

        assertEquals(
                java.util.List.of("src/main/java/demo/Example.java"),
                service.pendingCandidateProjectPaths()
        );
    }

    @Test
    void completeValidationAcceptsAReviewedJavaCandidate(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\n\nclass Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification(
                "src/main/java/demo/Example.java",
                "package demo;\n\nclass Example { int changed; }\n"
        );
        service.beginOperation("test", ProjectState.getInstance().getModifications().keySet());
        service.prepareProjectCandidate(
                "src/main/java/demo/Example.java",
                ProjectState.getInstance().getModifications().get("src/main/java/demo/Example.java")
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
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification("src/main/java/demo/Example.java", "package demo; class Example {");
        service.beginOperation("test", ProjectState.getInstance().getModifications().keySet());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.prepareProjectCandidate(
                        "src/main/java/demo/Example.java",
                        ProjectState.getInstance().getModifications().get("src/main/java/demo/Example.java")
                )
        );
        assertTrue(error.getMessage().contains("valid Java source"));
    }

    @Test
    void completeValidationRejectsATypeThatDoesNotMatchItsJavaPath(@TempDir Path projectRoot) throws Exception {
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        ProjectState.getInstance().setModifications(new LinkedHashMap<>());
        putModification("src/main/java/cn/edu/pku/Foo.java", "public class Bar {}");
        service.beginOperation("test", ProjectState.getInstance().getModifications().keySet());
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.prepareProjectCandidate(
                        "src/main/java/cn/edu/pku/Foo.java",
                        ProjectState.getInstance().getModifications().get("src/main/java/cn/edu/pku/Foo.java")
                )
        );
        assertTrue(error.getMessage().contains("Foo"));
    }

    @Test
    void pythonNewAndDeletedFileDiffsUseRepositoryPaths(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("removed.py"), "print('old')\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "PYTHON");

        CandidateCodeService service = new CandidateCodeService();
        CodeFileDiffResult added = service.prepareProjectCandidate(
                "added.py",
                "print('new')\n"
        );
        CodeFileDiffResult deleted = service.prepareProjectCandidate(
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

    @Test
    void javaProjectCanReviewAndMaterializeANonJavaAgentCandidate(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("application.yml");
        Path javaSource = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "package demo; class Example {}\n");
        Files.writeString(sourceFile, "feature: disabled\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        ProjectState.getInstance().setModifications(Map.of("application.yml", "feature: enabled\n"));
        CodeFileDiffResult candidate = service.prepareProjectCandidate(
                "application.yml",
                "feature: enabled\n"
        );

        assertEquals("text", candidate.getLanguage());
        assertTrue(candidate.getDiff().contains("feature: enabled"));
        service.materializeCandidate("application.yml");
        assertEquals("feature: enabled\n", Files.readString(sourceFile));
    }

    @Test
    void projectRelativeJavaCandidateMapsBackToTheAnalysisRoot(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo; class Example {}\n");
        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");

        CandidateCodeService service = new CandidateCodeService();
        String projectPath = "src/main/java/demo/Example.java";
        String edited = "package demo; class Example { int changed; }\n";
        ProjectState.getInstance().setModifications(Map.of(projectPath, edited));

        CodeFileDiffResult candidate = service.prepareProjectCandidate(projectPath, edited);

        assertEquals(projectPath, candidate.getKey());
        assertEquals(projectPath, candidate.getPath());
        assertEquals("demo/Example.java", service.javaSourceRelativePath(projectPath).orElseThrow());
        service.materializeCandidate(projectPath);
        assertEquals(edited, Files.readString(sourceFile));
    }

    @Test
    void candidateStateDoesNotLeakBetweenRepositories(@TempDir Path first, @TempDir Path second) {
        ProjectState firstProject = ProjectState.selectWorkspace("candidate-a", 301, first.toString(), "JAVA");
        ProjectState secondProject = ProjectState.selectWorkspace("candidate-b", 302, second.toString(), "JAVA");
        CandidateCodeService service = new CandidateCodeService();

        try (ProjectState.Scope ignored = ProjectState.bindProject("candidate-a", firstProject)) {
            firstProject.setModifications(Map.of("demo/First.java", "class First {}"));
            service.beginOperation("first", firstProject.getModifications().keySet());
            assertEquals(java.util.Set.of("demo/First.java"), service.pendingModificationKeys());
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("candidate-b", secondProject)) {
            secondProject.setModifications(Map.of("demo/Second.java", "class Second {}"));
            service.beginOperation("second", secondProject.getModifications().keySet());
            assertEquals(java.util.Set.of("demo/Second.java"), service.pendingModificationKeys());
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("candidate-a", firstProject)) {
            assertEquals(java.util.Set.of("demo/First.java"), service.pendingModificationKeys());
        }
    }

    private void putModification(String key, String value) {
        Map<String, String> modifications = new LinkedHashMap<>(ProjectState.getInstance().getModifications());
        modifications.put(key, value);
        ProjectState.getInstance().setModifications(modifications);
    }
}
