package com.mycode.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockWebServer server;
    private LlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new LlmClient(server.url("/").toString(), "test-key");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void modelCatalogPrefersDeepseekV4FlashWhenAvailable() throws Exception {
        enqueueModels("model-a", "deepseek-v4-flash", "model-b");

        LlmClient.ModelCatalog catalog = client.getModelCatalog();

        assertEquals(List.of("model-a", "deepseek-v4-flash", "model-b"), catalog.models());
        assertEquals("deepseek-v4-flash", catalog.defaultModel());
        assertModelsRequest(server.takeRequest(1, TimeUnit.SECONDS));
    }

    @Test
    void modelCatalogFallsBackToFirstModel() throws Exception {
        enqueueModels("first-model", "second-model");

        LlmClient.ModelCatalog catalog = client.getModelCatalog();

        assertEquals("first-model", catalog.defaultModel());
        assertModelsRequest(server.takeRequest(1, TimeUnit.SECONDS));
    }

    @Test
    void resolveModelRejectsASelectionOutsideTheProviderList() {
        enqueueModels("first-model", "second-model");

        assertThrows(IllegalArgumentException.class, () -> client.resolveModel("unknown-model"));
    }

    @Test
    void endpointUrlCannotBeConfiguredAsTheBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmClient("https://api.deepseek.com/chat/completions", "test-key")
        );
    }

    @Test
    void baseUrlGetsTheApiVersionPathExactlyOnce() {
        assertEquals("https://api.example.com/v1", LlmClient.normalizeBaseUrl("https://api.example.com"));
        assertEquals("https://api.example.com/v1", LlmClient.normalizeBaseUrl("https://api.example.com/v1/"));
    }

    @Test
    void streamingRequestUsesOpenAiChatCompletionsPathAndSelectedModel() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                        + "data: [DONE]\n\n"));

        LlmGenerationResult result = client.streamGenerateWithPromptResult(
                "test prompt",
                (event, content) -> {
                },
                "chosen-model"
        );

        assertEquals("Hello world", result.content());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));

        JsonNode requestBody = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
        assertEquals("chosen-model", requestBody.path("model").asText());
        assertEquals("system", requestBody.path("messages").get(0).path("role").asText());
        assertTrue(requestBody.path("messages").get(0).path("content").asText().contains("FeatX 的代码工程组件"));
        assertEquals("test prompt", requestBody.path("messages").get(1).path("content").asText());
        assertTrue(requestBody.path("stream").asBoolean());
        assertTrue(requestBody.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void streamingResultCapturesContentCacheAndReasoningUsage() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"result\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{"
                        + "\"prompt_tokens\":120,"
                        + "\"completion_tokens\":30,"
                        + "\"total_tokens\":150,"
                        + "\"prompt_cache_hit_tokens\":80,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":4}}}\n\n"
                        + "data: [DONE]\n\n"));

        LlmGenerationResult result = client.streamGenerateWithPromptResult(
                "test prompt",
                (event, content) -> {
                },
                "chosen-model"
        );

        assertEquals("result", result.content());
        assertNotNull(result.usage());
        assertEquals(120, result.usage().inputTokens());
        assertEquals(80, result.usage().cachedInputTokens());
        assertEquals(40, result.usage().uncachedInputTokens());
        assertEquals(30, result.usage().outputTokens());
        assertEquals(4, result.usage().reasoningOutputTokens());
        assertEquals(150, result.usage().totalTokens());
    }

    @Test
    void nonStreamingResultCapturesContentAndUsage() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"deltaQuery\\\":\\\"cache avatar\\\"}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":8,\"total_tokens\":50,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":12}}}"));

        LlmGenerationResult result = client.generateWithSinglePromptResult(
                "compare descriptions",
                "chosen-model"
        );

        assertEquals("{\"deltaQuery\":\"cache avatar\"}", result.content());
        assertNotNull(result.usage());
        assertEquals(42, result.usage().inputTokens());
        assertEquals(12, result.usage().cachedInputTokens());
        assertEquals(8, result.usage().outputTokens());
        assertEquals(50, result.usage().totalTokens());

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        JsonNode requestBody = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
        assertEquals("chosen-model", requestBody.path("model").asText());
        assertTrue(!requestBody.path("stream").asBoolean());
    }

    private void enqueueModels(String... modelIds) {
        StringBuilder body = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int index = 0; index < modelIds.length; index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append("{\"id\":\"").append(modelIds[index]).append("\",\"object\":\"model\"}");
        }
        body.append("]}");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body.toString()));
    }

    private void assertModelsRequest(RecordedRequest request) {
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        assertEquals("/v1/models", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
    }
}
