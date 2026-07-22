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
            FeatureGraphResult graph = codemapService.getPythonFeatureGraph(featureId);
            java.util.Set<String> pendingPaths = candidateCodeService.pendingModificationKeys().stream()
                    .map(path -> path.replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> stagedPaths = candidateCodeService.stagedModificationKeys().stream()
                    .map(path -> path.replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            graph.getNodes().forEach(node -> {
                String path = CodeMapService.resolvePythonNodeToFile(node.getId()).replace('\\', '/');
                if (stagedPaths.contains(path)) {
                    node.setType("Staged");
                } else if (pendingPaths.contains(path)) {
                    node.setType("Modify");
                }
            });
            return graph;
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
        result.getNodes().stream()
                .filter(node -> pendingNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Modify"));
        java.util.Set<String> stagedNodeIds = candidateCodeService.stagedModificationKeys().stream()
                .map(JavaFilePath::toClassName)
                .collect(java.util.stream.Collectors.toSet());
        result.getNodes().stream()
                .filter(node -> stagedNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Staged"));
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
            FeatureGraphResult graph = codemapService.getPythonModificationGraph();
            java.util.Set<String> pendingPaths = candidateCodeService.pendingModificationKeys().stream()
                    .map(path -> path.replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            java.util.Set<String> stagedPaths = candidateCodeService.stagedModificationKeys().stream()
                    .map(path -> path.replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> representedPaths = graph.getNodes().stream()
                    .map(node -> pythonNodeProjectPath(node.getId(), pendingPaths))
                    .collect(java.util.stream.Collectors.toSet());
            pendingPaths.stream()
                    .filter(path -> !representedPaths.contains(path))
                    .map(FeatureGraphResult.Node::new)
                    .forEach(node -> {
                        node.setType("Modify");
                        graph.getNodes().add(node);
                    });
            graph.getNodes().stream()
                    .filter(node -> stagedPaths.contains(pythonNodeProjectPath(node.getId(), pendingPaths)))
                    .forEach(node -> node.setType("Staged"));
            return graph;
        }
        FeatureGraphResult maxGraph = codemapService.getMaxGraph();
        java.util.Set<String> classIds = candidateCodeService.pendingModificationKeys().stream()
                .map(path -> path.endsWith(".java") ? JavaFilePath.toClassName(path) : path)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        FeatureGraphResult newGraph = new FeatureGraphResult(classIds);
        FeatureGraphResult result = maxGraph.setNewType(newGraph);
        java.util.Set<String> stagedClassIds = candidateCodeService.stagedModificationKeys().stream()
                .map(path -> path.endsWith(".java") ? JavaFilePath.toClassName(path) : path)
                .collect(java.util.stream.Collectors.toSet());
        result.getNodes().stream()
                .filter(node -> stagedClassIds.contains(node.getId()))
                .forEach(node -> node.setType("Staged"));
        return result;
    }

    private String pythonNodeProjectPath(String nodeId, java.util.Set<String> candidatePaths) {
        String normalized = nodeId.replace('\\', '/');
        if (candidatePaths.contains(normalized)) {
            return normalized;
        }
        return CodeMapService.resolvePythonNodeToFile(nodeId).replace('\\', '/');
    }
}
