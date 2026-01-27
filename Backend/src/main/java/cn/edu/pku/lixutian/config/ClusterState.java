package cn.edu.pku.lixutian.config;

import cn.edu.pku.lixutian.dto.result.FeatureResult;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

public class ClusterState {
    private static ClusterState instance = null;

    public static ClusterState getInstance() {
        if (instance == null) {
            instance = new ClusterState();
        }
        return instance;
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
}
