package com.mycode.service.code;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.AgentTokenUsageResult;
import com.mycode.service.llm.LlmTokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRegistryTest {
    @AfterEach
    void clearGlobalCandidateMap() {
        ProjectState.getInstance().setModifications(Map.of());
    }

    @Test
    void runIdPreventsASecondOperationFromOverwritingAnActiveOne(@TempDir Path projectRoot) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(12);
        AgentRunRegistry registry = new AgentRunRegistry();

        AgentRunContext context = prepare(registry, project);

        assertThrows(IllegalStateException.class, () -> prepare(registry, project));
        registry.claim(context.runId());
        registry.complete(context.runId(), Map.of("cn/edu/pku/Foo.java", "class Foo {}"));

        assertEquals(
                "class Foo {}",
                registry.modifications(context.runId()).get("cn/edu/pku/Foo.java")
        );
        assertThrows(IllegalStateException.class, () -> registry.claim(context.runId()));
    }

    @Test
    void completedRunIsRejectedAfterProjectSelectionChanges(@TempDir Path first, @TempDir Path second) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(first.toString(), "JAVA");
        project.setRepoId(1);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = prepare(registry, project);
        registry.claim(context.runId());
        registry.complete(context.runId(), Map.of());

        project.setProjectPath(second.toString(), "JAVA");
        project.setRepoId(2);

        assertThrows(IllegalStateException.class, () -> registry.requireCompleted(context.runId()));
    }

    @Test
    void clearingARunningOperationInvokesItsCancellation(@TempDir Path projectRoot) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(3);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = prepare(registry, project);
        AtomicBoolean cancelled = new AtomicBoolean();

        registry.claim(context.runId());
        registry.registerCancellation(context.runId(), () -> cancelled.set(true));
        registry.clear();

        assertTrue(cancelled.get());
    }

    @Test
    void runKeepsBufferingEventsWithoutABrowserSubscriber(@TempDir Path projectRoot) throws Exception {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(4);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = prepare(registry, project);

        registry.selectModel(context.runId(), "test-model");
        registry.claim(context.runId());
        AgentRunEventStream events = registry.eventStream(context.runId());
        events.send("status", "Stage I\n");
        events.send("delta", "{\"needAdditionalFile\":false}");
        registry.complete(context.runId(), Map.of("cn/edu/pku/Foo.java", "class Foo {}"));
        events.send("completed", "Done");

        assertEquals(AgentRunRegistry.Status.COMPLETED, registry.status(context.runId()));
        assertEquals("test-model", registry.snapshot(context.runId()).model());
        assertEquals("new requirement", registry.snapshot(context.runId()).request());
        assertEquals("class Foo {}", registry.modifications(context.runId()).get("cn/edu/pku/Foo.java"));
    }

    @Test
    void aggregatesReportedUsageAndKeepsMissingUsageVisible(@TempDir Path projectRoot) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(41);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = prepare(registry, project);
        registry.claim(context.runId());

        assertEquals(1, registry.beginLlmCall(context.runId()));
        registry.completeLlmCall(context.runId(), new LlmTokenUsage(100, 60, 20, 5, 120));
        assertEquals(2, registry.beginLlmCall(context.runId()));
        registry.completeLlmCall(context.runId(), null);

        AgentTokenUsageResult usage = registry.tokenUsage(context.runId());
        assertEquals(2, usage.calls());
        assertEquals(1, usage.reportedCalls());
        assertEquals(100, usage.inputTokens());
        assertEquals(60, usage.cachedInputTokens());
        assertEquals(40, usage.uncachedInputTokens());
        assertEquals(20, usage.outputTokens());
        assertEquals(5, usage.reasoningOutputTokens());
        assertEquals(120, usage.totalTokens());
        assertTrue(!usage.complete());
    }

    @Test
    void preparationCallsAreIncludedInRunUsage(@TempDir Path projectRoot) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(42);
        AgentRunRegistry registry = new AgentRunRegistry();
        String runId = registry.reservePreparation();

        assertEquals(1, registry.beginLlmCall(runId));
        registry.completeLlmCall(runId, new LlmTokenUsage(30, 10, 5, 0, 35));

        AgentTokenUsageResult usage = registry.tokenUsage(runId);
        assertEquals(1, usage.calls());
        assertEquals(1, usage.reportedCalls());
        assertEquals(35, usage.totalTokens());
        assertTrue(usage.complete());
    }

    @Test
    void differentRepositoriesCanPrepareConcurrently(@TempDir Path first, @TempDir Path second) {
        ProjectState firstProject = ProjectState.selectWorkspace("workspace-a", 101, first.toString(), "JAVA");
        ProjectState secondProject = ProjectState.selectWorkspace("workspace-b", 102, second.toString(), "JAVA");
        AgentRunRegistry registry = new AgentRunRegistry();

        AgentRunContext firstRun;
        try (ProjectState.Scope ignored = ProjectState.bindProject("workspace-a", firstProject)) {
            firstRun = prepare(registry, firstProject);
        }
        AgentRunContext secondRun;
        try (ProjectState.Scope ignored = ProjectState.bindProject("workspace-b", secondProject)) {
            secondRun = prepare(registry, secondProject);
        }

        assertNotEquals(firstRun.runId(), secondRun.runId());
        assertEquals(AgentRunRegistry.Status.PREPARED, registry.status(firstRun.runId()));
        assertEquals(AgentRunRegistry.Status.PREPARED, registry.status(secondRun.runId()));
        assertTrue(registry.hasActiveOperation(101));
        assertTrue(registry.hasActiveOperation(102));
    }

    @Test
    void preparingRunImmediatelyBlocksTheSameRepository(@TempDir Path projectRoot) {
        ProjectState firstWorkspace = ProjectState.selectWorkspace(
                "same-project-a", 103, projectRoot.toString(), "JAVA"
        );
        ProjectState secondWorkspace = ProjectState.selectWorkspace(
                "same-project-b", 103, projectRoot.toString(), "JAVA"
        );
        AgentRunRegistry registry = new AgentRunRegistry();

        String runId;
        try (ProjectState.Scope ignored = ProjectState.bindProject("same-project-a", firstWorkspace)) {
            runId = registry.reservePreparation();
        }

        try (ProjectState.Scope ignored = ProjectState.bindProject("same-project-b", secondWorkspace)) {
            assertThrows(IllegalStateException.class, registry::reservePreparation);
        }
        assertEquals(AgentRunRegistry.Status.PREPARING, registry.status(runId));
    }

    @Test
    void concurrentReservationsForTheSameRepositoryHaveExactlyOneWinner(@TempDir Path projectRoot)
            throws Exception {
        ProjectState firstWorkspace = ProjectState.selectWorkspace(
                "race-a", 104, projectRoot.toString(), "JAVA"
        );
        ProjectState secondWorkspace = ProjectState.selectWorkspace(
                "race-b", 104, projectRoot.toString(), "JAVA"
        );
        AgentRunRegistry registry = new AgentRunRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(
                    () -> reserveAfterSignal(registry, "race-a", firstWorkspace, start)
            );
            Future<Boolean> second = executor.submit(
                    () -> reserveAfterSignal(registry, "race-b", secondWorkspace, start)
            );
            start.countDown();

            int winners = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertTrue(registry.hasActiveOperation(104));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedRunCannotBeReusedForAnotherOperation(@TempDir Path projectRoot) {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(105);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = prepare(registry, project);
        registry.claim(context.runId());
        registry.complete(context.runId(), Map.of("cn/edu/pku/Foo.java", "class Foo {}"));

        assertEquals(context, registry.requireCompletedOperation(context.runId(), "edit"));
        assertThrows(
                IllegalStateException.class,
                () -> registry.requireCompletedOperation(context.runId(), "delete")
        );
        assertThrows(IllegalArgumentException.class, () -> registry.requireCompletedIfActive(null));
    }

    private boolean reserveAfterSignal(
            AgentRunRegistry registry,
            String workspaceId,
            ProjectState project,
            CountDownLatch start
    ) throws InterruptedException {
        start.await();
        try (ProjectState.Scope ignored = ProjectState.bindProject(workspaceId, project)) {
            try {
                registry.reservePreparation();
                return true;
            } catch (IllegalStateException conflict) {
                return false;
            }
        }
    }

    private AgentRunContext prepare(AgentRunRegistry registry, ProjectState project) {
        return registry.prepare(
                AgentRunMode.JAVA_MODIFY.id(),
                "new requirement",
                "old requirement",
                "context",
                "cn/edu/pku/Foo.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                7,
                null,
                List.of()
        );
    }
}
