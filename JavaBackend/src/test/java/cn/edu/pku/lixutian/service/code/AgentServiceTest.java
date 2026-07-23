package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AgentServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentService service = new AgentService();

    @AfterEach
    void cleanUpProjectState() {
        ProjectState.getInstance().setRepoId(null);
    }

    @Test
    void zeroLimitPreservesCompleteContext() {
        String context = "complete-current-feature-codemap";

        assertEquals(context, service.boundedPromptSection(context, 0));
    }

    @Test
    void positiveLimitStillBoundsOptionalContext() {
        String bounded = service.boundedPromptSection("abcdefghijklmnopqrstuvwxyz", 12);

        assertTrue(bounded.contains("context truncated by FeatX"));
        assertThrows(IllegalArgumentException.class, () -> service.boundedPromptSection("context", -1));
    }

    @Test
    void failedCallPersistsPartialResponseAndMissingUsage(@TempDir Path projectRoot) throws Exception {
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(51);

        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify",
                "new requirement",
                "old requirement",
                "focused context",
                "Foo.java",
                AgentLanguage.EN,
                projectRoot.toString(),
                projectRoot.toString(),
                51,
                7,
                null,
                List.of()
        );
        registry.claim(context.runId());

        LlmClient llmClient = mock(LlmClient.class);
        doAnswer(invocation -> {
            AgentEventSink sink = invocation.getArgument(1);
            sink.send("delta", "partial model output");
            throw new IOException("provider unavailable");
        }).when(llmClient).streamGenerateWithPromptResult(anyString(), any(AgentEventSink.class), eq("model"));

        service.llmClient = llmClient;
        service.agentRunRegistry = registry;
        service.agentRunLogService = new AgentRunLogService(projectRoot.resolve("agent-logs"));

        assertThrows(
                IOException.class,
                () -> service.generateAgentResponse(context, "agent1", "prompt", (event, content) -> {
                }, "model")
        );

        Path callDirectory = projectRoot.resolve("agent-logs")
                .resolve(context.runId())
                .resolve("001-agent1");
        assertEquals("partial model output", Files.readString(callDirectory.resolve("response.txt")));
        JsonNode metadata = OBJECT_MAPPER.readTree(callDirectory.resolve("metadata.json").toFile());
        assertEquals("FAILED", metadata.path("status").asText());
        assertFalse(metadata.path("usageReported").asBoolean());
        assertTrue(metadata.path("error").asText().contains("provider unavailable"));
        assertEquals(1, registry.tokenUsage(context.runId()).calls());
        assertEquals(0, registry.tokenUsage(context.runId()).reportedCalls());
    }
}
