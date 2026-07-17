package cn.edu.pku.lixutian.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

        String result = client.streamGenerateWithPrompt("test prompt", new SseEmitter(), "chosen-model");

        assertEquals("Hello world", result);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));

        JsonNode requestBody = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
        assertEquals("chosen-model", requestBody.path("model").asText());
        assertEquals("test prompt", requestBody.path("messages").get(0).path("content").asText());
        assertTrue(requestBody.path("stream").asBoolean());
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
