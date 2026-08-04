package com.mycode.helper;

import com.mycode.dto.result.RepoSummaryProgressResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RepoSummaryProgressProtocolTest {
    @Test
    void summaryProgressUsesMessageKeysAndStructuredArguments() throws Exception {
        int repoId = 99101;
        Method startProgress = RepoSummaryHelper.class.getDeclaredMethod("startProgress", Integer.class);
        startProgress.setAccessible(true);
        Object state = startProgress.invoke(null, repoId);

        Method handleLogLine = state.getClass().getDeclaredMethod("handleLogLine", String.class);
        handleLogLine.setAccessible(true);
        handleLogLine.invoke(state, "Cluster ID: 4, Functions: [first, second, third]");

        RepoSummaryProgressResult progress = RepoSummaryHelper.getProgress(repoId);
        JsonNode json = new ObjectMapper().valueToTree(progress);
        JsonNode clustering = json.path("steps").get(3);

        assertEquals("progress.summary.clustering", json.path("messageKey").asText());
        assertEquals(4, json.path("messageArgs").path("clusterId").asInt());
        assertEquals(3, json.path("messageArgs").path("functionCount").asInt());
        assertEquals("progress.summary.clustering", clustering.path("messageKey").asText());
        assertFalse(json.has("message"));
        assertFalse(clustering.has("label"));
        assertFalse(clustering.has("detail"));
    }

    @Test
    void moduleStageExposesAKeyInsteadOfModuleCopy() throws Exception {
        int repoId = 99102;
        Method startProgress = RepoSummaryHelper.class.getDeclaredMethod("startProgress", Integer.class);
        startProgress.setAccessible(true);
        Object state = startProgress.invoke(null, repoId);

        Method handleLogLine = state.getClass().getDeclaredMethod("handleLogLine", String.class);
        handleLogLine.setAccessible(true);
        handleLogLine.invoke(state, "Module ID: 7");

        RepoSummaryProgressResult progress = RepoSummaryHelper.getProgress(repoId);
        assertEquals("module-description", progress.getCurrentStage());
        assertEquals("progress.summary.module-description", progress.getMessageKey());
    }

    @Test
    void summaryFailureRetainsTheActionableErrorDetail() throws Exception {
        int repoId = 99103;
        Method startProgress = RepoSummaryHelper.class.getDeclaredMethod("startProgress", Integer.class);
        startProgress.setAccessible(true);
        Object state = startProgress.invoke(null, repoId);

        Method activateStep = state.getClass().getDeclaredMethod("activateStep", String.class, java.util.Map.class);
        activateStep.setAccessible(true);
        activateStep.invoke(state, "embedding-cache", java.util.Map.of());
        Method fail = state.getClass().getDeclaredMethod("fail", String.class);
        fail.setAccessible(true);
        fail.invoke(state, "LazyInitializationException: could not initialize proxy");

        RepoSummaryProgressResult progress = RepoSummaryHelper.getProgress(repoId);
        assertEquals("failed", progress.getStatus());
        assertEquals("embedding-cache", progress.getCurrentStage());
        assertEquals(
                "LazyInitializationException: could not initialize proxy",
                progress.getMessageArgs().get("error")
        );
        assertEquals(
                "LazyInitializationException: could not initialize proxy",
                progress.getSteps().get(7).getMessageArgs().get("error")
        );
    }
}
