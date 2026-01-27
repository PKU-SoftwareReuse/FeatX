package cn.edu.pku.lixutian.controller.graphController;

import cn.edu.pku.lixutian.dto.result.ClusterResult;
import cn.edu.pku.lixutian.dto.result.GraphResult;
import cn.edu.pku.lixutian.graph.CallGraph;
import cn.edu.pku.lixutian.graph.ClassGraph;
import cn.edu.pku.lixutian.graph.ReferenceGraph;
import cn.edu.pku.lixutian.graph.SKG;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/base")
public class BaseGraphController {
    @GetMapping("/skg")
    public GraphResult getSKG() {
        SKG instance = SKG.getInstance();
        if (instance != null && instance.isBuilt()) {
            return new GraphResult(instance);
        } else {
            return new GraphResult();
        }
    }

    @GetMapping("/clusterIds")
    public List<ClusterResult> getClusterIds() {
        SKG skg = SKG.getInstance();
        return skg.vertexSet().stream().map(ClusterResult::new).collect(Collectors.toList());
    }
}
