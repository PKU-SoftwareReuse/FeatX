package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.dto.result.FocusGraphContextResult;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectFilePath;
import com.mycode.service.code.AgentService;
import com.mycode.service.code.AgentRunContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CandidateGraphService {
    private final CandidateCodeService candidateCodeService;
    private final RepoSummaryFileGraphService repoSummaryFileGraphService;
    private final JavaImportAnalyzerService javaImportAnalyzerService;

    public CandidateGraphService(
            CandidateCodeService candidateCodeService,
            RepoSummaryFileGraphService repoSummaryFileGraphService,
            JavaImportAnalyzerService javaImportAnalyzerService
    ) {
        this.candidateCodeService = candidateCodeService;
        this.repoSummaryFileGraphService = repoSummaryFileGraphService;
        this.javaImportAnalyzerService = javaImportAnalyzerService;
    }

    public FeatureGraphResult buildCandidateGraph(AgentRunContext context) {
        FocusGraphContextResult.GraphStage reasoningStage = context.graphStages().stream()
                .filter(stage -> "reasoning".equals(stage.getId()))
                .findFirst()
                .orElse(null);

        Map<String, FeatureGraphResult.Node> nodesByFile = new LinkedHashMap<>();
        Map<String, String> fileByReasoningNode = new LinkedHashMap<>();
        if (reasoningStage != null) {
            for (FocusGraphContextResult.Node reasoningNode : values(reasoningStage.getNodes())) {
                String file = projectFile(reasoningNode.getFuncFile(), context);
                if (file == null) {
                    continue;
                }
                fileByReasoningNode.put(reasoningNode.getId(), file);
                FeatureGraphResult.Node fileNode = nodesByFile.computeIfAbsent(
                        file,
                        FeatureGraphResult.Node::new
                );
                String method = reasoningNode.getMethodSignature();
                if (method != null && !method.isBlank() && !fileNode.getMethods().contains(method)) {
                    fileNode.addMethod(method);
                }
            }
        }

        Set<String> pendingPaths = normalizePaths(candidateCodeService.pendingCandidateProjectPaths());
        Set<String> stagedPaths = normalizePaths(candidateCodeService.projectPathsForCandidateKeys(
                candidateCodeService.stagedModificationKeys()
        ));
        stagedPaths.retainAll(pendingPaths);

        for (String path : pendingPaths) {
            FeatureGraphResult.Node node = nodesByFile.computeIfAbsent(path, FeatureGraphResult.Node::new);
            node.setType(stagedPaths.contains(path) ? "Staged" : "Modify");
        }

        Set<String> displayedFiles = new LinkedHashSet<>(nodesByFile.keySet());
        Set<String> reasoningFiles = new LinkedHashSet<>(fileByReasoningNode.values());
        Set<String> extraCandidateFiles = new LinkedHashSet<>(pendingPaths);
        extraCandidateFiles.removeAll(reasoningFiles);

        Set<FeatureGraphResult.Edge> edges = new LinkedHashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (FeatureGraphResult.Edge edge : repoSummaryFileGraphService.getFileAdjacencyEdges(displayedFiles)) {
            if (edge != null && edgeKeys.add(edgeKey(edge.getFrom(), edge.getTo()))) {
                edges.add(edge);
            }
        }
        try {
            for (JavaImportAnalyzerService.ImportEdge edge : javaImportAnalyzerService.candidateEdges(
                    Path.of(context.projectRoot()),
                    Path.of(context.sourceRoot()),
                    displayedFiles,
                    extraCandidateFiles,
                    candidateContentsByProjectPath()
            )) {
                if (edge != null && edgeKeys.add(edgeKey(edge.from(), edge.to()))) {
                    edges.add(new FeatureGraphResult.Edge(edge.from(), edge.to()));
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not analyze Java imports for the candidate graph.", exception);
        }

        return new FeatureGraphResult(
                new LinkedHashSet<>(nodesByFile.values()),
                edges
        );
    }

    private String edgeKey(String from, String to) {
        return String.valueOf(from) + "\u0000" + String.valueOf(to);
    }

    private Map<String, String> candidateContentsByProjectPath() {
        Map<String, String> contents = new LinkedHashMap<>();
        ProjectState.getInstance().getModifications().forEach((key, value) -> {
            if (key == null || !key.replace('\\', '/').endsWith(".java")) {
                return;
            }
            List<String> projectPaths = candidateCodeService.projectPathsForCandidateKeys(List.of(key));
            if (projectPaths.isEmpty()) {
                return;
            }
            String content = AgentService.DELETE_FILE_SENTINEL.equals(value) ? "" : value;
            contents.put(ProjectFilePath.normalize(projectPaths.get(0)), content);
        });
        return contents;
    }

    private String projectFile(String value, AgentRunContext context) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        if (!normalized.endsWith(".java") && !normalized.contains("/")) {
            normalized = JavaFilePath.fromClassName(normalized);
        }
        Path projectRoot = Path.of(context.projectRoot()).toAbsolutePath().normalize();
        Path sourceRoot = Path.of(context.sourceRoot()).toAbsolutePath().normalize();
        Path supplied = Path.of(normalized);
        Path absolute;
        if (supplied.isAbsolute()) {
            absolute = supplied.normalize();
        } else {
            Path projectCandidate = projectRoot.resolve(supplied).normalize();
            absolute = projectCandidate.startsWith(sourceRoot)
                    ? projectCandidate
                    : sourceRoot.resolve(supplied).normalize();
        }
        if (!absolute.startsWith(projectRoot)) {
            return null;
        }
        return ProjectFilePath.normalize(projectRoot.relativize(absolute).toString().replace('\\', '/'));
    }

    private Set<String> normalizePaths(List<String> paths) {
        Set<String> result = new LinkedHashSet<>();
        for (String path : paths == null ? Collections.<String>emptyList() : paths) {
            result.add(ProjectFilePath.normalize(path));
        }
        return result;
    }

    private <T> List<T> values(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
