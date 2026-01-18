package cn.edu.pku.lixutian.controller.graphController;

import cn.edu.pku.lixutian.service.code.ModifyAgentService;
import cn.edu.pku.lixutian.controller.FeatureController;
import cn.edu.pku.lixutian.dto.result.FeatureGraphResult;
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

    @GetMapping("/maxGraph")
    public FeatureGraphResult getMaxGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        return codemapService.getMaxGraph();
    }

    @GetMapping("/debloatGraph")
    public FeatureGraphResult getDebloatOnMaxGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        return maxGraph.setDebloatType();
    }

    @GetMapping("/newGraph")
    public FeatureGraphResult getNewOnMaxGraph() {
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        FeatureGraphResult newGraph = new FeatureGraphResult(ModifyAgentService.modificationMap.keySet());
        return maxGraph.setNewType(newGraph);
    }
}
