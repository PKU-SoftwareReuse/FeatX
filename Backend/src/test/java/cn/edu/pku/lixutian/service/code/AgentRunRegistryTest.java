package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRegistryTest {
    @AfterEach
    void clearGlobalCandidateMap() {
        AgentService.modificationMap = null;
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

    private AgentRunContext prepare(AgentRunRegistry registry, ProjectState project) {
        return registry.prepare(
                "modify",
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
