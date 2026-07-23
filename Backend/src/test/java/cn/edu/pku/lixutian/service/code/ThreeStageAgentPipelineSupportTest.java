package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import cn.edu.pku.lixutian.service.llm.LlmGenerationResult;
import cn.edu.pku.lixutian.service.llm.LlmTokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreeStageAgentPipelineSupportTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":true,"additionalFileList":[
                          {"filename":"settings.yml","recommendReason":"contains the feature switch"}
                        ]}
                        """))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"settings.yml",
                          "action":"rewrite",
                          "plan":"Enable the feature switch.",
                          "note":"Preserve other settings."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                        feature: disabled
                        =======
                        feature: enabled
                        >>>>>>> REPLACE
                        """));

        PythonModifyAgentService service = new PythonModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentRunLogService = new AgentRunLogService(sourceRoot.resolve("agent-logs"));
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals("feature: enabled\n", registry.modifications(context.runId()).get("settings.yml"));
        verify(llmClient, times(4)).streamGenerateWithPromptResult(
                anyString(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(registry.modifications(context.runId()).containsKey("settings.yml"));
        assertEquals(4, registry.snapshot(context.runId()).tokenUsage().calls());
        assertEquals(4, registry.snapshot(context.runId()).tokenUsage().reportedCalls());
        assertEquals(40, registry.snapshot(context.runId()).tokenUsage().inputTokens());
        assertEquals(16, registry.snapshot(context.runId()).tokenUsage().cachedInputTokens());
        assertEquals(8, registry.snapshot(context.runId()).tokenUsage().outputTokens());

        Path runLogs = sourceRoot.resolve("agent-logs").resolve(context.runId());
        assertEquals(runLogs.toAbsolutePath().toString(), registry.snapshot(context.runId()).agentLogPath());
        assertTrue(Files.isRegularFile(runLogs.resolve("run.json")));
        assertTrue(Files.isRegularFile(runLogs.resolve("token-usage.json")));
        assertTrue(Files.isRegularFile(runLogs.resolve("001-agent1/prompt.txt")));
        assertTrue(Files.isRegularFile(runLogs.resolve("001-agent1/response.txt")));
        assertTrue(Files.isRegularFile(runLogs.resolve("004-agent3-settings.yml-attempt-1/metadata.json")));

        JsonNode tokenLog = OBJECT_MAPPER.readTree(runLogs.resolve("token-usage.json").toFile());
        assertEquals(4, tokenLog.path("calls").asInt());
        assertEquals(4, tokenLog.path("reportedCalls").asInt());
        assertEquals(40, tokenLog.path("inputTokens").asLong());
        assertEquals(16, tokenLog.path("cachedInputTokens").asLong());
        assertEquals(24, tokenLog.path("uncachedInputTokens").asLong());
        assertEquals(8, tokenLog.path("outputTokens").asLong());
        assertTrue(tokenLog.path("complete").asBoolean());

        JsonNode callLog = OBJECT_MAPPER.readTree(
                runLogs.resolve("004-agent3-settings.yml-attempt-1/metadata.json").toFile()
        );
        assertEquals("COMPLETED", callLog.path("status").asText());
        assertTrue(callLog.path("usageReported").asBoolean());
        assertEquals(10, callLog.path("usage").path("inputTokens").asLong());
        assertTrue(Files.readString(runLogs.resolve("001-agent1/prompt.txt")).contains("settings.yml"));
        assertTrue(Files.readString(runLogs.resolve("001-agent1/response.txt")).contains("needAdditionalFile"));
    }

    @Test
    void standardJavaProjectReadsAndReturnsProjectRelativePaths(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Feature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class Feature { int value = 1; }\n");
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(96);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify",
                "Change the value",
                "Value is one",
                "Java graph context",
                "pom.xml\nsrc/main/java/demo/Feature.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                9,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"src/main/java/demo/Feature.java",
                          "action":"rewrite",
                          "plan":"Change value to two.",
                          "note":"Preserve the package and type."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                        int value = 1;
                        =======
                        int value = 2;
                        >>>>>>> REPLACE
                        """));

        ModifyAgentService service = new ModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        String candidate = registry.modifications(context.runId())
                .get("src/main/java/demo/Feature.java");
        assertTrue(candidate.contains("package demo;"));
        assertTrue(candidate.contains("int value = 2;"));
        assertTrue(Files.readString(sourceFile).contains("int value = 1;"));
    }

    @Test
    void deleteAgentProducesCandidateWhilePreservingProtectedSharedSymbol(@TempDir Path sourceRoot)
            throws Exception {
        String original = "def shared():\n    return 'shared'\n\ndef owned():\n    return 'owned'\n";
        Files.writeString(sourceRoot.resolve("feature.py"), original);
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "PYTHON");
        project.setRepoId(92);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete",
                "Delete the selected feature",
                "Selected feature",
                "PROTECTED_SYMBOL: package.feature.shared()\n",
                "feature.py",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                8,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"feature.py",
                          "action":"rewrite",
                          "plan":"Remove only the feature-owned function.",
                          "note":"Preserve shared()."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                        def owned():
                            return 'owned'
                        =======

                        >>>>>>> REPLACE
                        """));

        PythonModifyAgentService service = new PythonModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runDeletePipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        String candidate = registry.modifications(context.runId()).get("feature.py");
        assertTrue(candidate.contains("def shared()"));
        assertFalse(candidate.contains("def owned()"));
        assertEquals(original, Files.readString(sourceRoot.resolve("feature.py")));
    }

    @Test
    void deleteAgentRejectsRemovalOfProtectedSharedSymbol(@TempDir Path sourceRoot) throws Exception {
        Files.writeString(
                sourceRoot.resolve("feature.py"),
                "def shared():\n    return 'shared'\n\ndef owned():\n    return 'owned'\n"
        );
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "PYTHON");
        project.setRepoId(93);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete",
                "Delete the selected feature",
                "Selected feature",
                "PROTECTED_SYMBOL: package.feature.shared()\n",
                "feature.py",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                9,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"feature.py",
                          "action":"rewrite",
                          "plan":"Remove shared code.",
                          "note":"Unsafe test output."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                        def shared():
                            return 'shared'
                        =======

                        >>>>>>> REPLACE
                        """));

        PythonModifyAgentService service = new PythonModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runDeletePipeline(context.runId(), "model");
        awaitFailed(registry, context.runId());

        assertTrue(registry.snapshot(context.runId()).failureMessage().contains("protected shared symbol"));
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
    }

    @Test
    void javaDeleteRejectsRemovingProtectedDefinitionEvenWhenSameNamedCallRemains(@TempDir Path sourceRoot)
            throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/Feature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;

                public class Feature {
                    void shared() {}
                    void callShared() { shared(); }
                    void owned() {}
                }
                """);
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(94);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete",
                "Delete the selected feature",
                "Selected feature",
                "PROTECTED_SYMBOL: demo.Feature.shared()\n",
                "demo/Feature.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                10,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"demo/Feature.java",
                          "action":"rewrite",
                          "plan":"Remove shared code.",
                          "note":"Unsafe test output."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                            void shared() {}
                        =======

                        >>>>>>> REPLACE
                        """));

        DeleteAgentService service = new DeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitFailed(registry, context.runId());

        assertTrue(registry.snapshot(context.runId()).failureMessage().contains("protected shared symbol"));
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
    }

    @Test
    void deleteReplansMissingFilesAndAcceptsAnExplicitNoChangeResult(@TempDir Path sourceRoot)
            throws Exception {
        Path api = sourceRoot.resolve("demo/Api.java");
        Path caller = sourceRoot.resolve("demo/Caller.java");
        Files.createDirectories(api.getParent());
        Files.writeString(api, "package demo;\npublic interface Api {}\n");
        Files.writeString(caller, "package demo;\nclass Caller { void call(Api api) { api.removed(); } }\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(95);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete",
                "Delete removed",
                "Removed feature",
                "FEATURE_SYMBOL: demo.Api.removed()\n",
                "demo/Api.java\ndemo/Caller.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                11,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"demo/Mapper.xml",
                          "action":"rewrite",
                          "plan":"Remove an optional mapping.",
                          "note":"Only if present."
                        }]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"demo/Api.java",
                          "action":"rewrite",
                          "plan":"Remove removed() if it still exists.",
                          "note":"The live source tree is authoritative."
                        }]}
                        """))
                .thenReturn(generation("NO_CHANGES_REQUIRED"));

        DeleteAgentService service = new DeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertTrue(registry.modifications(context.runId()).isEmpty());
        assertEquals("package demo;\npublic interface Api {}\n", Files.readString(api));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(4)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(prompts.getAllValues().get(0).contains("FEATURE_SYMBOL_STATUS: MISSING demo.Api.removed()"));
        assertTrue(prompts.getAllValues().get(0).contains("LIVE_REFERENCE_FILE: demo/Caller.java"));
        assertTrue(prompts.getAllValues().get(2).contains("demo/Mapper.xml"));
    }

    private LlmGenerationResult generation(String content) {
        return new LlmGenerationResult(content, new LlmTokenUsage(10, 4, 2, 0, 12));
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

    private void awaitFailed(AgentRunRegistry registry, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            AgentRunRegistry.Status status = registry.status(runId);
            if (status == AgentRunRegistry.Status.FAILED) {
                return;
            }
            if (status == AgentRunRegistry.Status.COMPLETED) {
                throw new AssertionError("Delete Agent unexpectedly completed.");
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Delete Agent did not fail in time.");
    }
}
