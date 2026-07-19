package cn.edu.pku.lixutian.controller.graphController;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.controller.FeatureController;
import cn.edu.pku.lixutian.dto.result.FeatureGraphResult;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.CodeMapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/graph/feature")
public class FeatureGraphController {
    @Autowired
    CodeMapService codemapService;

    @Autowired
    FeatureController featureController;

    @Autowired
    CandidateCodeService candidateCodeService;

    @GetMapping("/maxGraph")
    public FeatureGraphResult getMaxGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        if (ProjectState.getInstance().isPython()) {
            return codemapService.getPythonFeatureGraph(featureId);
        }
        return codemapService.getMaxGraph();
    }

    @GetMapping("/debloatGraph")
    public FeatureGraphResult getDebloatOnMaxGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        if (ProjectState.getInstance().isPython()) {
            return codemapService.getPythonFeatureGraph(featureId);
        }
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        FeatureGraphResult result = maxGraph.setDebloatType();
        java.util.Set<String> affectedNodeIds = result.getNodes().stream()
                .filter(node -> "Modify".equals(node.getType()))
                .map(FeatureGraphResult.Node::getId)
                .collect(java.util.stream.Collectors.toSet());
        candidateCodeService.beginOperation("delete:" + ProjectState.getInstance().getRepoId() + ":" + featureId,
                affectedNodeIds);
        java.util.Set<String> pendingNodeIds = candidateCodeService.pendingModificationKeys();
        result.getNodes().stream()
                .filter(node -> "Modify".equals(node.getType()))
                .filter(node -> !pendingNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Default"));
        return result;
    }

    @GetMapping("/newGraph")
    public FeatureGraphResult getNewOnMaxGraph() {
        if (ProjectState.getInstance().isPython()) {
            return codemapService.getPythonModificationGraph();
        }
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        FeatureGraphResult newGraph = new FeatureGraphResult(candidateCodeService.pendingModificationKeys());
        return maxGraph.setNewType(newGraph);
    }
}
