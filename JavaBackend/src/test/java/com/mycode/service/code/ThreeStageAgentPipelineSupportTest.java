package com.mycode.service.code;

import com.mycode.config.ProjectState;
import com.mycode.service.llm.LlmClient;
import com.mycode.service.llm.LlmGenerationResult;
import com.mycode.service.llm.LlmTokenUsage;
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
    void agent1RefinesContextForAtMostFiveRounds(@TempDir Path projectRoot) throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java/demo");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Target.java"),
                "package demo;\npublic class Target { int obsolete = 1; }\n");
        Files.writeString(sourceRoot.resolve("Noise.java"), "package demo; class Noise {}\n");
        Files.writeString(sourceRoot.resolve("Keep.java"), "package demo; class Keep {}\n");
        for (int index = 1; index <= 5; index++) {
            Files.writeString(
                    sourceRoot.resolve("Extra" + index + ".java"),
                    "package demo; class Extra" + index + " {}\n"
            );
        }
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(97);

        String coreContext = """
                ## Current Feature Complete CodeMap
                mandatory target method

                ## Java Reasoning Context

                ### File: src/main/java/demo/Noise.java
                NOISE_CONTEXT_MARKER

                ### File: src/main/java/demo/Keep.java
                KEEP_CONTEXT_MARKER

                ## Deterministic Java Static Deletion Context
                MANDATORY_STATIC_DELETE_MARKER

                ## FeatX Deterministic Delete Safety Boundary
                FEATURE_SYMBOL: demo.Target.obsolete()
                """;
        String allFiles = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> "src/main/java/demo/Extra" + index + ".java")
                .collect(java.util.stream.Collectors.joining("\n", "src/main/java/demo/Target.java\n"
                        + "src/main/java/demo/Noise.java\nsrc/main/java/demo/Keep.java\n", ""));
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete",
                "Delete obsolete field",
                "Obsolete field exists",
                coreContext,
                allFiles,
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
                .thenReturn(generation(contextAdjustment("Extra1.java", "Noise.java")))
                .thenReturn(generation(contextAdjustment("Extra2.java", "Extra1.java")))
                .thenReturn(generation(contextAdjustment("Extra3.java", null)))
                .thenReturn(generation(contextAdjustment("Extra4.java", "Keep.java")))
                .thenReturn(generation(contextAdjustment("Extra5.java", null)))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"src/main/java/demo/Target.java",
                          "action":"rewrite",
                          "plan":"Remove the obsolete field.",
                          "note":"Preserve the type."
                        }]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                         int obsolete = 1;
                        =======

                        >>>>>>> REPLACE
                        """));

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(7)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        String agent2Prompt = prompts.getAllValues().get(5);
        assertTrue(agent2Prompt.contains("MANDATORY_STATIC_DELETE_MARKER"));
        assertTrue(agent2Prompt.contains("Extra2"));
        assertTrue(agent2Prompt.contains("Extra3"));
        assertTrue(agent2Prompt.contains("Extra4"));
        assertTrue(agent2Prompt.contains("Extra5"));
        assertFalse(agent2Prompt.contains("NOISE_CONTEXT_MARKER"));
        assertFalse(agent2Prompt.contains("KEEP_CONTEXT_MARKER"));
        assertFalse(agent2Prompt.contains("class Extra1"));
        assertTrue(registry.modifications(context.runId())
                .get("src/main/java/demo/Target.java")
                .contains("public class Target"));
        assertFalse(registry.modifications(context.runId())
                .get("src/main/java/demo/Target.java")
                .contains("obsolete"));
    }

    @Test
    void agent1AcceptsMoreThanTwelveContextAdditionsAndRemovals(@TempDir Path projectRoot) throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java/demo");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Target.java"), "package demo;\npublic class Target {}\n");

        StringBuilder coreContext = new StringBuilder("## Java Reasoning Context\n\n");
        StringBuilder allFiles = new StringBuilder("src/main/java/demo/Target.java\n");
        var adjustment = OBJECT_MAPPER.createObjectNode();
        adjustment.put("needAdditionalFile", true);
        var additions = adjustment.putArray("additionalFileList");
        var removals = adjustment.putArray("removeContextFileList");
        for (int index = 1; index <= 13; index++) {
            String addedFile = "src/main/java/demo/AddedContext" + index + ".java";
            String optionalFile = "src/main/java/demo/OptionalContext" + index + ".java";
            Files.writeString(
                    projectRoot.resolve(addedFile),
                    "package demo; class AddedContext" + index + " { // ADDED_CONTEXT_MARKER_" + index + "\n}\n"
            );
            Files.writeString(
                    projectRoot.resolve(optionalFile),
                    "package demo; class OptionalContext" + index + " {}\n"
            );
            allFiles.append(addedFile).append('\n').append(optionalFile).append('\n');
            coreContext.append("### File: ").append(optionalFile).append('\n')
                    .append("OPTIONAL_CONTEXT_MARKER_").append(index).append("\n\n");
            additions.addObject()
                    .put("filename", addedFile)
                    .put("recommendReason", "需要完整依赖上下文");
            removals.addObject()
                    .put("filename", optionalFile)
                    .put("recommendReason", "与本次修改无关");
        }

        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(106);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify-many-context-files",
                "确认目标类保持不变",
                "目标类已经存在",
                coreContext.toString(),
                allFiles.toString(),
                AgentLanguage.CN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                21,
                null,
                List.of()
        );

        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation(adjustment.toString()))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[],"removeContextFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{
                          "filename":"src/main/java/demo/Target.java",
                          "action":"rewrite",
                          "plan":"确认文件已经满足需求。",
                          "note":"无需引入额外行为。"
                        }]}
                        """))
                .thenReturn(generation("NO_CHANGES_REQUIRED"));

        JavaModifyAgentService service = new JavaModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(4)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(prompts.getAllValues().get(0).contains("你是负责 Java 项目的 Agent1"));
        assertTrue(prompts.getAllValues().get(0).contains("OptionalContext13.java"));
        assertTrue(prompts.getAllValues().get(1).contains("ADDED_CONTEXT_MARKER_13"));
        assertFalse(prompts.getAllValues().get(1).contains("OPTIONAL_CONTEXT_MARKER_13"));
        assertTrue(prompts.getAllValues().get(2).contains("你是负责 Java 项目的 Agent2"));
        assertTrue(prompts.getAllValues().get(3).contains("你是负责 Java 项目的 Agent3"));
        assertTrue(registry.modifications(context.runId()).isEmpty());
    }

    @Test
    void agent2CanReturnAnEmptyModificationPlan(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Target.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class Target {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(107);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify-empty-plan",
                "确认当前实现已经满足需求",
                "当前实现",
                "目标类上下文",
                "src/main/java/demo/Target.java",
                AgentLanguage.CN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                22,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[]}
                        """));

        JavaModifyAgentService service = new JavaModifyAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(prompts.getAllValues().get(1).contains("可以返回空的"));
        assertTrue(registry.modifications(context.runId()).isEmpty());
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

        JavaModifyAgentService service = new JavaModifyAgentService();
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

        PythonDeleteAgentService service = new PythonDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
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

        PythonDeleteAgentService service = new PythonDeleteAgentService();
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

        JavaDeleteAgentService service = new JavaDeleteAgentService();
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
    void agent3CanSkipOneUnsafeFileAndContinueWithRemainingFiles(@TempDir Path sourceRoot)
            throws Exception {
        Path packageRoot = sourceRoot.resolve("demo");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("A.java"),
                "package demo;\nclass A { void owned() {} }\n");
        Files.writeString(packageRoot.resolve("B.java"),
                "package demo;\nclass B { void shared() {} void owned() {} }\n");
        Files.writeString(packageRoot.resolve("C.java"),
                "package demo;\nclass C { void owned() {} }\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(98);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete-skip-unsafe-file",
                "Delete the selected feature",
                "Selected feature",
                "PROTECTED_SYMBOL: demo.B.shared()\n",
                "demo/A.java\ndemo/B.java\ndemo/C.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                12,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[
                          {"filename":"demo/A.java","action":"rewrite","plan":"Remove owned().","note":""},
                          {"filename":"demo/B.java","action":"rewrite","plan":"Remove feature code.","note":"Preserve shared()."},
                          {"filename":"demo/C.java","action":"rewrite","plan":"Remove owned().","note":""}
                        ]}
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                         void owned() {}
                        =======

                        >>>>>>> REPLACE
                        """))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                         void shared() {}
                        =======

                        >>>>>>> REPLACE
                        """))
                .thenReturn(generation("SKIP_FILE_SAFELY"))
                .thenReturn(generation("""
                        <<<<<<< SEARCH
                         void owned() {}
                        =======

                        >>>>>>> REPLACE
                        """));

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertTrue(registry.modifications(context.runId()).containsKey("demo/A.java"));
        assertFalse(registry.modifications(context.runId()).containsKey("demo/B.java"));
        assertTrue(registry.modifications(context.runId()).containsKey("demo/C.java"));
        assertEquals("package demo;\nclass B { void shared() {} void owned() {} }\n",
                Files.readString(packageRoot.resolve("B.java")));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(6)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(prompts.getAllValues().get(4).contains("只返回 SKIP_FILE_SAFELY"));
        assertTrue(prompts.getAllValues().get(5).contains("目标文件：demo/C.java"));
    }

    @Test
    void javaDeleteAcceptsAgentDirectedWholeFileDeletionWithoutAllowlist(@TempDir Path sourceRoot)
            throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/DedicatedFeature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class DedicatedFeature {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(97);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete-java-file",
                "Delete the selected feature",
                "Selected feature",
                "FEATURE_SYMBOL: demo.DedicatedFeature\n",
                "demo/DedicatedFeature.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                12,
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
                          "filename":"demo/DedicatedFeature.java",
                          "action":"delete",
                          "plan":"Delete the dedicated feature file.",
                          "note":"The file belongs only to the selected feature."
                        }]}
                        """));

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals(
                AgentService.DELETE_FILE_SENTINEL,
                registry.modifications(context.runId()).get("demo/DedicatedFeature.java")
        );
        assertTrue(Files.isRegularFile(sourceFile));
    }

    @Test
    void javaDeleteStillRejectsWholeFileDeletionContainingProtectedSymbol(@TempDir Path sourceRoot)
            throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/SharedFeature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                public class SharedFeature {
                    void keep() {}
                }
                """);
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(99);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete-java-protected-file",
                "Delete the selected feature",
                "Selected feature",
                "PROTECTED_SYMBOL: demo.SharedFeature.keep()\n",
                "demo/SharedFeature.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                14,
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
                          "filename":"demo/SharedFeature.java",
                          "action":"delete",
                          "plan":"Delete the whole file.",
                          "note":"Unsafe output containing a protected symbol."
                        }]}
                        """));

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitFailed(registry, context.runId());

        assertTrue(registry.snapshot(context.runId()).failureMessage().contains("protected shared symbol"));
        assertTrue(Files.isRegularFile(sourceFile));
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
    }

    @Test
    void pythonDeleteStillRejectsWholeFileDeletionWithoutDeterministicAllowlist(@TempDir Path sourceRoot)
            throws Exception {
        Path sourceFile = sourceRoot.resolve("feature.py");
        Files.writeString(sourceFile, "def feature():\n    return True\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "PYTHON");
        project.setRepoId(98);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete-python-file",
                "Delete the selected feature",
                "Selected feature",
                "FEATURE_SYMBOL: feature.feature()\n",
                "feature.py",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                13,
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
                          "action":"delete",
                          "plan":"Delete the whole module.",
                          "note":"Unsafe output without a deterministic allowlist."
                        }]}
                        """));

        PythonDeleteAgentService service = new PythonDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitFailed(registry, context.runId());

        assertTrue(registry.snapshot(context.runId()).failureMessage().contains("deterministic AST boundary"));
        assertTrue(Files.isRegularFile(sourceFile));
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
    }

    @Test
    void agent2RepairAcceptsValidatedBareFileArray(@TempDir Path sourceRoot) throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/DedicatedFeature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class DedicatedFeature {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(100);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "delete-agent2-array-repair",
                "Delete the selected feature",
                "Selected feature",
                "FEATURE_SYMBOL: demo.DedicatedFeature\n",
                "demo/DedicatedFeature.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                15,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(generation("""
                        {"needAdditionalFile":false,"additionalFileList":[]}
                        """))
                .thenReturn(generation("""
                        {"modifiedFileList":[{"filename":"demo/DedicatedFeature.java","
                        action":"delete","plan":"Delete the "quoted" feature file."}]}
                        """))
                .thenReturn(generation("""
                        [{
                          "filename":"demo/DedicatedFeature.java",
                          "action":"delete",
                          "plan":"Delete the dedicated feature file.",
                          "note":"The repair returned a bare but otherwise valid array."
                        }]
                        """));

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals(
                AgentService.DELETE_FILE_SENTINEL,
                registry.modifications(context.runId()).get("demo/DedicatedFeature.java")
        );
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(3)).streamGenerateWithPromptResult(
                prompts.capture(),
                any(AgentEventSink.class),
                eq("model")
        );
        assertTrue(prompts.getAllValues().get(2).contains("每个双引号都必须使用反斜杠转义"));
        assertTrue(Files.isRegularFile(sourceFile));
    }

    @Test
    void agent1CanRecoverOnItsFifthRetry(@TempDir Path sourceRoot) throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/Agent1Retry.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class Agent1Retry {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(101);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "agent1-fifth-retry",
                "Delete the selected feature",
                "Selected feature",
                "FEATURE_SYMBOL: demo.Agent1Retry\n",
                "demo/Agent1Retry.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                16,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenThrow(new RuntimeException("provider unavailable"))
                .thenReturn(
                        generation("{}"),
                        generation("{}"),
                        generation("{}"),
                        generation("{}"),
                        generation("{\"needAdditionalFile\":false,\"additionalFileList\":[]}"),
                        generation("""
                                {"modifiedFileList":[{
                                  "filename":"demo/Agent1Retry.java",
                                  "action":"delete",
                                  "plan":"Delete the dedicated file.",
                                  "note":"Agent1 recovered on retry five."
                                }]}
                                """)
                );

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals(
                AgentService.DELETE_FILE_SENTINEL,
                registry.modifications(context.runId()).get("demo/Agent1Retry.java")
        );
        verify(llmClient, times(7)).streamGenerateWithPromptResult(
                anyString(),
                any(AgentEventSink.class),
                eq("model")
        );
    }

    @Test
    void agent2CanRecoverOnItsFifthRetry(@TempDir Path sourceRoot) throws Exception {
        Path sourceFile = sourceRoot.resolve("demo/Agent2Retry.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class Agent2Retry {}\n");
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(sourceRoot.toString(), "JAVA");
        project.setRepoId(102);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "agent2-fifth-retry",
                "Delete the selected feature",
                "Selected feature",
                "FEATURE_SYMBOL: demo.Agent2Retry\n",
                "demo/Agent2Retry.java",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                17,
                null,
                List.of()
        );
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model")))
                .thenReturn(
                        generation("{\"needAdditionalFile\":false,\"additionalFileList\":[]}"),
                        generation("{}"),
                        generation("{}"),
                        generation("{}"),
                        generation("{}"),
                        generation("{}"),
                        generation("""
                                {"modifiedFileList":[{
                                  "filename":"demo/Agent2Retry.java",
                                  "action":"delete",
                                  "plan":"Delete the dedicated file.",
                                  "note":"Agent2 recovered on retry five."
                                }]}
                                """)
                );

        JavaDeleteAgentService service = new JavaDeleteAgentService();
        executor = Executors.newSingleThreadExecutor();
        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentPipelineExecutor = executor;

        service.runPipeline(context.runId(), "model");
        awaitCompleted(registry, context.runId());

        assertEquals(
                AgentService.DELETE_FILE_SENTINEL,
                registry.modifications(context.runId()).get("demo/Agent2Retry.java")
        );
        verify(llmClient, times(7)).streamGenerateWithPromptResult(
                anyString(),
                any(AgentEventSink.class),
                eq("model")
        );
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

        JavaDeleteAgentService service = new JavaDeleteAgentService();
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

    private String contextAdjustment(String addedFile, String removedFile) {
        String addition = addedFile == null
                ? ""
                : "{\"filename\":\"src/main/java/demo/" + addedFile
                        + "\",\"recommendReason\":\"needed for the deletion\"}";
        String removal = removedFile == null
                ? ""
                : "{\"filename\":\"src/main/java/demo/" + removedFile
                        + "\",\"recommendReason\":\"irrelevant to the deletion\"}";
        return "{\"needAdditionalFile\":" + (addedFile != null)
                + ",\"additionalFileList\":[" + addition + "]"
                + ",\"removeContextFileList\":[" + removal + "]}";
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
