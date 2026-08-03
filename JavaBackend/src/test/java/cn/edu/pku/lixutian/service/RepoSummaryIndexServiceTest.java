package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoSummaryIndexServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProcessService processService = new ProcessService();
    private Integer repositoryId;

    @AfterEach
    void clearGraphState() {
        if (repositoryId != null) {
            processService.clearRepository(repositoryId);
        }
    }

    @Test
    void initialJavaWarmupSendsCompleteGraphAndFullSync(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/com/acme/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                "package com.acme; public class App { String suffix(String value) { return value + \"x\"; } }\n"
        );

        repositoryId = 9912;
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(repositoryId);
        processService.process();

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("{}"));
            server.enqueue(jsonResponse("{}"));
            server.start();

            CodeMapService codeMapService = mock(CodeMapService.class);
            when(codeMapService.readFeatureFromDatabase(repositoryId)).thenReturn(List.of());
            RepoSummaryIndexService service = new RepoSummaryIndexService(
                    new RepoSummaryHttpClient(server.url("/").toString()),
                    codeMapService
            );

            service.warmRepositoryIndexes(project, repositoryId);

            RecordedRequest featureRequest = server.takeRequest(5, TimeUnit.SECONDS);
            RecordedRequest graphRequest = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(featureRequest);
            assertNotNull(graphRequest);
            assertEquals("/v1/index/sync-features", featureRequest.getPath());
            assertEquals("/v1/cache/sync", graphRequest.getPath());

            JsonNode graphPayload = OBJECT_MAPPER.readTree(graphRequest.getBody().readUtf8());
            assertTrue(graphPayload.path("fullSync").asBoolean());
            assertTrue(graphPayload.path("changedPaths").isEmpty());
            assertFalse(graphPayload.path("nodes").isEmpty());
            graphPayload.path("nodes").forEach(node -> {
                assertFalse(node.path("id").asText().isBlank());
                assertEquals("src/main/java/com/acme/App.java", node.path("sourcePath").asText());
                assertFalse(node.path("text").asText().isBlank());
            });
        }
    }

    @Test
    void repositoryIndexIsReadyOnlyWhenFeatureAndGraphCachesExist() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("""
                    {"entries":[
                      {"entityKind":"feature","count":3,"bytes":100},
                      {"entityKind":"graph-node","count":7,"bytes":200}
                    ]}
                    """));
            server.enqueue(jsonResponse("""
                    {"entries":[
                      {"entityKind":"feature","count":3,"bytes":100}
                    ]}
                    """));
            server.start();

            RepoSummaryIndexService service = new RepoSummaryIndexService(
                    new RepoSummaryHttpClient(server.url("/").toString()),
                    mock(CodeMapService.class)
            );

            assertTrue(service.isRepositoryIndexReady(12));
            assertFalse(service.isRepositoryIndexReady(13));
            assertEquals("/v1/cache/stats/12", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertEquals("/v1/cache/stats/13", server.takeRequest(5, TimeUnit.SECONDS).getPath());
        }
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
