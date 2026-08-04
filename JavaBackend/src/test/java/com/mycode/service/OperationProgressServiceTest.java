package com.mycode.service;

import com.mycode.config.ProjectState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OperationProgressServiceTest {
    @Test
    void progressIsStoredPerRepository(@TempDir Path first, @TempDir Path second) {
        ProjectState firstProject = ProjectState.selectWorkspace("progress-a", 401, first.toString(), "JAVA");
        ProjectState secondProject = ProjectState.selectWorkspace("progress-b", 402, second.toString(), "JAVA");
        OperationProgressService service = new OperationProgressService();

        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-a", firstProject)) {
            service.start("modify", 8, "prepare");
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-b", secondProject)) {
            service.start("add", 8, "prepare");
            assertEquals("add", service.getSnapshot().getOperation());
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-a", firstProject)) {
            assertEquals("modify", service.getSnapshot().getOperation());
            assertEquals("progress.operation.prepare", service.getSnapshot().getMessageKey());
        }
    }

    @Test
    void progressProtocolContainsOnlyMessageKeyAndStructuredArguments(@TempDir Path workspace) {
        ProjectState project = ProjectState.selectWorkspace("progress-protocol", 403, workspace.toString(), "JAVA");
        OperationProgressService service = new OperationProgressService();

        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-protocol", project)) {
            service.start("java-delete", 8, "ownership-check", Map.of("protectedSharedMethods", 3));
            OperationProgressService.ProgressSnapshot snapshot = service.getSnapshot();
            JsonNode json = new ObjectMapper().valueToTree(snapshot);

            assertEquals("progress.operation.ownership-check", json.path("messageKey").asText());
            assertEquals(3, json.path("messageArgs").path("protectedSharedMethods").asInt());
            assertFalse(json.has("message"));
            assertFalse(json.has("error"));
            assertFalse(json.has("details"));
        }
    }
}
