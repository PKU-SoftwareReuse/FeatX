package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreeStageAgentPipelineSupportTest {
    private ExecutorService executor;

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.shutdownNow();
        }
        ProjectState.getInstance().setModifications(java.util.Map.of());
        ProjectState.getInstance().setRepoId(null);
    }

    @Test
    void pythonUsesJavaBaselineRecheckAndCanModifyConfiguration(@TempDir Path sourceRoot) throws Exception {
        Files.writeString(sourceRoot.resolve("main.py"), "def main():\n    return True\n");
        Files.writeString(sourceRoot.resolve("settings.yml"), "feature: disabled\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "PYTHON");
        project.setRepoId(91);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify-python",
                "Enable the feature",
                "Feature is disabled",
                "focused Python graph context",
                "main.py\nsettings.yml",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                7,
                null,
                List.of()
        );

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPrompt(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn("""
                        {"needAdditionalFile":true,"additionalFileList":[
                          {"filename":"settings.yml","recommendReason":"contains the feature switch"}
                        ]}
                        """)
                .thenReturn("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """)
                .thenReturn("""
                        {"modifiedFileList":[{
                          "filename":"settings.yml",
                          "action":"rewrite",
                          "plan":"Enable the feature switch.",
                          "note":"Preserve other settings."
                        }]}
                        """)
                .thenReturn("""
                        <<<<<<< SEARCH
                        feature: disabled
                        =======
                        feature: enabled
                        >>>>>>> REPLACE
                        """);

        PythonModifyAgentService service = new PythonModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals("feature: enabled\n", registry.modifications(context.runId()).get("settings.yml"));
        verify(llmClient, times(4)).streamGenerateWithPrompt(
                anyString(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(registry.modifications(context.runId()).containsKey("settings.yml"));
    }

    private void awaitCompleted(AgentRunRegistry registry, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            AgentRunRegistry.Status status = registry.status(runId);
            if (status == AgentRunRegistry.Status.COMPLETED) {
                return;
            }
            if (status == AgentRunRegistry.Status.FAILED) {
                throw new AssertionError("Agent pipeline failed: " + registry.snapshot(runId).failureMessage());
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Agent pipeline did not complete in time.");
    }
}
