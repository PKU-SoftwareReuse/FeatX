package com.mycode.controller.graphController;

import com.mycode.config.ProjectState;
import com.mycode.controller.FeatureController;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.service.CandidateGraphService;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.CodeMapService;
import com.mycode.service.code.AgentRunContext;
import com.mycode.service.code.AgentRunRegistry;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectPathMapping;
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

    @Autowired
    CandidateGraphService candidateGraphService;

    @GetMapping("/initialGraph")
    public FeatureGraphResult getInitialGraph(@RequestParam Integer featureId) {
        featureController.select(featureId);
        return codemapService.getRepoSummaryFeatureFileGraph(featureId);
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
                .map(path -> ProjectPathMapping.sourceRelativeToProject(ProjectState.getInstance(), path))
                .collect(java.util.stream.Collectors.toSet());
        candidateCodeService.beginOperation(
                "delete:" + ProjectState.getInstance().getRepoId() + ":" + featureId,
                affectedJavaFiles
        );
        java.util.Set<String> pendingNodeIds = candidateCodeService.pendingModificationKeys().stream()
                .map(this::javaCandidateNodeId)
                .collect(java.util.stream.Collectors.toSet());
        result.getNodes().stream()
                .filter(node -> "Modify".equals(node.getType()))
                .filter(node -> !pendingNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Default"));
        result.getNodes().stream()
                .filter(node -> pendingNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Modify"));
        java.util.Set<String> stagedNodeIds = candidateCodeService.stagedModificationKeys().stream()
                .map(this::javaCandidateNodeId)
                .collect(java.util.stream.Collectors.toSet());
        result.getNodes().stream()
                .filter(node -> stagedNodeIds.contains(node.getId()))
                .forEach(node -> node.setType("Staged"));
        return result;
    }

    @GetMapping("/candidateGraph")
    public FeatureGraphResult getCandidateGraph(@RequestParam(required = false) String runId) {
        try {
            if (ProjectState.getInstance().isPython()) {
                // Python keeps its existing feature/container graph semantics;
                // only the public route name is shared with Java.
                agentRunRegistry.requireCompletedIfActive(runId);
                return getPythonCandidateGraph();
            }

            AgentRunContext context = agentRunRegistry.requireCompleted(runId);
            return candidateGraphService.buildCandidateGraph(context);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    private FeatureGraphResult getPythonCandidateGraph() {
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

    private String javaCandidateNodeId(String candidatePath) {
        if (!candidatePath.endsWith(".java")) {
            return candidatePath;
        }
        String sourceRelativePath = ProjectPathMapping.projectRelativeToSource(
                ProjectState.getInstance(),
                candidatePath
        ).orElse(candidatePath);
        return JavaFilePath.toClassName(sourceRelativePath);
    }

    private String pythonNodeProjectPath(String nodeId, java.util.Set<String> candidatePaths) {
        String normalized = nodeId.replace('\\', '/');
        if (candidatePaths.contains(normalized)) {
            return normalized;
        }
        return CodeMapService.resolvePythonNodeToFile(nodeId).replace('\\', '/');
    }
}
