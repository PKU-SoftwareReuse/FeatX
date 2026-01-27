package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class ClusterResult {
    private String fullName;
    private String type;
    private Set<Integer> clusterIds;

    public ClusterResult(Vertex<?> vertex) {
        GraphResult.Node node = new GraphResult.Node(vertex);
        this.fullName = node.getId();
        this.type = node.getType();
        this.clusterIds = vertex.getClusterIds();
    }
}
