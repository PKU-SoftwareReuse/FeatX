package cn.edu.pku.lixutian.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepoSummaryHttpClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void streamsProgressAndReturnsFinalResult() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/x-ndjson")
                    .setBody("""
                            {"type":"progress","progress":{"stage":"ranking","step":6}}
                            {"type":"result","result":{"selectedNodeIds":["node-a"]}}
                            """));
            server.start();

            RepoSummaryHttpClient client = new RepoSummaryHttpClient(server.url("/").toString());
            List<JsonNode> progress = new ArrayList<>();
            JsonNode result = client.postStreaming(
                    "/v1/java-graph/rank",
                    OBJECT_MAPPER.createObjectNode().put("query", "feature"),
                    progress::add,
                    5
            );

            assertEquals("ranking", progress.get(0).path("stage").asText());
            assertEquals("node-a", result.path("selectedNodeIds").get(0).asText());
            assertEquals("/v1/java-graph/rank", server.takeRequest().getPath());
        }
    }
}
