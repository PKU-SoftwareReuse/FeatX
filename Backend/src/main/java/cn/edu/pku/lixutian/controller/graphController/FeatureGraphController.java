package cn.edu.pku.lixutian.controller.graphController;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.controller.FeatureController;
import cn.edu.pku.lixutian.dto.result.FeatureGraphResult;
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.helper.JavaFilePath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/graph/feature")
public class FeatureGraphController {
    @Autowired
    CodeMapService codemapService;

    @Autowired
    FeatureController featureController;

    @Autowired
    CandidateCodeService candidateCodeService;

    @Autowired
    AgentRunRegistry agentRunRegistry;

    @GetMapping("/maxGraph")
    public FeatureGraphResult getMaxGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        if (ProjectState.getInstance().isPython()) {
            return codemapService.getPythonFeatureGraph(featureId);
        }
        return codemapService.getMaxGraph();
    }

    @GetMapping("/debloatGraph")
    public FeatureGraphResult getDebloatOnMaxGraph(
            @RequestParam Integer featureId,
            @RequestParam String runId
    ) {
        try {
            agentRunRegistry.requireCompletedOperation(runId, "delete");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
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
        java.util.Set<String> affectedJavaFiles = affectedNodeIds.stream()
                .map(JavaFilePath::fromClassName)
                .collect(java.util.stream.Collectors.toSet());
        candidateCodeService.beginOperation(
                "delete:" + ProjectState.getInstance().getRepoId() + ":" + featureId,
                affectedJavaFiles
        );
        java.util.Set<String> pendingNodeIds = candidateCodeService.pendingModificationKeys().stream()
                .map(JavaFilePath::toClassName)
                .collect(java.util.stream.Collectors.toSet());
        result.getNodes().stream()
                .filter(node -> "Modify".equals(node.getType()))
                .filter(node -> !pendingNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Default"));
        return result;
    }

    @GetMapping("/newGraph")
    public FeatureGraphResult getNewOnMaxGraph(@RequestParam(required = false) String runId) {
        try {
            agentRunRegistry.requireCompletedIfActive(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
        if (ProjectState.getInstance().isPython()) {
            return codemapService.getPythonModificationGraph();
        }
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        java.util.Set<String> classIds = candidateCodeService.pendingModificationKeys().stream()
                .map(JavaFilePath::toClassName)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        FeatureGraphResult newGraph = new FeatureGraphResult(classIds);
        return maxGraph.setNewType(newGraph);
    }
}
