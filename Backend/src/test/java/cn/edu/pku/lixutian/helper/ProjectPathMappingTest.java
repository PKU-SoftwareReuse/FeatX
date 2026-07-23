package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.config.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathMappingTest {
    @Test
    void mapsBetweenRepositoryAndConventionalJavaSourceRoots(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/com/acme/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package com.acme; class App {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");

        assertEquals(
                "src/main/java/com/acme/App.java",
                ProjectPathMapping.sourceRelativeToProject(project, "com/acme/App.java")
        );
        assertEquals(
                "com/acme/App.java",
                ProjectPathMapping.projectRelativeToSource(
                        project,
                        "src/main/java/com/acme/App.java"
                ).orElseThrow()
        );
        assertTrue(ProjectPathMapping.projectRelativeToSource(project, "pom.xml").isEmpty());
    }
}
