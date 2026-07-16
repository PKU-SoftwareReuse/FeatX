package cn.edu.pku.lixutian.dto.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class FocusGraphContextResult {
    private String query;
    private List<SelectedFeature> selectedFeatures = new ArrayList<>();
    private List<String> seedMethods = new ArrayList<>();
    private List<String> localizedMethods = new ArrayList<>();
    private List<String> affectedFiles = new ArrayList<>();
    private ReasoningGraph reasoningGraph = new ReasoningGraph();
    private List<GraphStage> graphStages = new ArrayList<>();
    private String contextPrompt = "";

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelectedFeature {
        private Integer rank;
        private String featureId;
        private String clusterId;
        private String moduleDesc;
        private String description;
        private String reason;
        private Double score;
        private Integer retrievalRank;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasoningGraph {
        private List<Node> nodes = new ArrayList<>();
        private List<Edge> edges = new ArrayList<>();
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphStage {
        private String id;
        private String label;
        private String description;
        private List<Node> nodes = new ArrayList<>();
        private List<Edge> edges = new ArrayList<>();
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        private String id;
        private String label;
        private String category;
        private String srcType;
        private String methodSignature;
        private String funcFile;
        private Double score;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Edge {
        private String from;
        private String to;
        private String type;
    }
}
