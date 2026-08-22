package com.mycode.service;

import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.mycode.helper.CodeDiffHelper;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectPathMapping;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class JavaStaticDeleteContextService {

    public String buildContext() {
        Set<Integer> clusterIds = ClusterState.getInstance().getClusterIds();
        if (clusterIds == null || clusterIds.isEmpty()) {
            return "";
        }

        SKG skg = SKG.getInstance();
        FeatureGraphResult debloatGraph = new FeatureGraphResult(skg.getMaxGraph(clusterIds)).setDebloatType();
        VertexMap vertexMap = VertexMap.getInstance();
        if (vertexMap == null) {
            throw new IllegalStateException("Java static deletion requires an initialized VertexMap.");
        }

        List<StaticDeleteDiff> diffs = new ArrayList<>();
        debloatGraph.getNodes().stream()
                .filter(node -> "Modify".equals(node.getType()))
                .sorted(Comparator.comparing(FeatureGraphResult.Node::getId))
                .forEach(node -> {
                    Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(node.getId());
                    if (classVertex == null) {
                        throw new IllegalStateException(
                                "Java static deletion could not resolve affected class " + node.getId() + "."
                        );
                    }
                    String file = sourceFile(classVertex, node.getId());
                    String diff = CodeDiffHelper.generateDeleteDiff(skg, classVertex, clusterIds);
                    diffs.add(new StaticDeleteDiff(file, node.getId(), diff));
                });

        if (diffs.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder("""
                ## Deterministic Java Static Deletion Context

                The following complete class-level diffs come from FeatX's legacy Java DeleteHelper.
                Lines prefixed with '-' are deterministic deletion candidates. Use them as the primary deletion scope,
                while preserving every protected shared symbol and repairing references required for compilation.

                """);
        Set<String> affectedFiles = new LinkedHashSet<>();
        for (StaticDeleteDiff diff : diffs) {
            if (affectedFiles.add(diff.file)) {
                context.append("STATIC_DELETE_AFFECTED_FILE: ").append(diff.file).append('\n');
            }
        }
        for (StaticDeleteDiff diff : diffs) {
            context.append("\n### Static delete diff: ").append(diff.classId).append("\n\n")
                    .append("File: ").append(diff.file).append("\n\n")
                    .append("```diff\n").append(diff.diff).append("\n```\n");
        }
        return context.toString();
    }

    private String sourceFile(Vertex<TypeDeclaration<?>> classVertex, String classId) {
        try {
            ProjectState project = ProjectState.getInstance();
            Path source = classVertex.getDeclaration()
                    .findCompilationUnit().orElseThrow()
                    .getStorage().orElseThrow()
                    .getPath().toAbsolutePath().normalize();
            String preprocessPath = project.getPreprocess2Path();
            if (preprocessPath != null && !preprocessPath.isBlank()) {
                Path preprocessRoot = Path.of(preprocessPath).toAbsolutePath().normalize();
                if (source.startsWith(preprocessRoot)) {
                    String sourceRelativePath = preprocessRoot.relativize(source)
                            .toString()
                            .replace(File.separatorChar, '/');
                    return ProjectPathMapping.sourceRelativeToProject(project, sourceRelativePath);
                }
            }
            Path sourceRoot = Path.of(project.getSrcPath()).toAbsolutePath().normalize();
            if (source.startsWith(sourceRoot)) {
                return ProjectPathMapping.absoluteToProject(project, source);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the graph class id used by the legacy delete graph.
        }
        return ProjectPathMapping.sourceRelativeToProject(
                ProjectState.getInstance(),
                JavaFilePath.fromClassName(classId)
        );
    }

    private record StaticDeleteDiff(String file, String classId, String diff) {
    }
}
