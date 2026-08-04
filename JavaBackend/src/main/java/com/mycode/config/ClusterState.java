package com.mycode.config;

import com.mycode.dto.result.FeatureResult;
import com.mycode.service.code.AgentLanguage;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Workspace-local feature selection and request state. */
public class ClusterState {
    private static final ConcurrentHashMap<String, ClusterState> WORKSPACE_STATES = new ConcurrentHashMap<>();

    public static ClusterState getInstance() {
        return WORKSPACE_STATES.computeIfAbsent(ProjectState.currentWorkspaceId(), ignored -> new ClusterState());
    }

    public static void clearWorkspace(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            WORKSPACE_STATES.remove(workspaceId);
        }
    }

    private ClusterState() {
    }

    @Getter
    @Setter
    private Set<Integer> clusterIds;

    @Getter
    @Setter
    private FeatureResult candidateFeature;

    @Getter
    @Setter
    private String newFeatureDescription;

    @Getter
    @Setter
    private Integer candidateModuleId;

    @Getter
    @Setter
    private AgentLanguage agentLanguage = AgentLanguage.EN;
}
