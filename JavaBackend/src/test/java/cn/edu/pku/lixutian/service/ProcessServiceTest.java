package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.SKG;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessServiceTest {
    private final ProcessService processService = new ProcessService();
    private Integer repositoryId;

    @AfterEach
    void clearGraphState() {
        if (repositoryId != null) {
            processService.clearRepository(repositoryId);
        }
    }

    @Test
    void confirmedJavaRefreshMirrorsOnlyJavaFilesAndRebuildsFromPreprocess2(@TempDir Path projectRoot)
            throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/com/acme/App.java");
        Path retainedText = projectRoot.resolve("src/main/java/com/acme/template.txt");
        Path resource = projectRoot.resolve("src/main/resources/application.yml");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(resource.getParent());
        String original = "package com.acme; public class App { int version = 1; }\n";
        Files.writeString(sourceFile, original);
        Files.writeString(retainedText, "keep me\n");
        Files.writeString(resource, "feature: enabled\n");

        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        repositoryId = 8801;
        project.setRepoId(repositoryId);

        processService.rebuildAfterConfirmedSourceChange();

        Path preprocess1 = Path.of(project.getPreprocess1Path(), "com/acme/App.java");
        Path delombok = Path.of(project.getDelombokPath(), "com/acme/App.java");
        Path preprocess2 = Path.of(project.getPreprocess2Path(), "com/acme/App.java");
        assertTrue(Files.isRegularFile(preprocess1));
        assertTrue(Files.isRegularFile(delombok));
        assertTrue(Files.isRegularFile(preprocess2));
        assertFalse(Files.exists(Path.of(project.getPreprocess2Path(), "com/acme/template.txt")));
        assertFalse(Files.exists(Path.of(project.getPreprocess2Path(), "application.yml")));
        assertTrue(SKG.getInstance().isBuilt());
        assertTrue(Files.readString(sourceFile).contains("version = 1"));

        Files.writeString(sourceFile, "package com.acme; public class App { int version = 2; }\n");
        processService.rebuildAfterConfirmedSourceChange();

        assertTrue(Files.readString(preprocess2).contains("version = 2"));
        assertFalse(Files.readString(preprocess2).contains("version = 1"));
        assertTrue(Files.readString(retainedText).contains("keep me"));
        assertTrue(Files.readString(resource).contains("feature: enabled"));
    }
}
