package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.dto.result.RepoSummaryProgressResult;
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
}
