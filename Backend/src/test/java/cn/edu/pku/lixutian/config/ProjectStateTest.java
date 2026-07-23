package cn.edu.pku.lixutian.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStateTest {
    @Test
    void standardJavaProjectSeparatesOriginalAndDerivedRoots(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/com/acme/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package com.acme; class App {}\n");

        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");

        assertEquals(projectRoot.toString(), project.getProjectPath());
        assertEquals(projectRoot.resolve("src/main/java").toString(), project.getSrcPath());
        assertEquals(projectRoot.resolve("preprocess1/main/java").toString(), project.getPreprocess1Path());
        assertEquals(projectRoot.resolve("delombok/main/java").toString(), project.getDelombokPath());
        assertEquals(projectRoot.resolve("preprocess2/main/java").toString(), project.getPreprocess2Path());
    }

    @Test
    void unboundThreadsDoNotShareAProcessWideProjectState() throws Exception {
        ProjectState.clearWorkspace(ProjectState.currentWorkspaceId());
        AtomicReference<ProjectState> first = new AtomicReference<>();
        AtomicReference<ProjectState> second = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(2);

        Thread firstThread = new Thread(() -> {
            first.set(ProjectState.getInstance());
            ready.countDown();
        });
        Thread secondThread = new Thread(() -> {
            second.set(ProjectState.getInstance());
            ready.countDown();
        });
        firstThread.start();
        secondThread.start();

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        firstThread.join();
        secondThread.join();
        assertNotSame(first.get(), second.get());
    }

    @Test
    void workspacesResolveIndependentRepositoriesWhileSharingTheSameRepositoryRuntime(
            @TempDir Path firstRoot,
            @TempDir Path secondRoot
    ) {
        ProjectState first = ProjectState.selectWorkspace("state-a", 601, firstRoot.toString(), "JAVA");
        ProjectState second = ProjectState.selectWorkspace("state-b", 602, secondRoot.toString(), "PYTHON");
        ProjectState sameFirstRepository = ProjectState.selectWorkspace(
                "state-c", 601, firstRoot.toString(), "JAVA"
        );

        assertNotSame(first, second);
        assertSame(first, sameFirstRepository);
        try (ProjectState.Scope ignored = ProjectState.bindWorkspace("state-a")) {
            assertSame(first, ProjectState.getInstance());
            assertEquals(601, ProjectState.currentRepositoryKey());
        }
        try (ProjectState.Scope ignored = ProjectState.bindWorkspace("state-b")) {
            assertSame(second, ProjectState.getInstance());
            assertEquals(602, ProjectState.currentRepositoryKey());
        }
    }

    @Test
    void capturedContextRestoresTheRepositoryOnBackgroundThreads(@TempDir Path projectRoot) throws Exception {
        ProjectState project = ProjectState.selectWorkspace(
                "background-state", 603, projectRoot.toString(), "JAVA"
        );
        ProjectState.CapturedContext captured;
        try (ProjectState.Scope ignored = ProjectState.bindProject("background-state", project)) {
            captured = ProjectState.capture();
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> repository = executor.submit(
                    () -> captured.call(ProjectState::currentRepositoryKey)
            );
            assertEquals(603, repository.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
