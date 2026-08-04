package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.FeatureResult;
import com.mycode.dto.result.ModuleResult;
import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.helper.ProjectPathMapping;
import com.mycode.service.code.AgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RepoSummaryIndexService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RepoSummaryHttpClient httpClient;
    private final CodeMapService codeMapService;

    public RepoSummaryIndexService(RepoSummaryHttpClient httpClient, CodeMapService codeMapService) {
        this.httpClient = httpClient;
        this.codeMapService = codeMapService;
    }

    public void warmRepositoryIndexes(ProjectState project, Integer repositoryId) throws IOException {
        if (project == null || repositoryId == null) {
            return;
        }
        syncFeatureIndex(repositoryId);
        if (project.isJava()) {
            syncJavaIndex(project, repositoryId, Set.of(), true);
        } else if (project.isPython()) {
            warmPythonIndex(project, repositoryId);
        }
    }

    public boolean isRepositoryIndexReady(Integer repositoryId) throws IOException {
        if (repositoryId == null) {
            return false;
        }
        JsonNode stats = httpClient.getJson("/v1/cache/stats/" + repositoryId, 30);
        boolean featureIndexReady = false;
        boolean graphIndexReady = false;
        for (JsonNode entry : stats.path("entries")) {
            if (entry.path("count").asLong() <= 0) {
                continue;
            }
            String entityKind = entry.path("entityKind").asText();
            if ("feature".equals(entityKind)) {
                featureIndexReady = true;
            } else if ("graph-node".equals(entityKind)) {
                graphIndexReady = true;
            }
        }
        return featureIndexReady && graphIndexReady;
    }

    public void refreshConfirmedChanges(AgentRunContext run, List<String> changedProjectPaths) throws IOException {
        if (run == null || run.repositoryId() == null) {
            return;
        }
        syncFeatureIndex(run.repositoryId());

        Set<String> changedPaths = changedProjectPaths == null
                ? Set.of()
                : changedProjectPaths.stream()
                .map(RepoSummaryIndexService::normalizePath)
                .filter(path -> !path.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (changedPaths.isEmpty()) {
            return;
        }

        ProjectState project = ProjectState.getInstance();
        if (project.isPython() && changedPaths.stream().anyMatch(path -> path.endsWith(".py"))) {
            syncPythonIndex(project, run.repositoryId(), changedPaths);
        } else if (project.isJava() && changedPaths.stream().anyMatch(path -> path.endsWith(".java"))) {
            syncJavaIndex(project, run.repositoryId(), changedPaths, false);
        }
    }

    private void syncFeatureIndex(Integer repositoryId) throws IOException {
        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        request.put("repoId", repositoryId);
        ArrayNode features = request.putArray("features");
        for (ModuleResult module : codeMapService.readFeatureFromDatabase(repositoryId)) {
            for (FeatureResult feature : module.getFeatureList()) {
                ObjectNode item = features.addObject();
                item.put("featureId", feature.getFeatureId());
                item.put("clusterId", featureClusterId(module, feature));
                item.put("moduleDesc", valueOrEmpty(module.getModuleDesc()));
                item.put("description", valueOrEmpty(feature.getFeatureDescription()));
                ArrayNode methods = item.putArray("methods");
                featureMethods(feature).forEach(methods::add);
            }
        }
        httpClient.postJson("/v1/index/sync-features", request, 180);
    }

    private void syncPythonIndex(
            ProjectState project,
            Integer repositoryId,
            Set<String> changedPaths
    ) throws IOException {
        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("projectRoot", project.getProjectPath());
        request.put("sourceRoot", project.getSrcPath());
        ArrayNode paths = request.putArray("changedPaths");
        changedPaths.forEach(paths::add);
        httpClient.postJson("/v1/cache/sync-python", request, 900);
    }

    private void warmPythonIndex(ProjectState project, Integer repositoryId) throws IOException {
        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("projectRoot", project.getProjectPath());
        request.put("sourceRoot", project.getSrcPath());
        httpClient.postJson("/v1/cache/warm-python", request, 900);
    }

    private void syncJavaIndex(
            ProjectState project,
            Integer repositoryId,
            Set<String> changedPaths,
            boolean fullSync
    ) throws IOException {
        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("entityKind", "graph-node");
        request.put("fullSync", fullSync);
        ArrayNode paths = request.putArray("changedPaths");
        changedPaths.forEach(paths::add);
        ArrayNode nodes = request.putArray("nodes");

        SKG.findInstance().ifPresent(graph -> graph.vertexSet().stream()
                .sorted(java.util.Comparator.comparing(Vertex::getId))
                .forEach(vertex -> addJavaNode(nodes, project, changedPaths, fullSync, vertex)));
        httpClient.postJson("/v1/cache/sync", request, 900);
    }

    private void addJavaNode(
            ArrayNode nodes,
            ProjectState project,
            Set<String> changedPaths,
            boolean fullSync,
            Vertex<?> vertex
    ) {
        BodyDeclaration<?> declaration = vertex.getDeclaration();
        String sourcePath = sourceFile(project, declaration);
        if (sourcePath.isBlank() || (!fullSync && !changedPaths.contains(sourcePath))) {
            return;
        }
        ObjectNode item = nodes.addObject();
        item.put("id", vertex.getId());
        item.put("sourcePath", sourcePath);
        item.put("text", nodeText(declaration));
    }

    private String sourceFile(ProjectState project, BodyDeclaration<?> declaration) {
        try {
            Path source = declaration.findCompilationUnit().orElseThrow()
                    .getStorage().orElseThrow().getPath().toAbsolutePath().normalize();
            Path preprocessRoot = Path.of(project.getPreprocess2Path()).toAbsolutePath().normalize();
            if (source.startsWith(preprocessRoot)) {
                String sourceRelativePath = preprocessRoot.relativize(source)
                        .toString()
                        .replace(File.separatorChar, '/');
                return normalizePath(ProjectPathMapping.sourceRelativeToProject(project, sourceRelativePath));
            }
            Path sourceRoot = Path.of(project.getSrcPath()).toAbsolutePath().normalize();
            if (source.startsWith(sourceRoot)) {
                return normalizePath(ProjectPathMapping.absoluteToProject(project, source));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String nodeText(BodyDeclaration<?> declaration) {
        if (!(declaration instanceof TypeDeclaration<?> type)) {
            return declaration.toString();
        }
        TypeDeclaration<?> skeleton = type.clone();
        skeleton.findAll(MethodDeclaration.class).forEach(method -> method.removeBody());
        skeleton.findAll(ConstructorDeclaration.class)
                .forEach(constructor -> constructor.getBody().getStatements().clear());
        skeleton.findAll(InitializerDeclaration.class).forEach(Node::remove);
        return skeleton.toString();
    }

    private List<String> featureMethods(FeatureResult feature) {
        if (feature.getCandidateMethods() == null) {
            return List.of();
        }
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        feature.getCandidateMethods().forEach(candidate -> {
            if (candidate.getLxtFull() != null && !candidate.getLxtFull().isEmpty()) {
                candidate.getLxtFull().forEach(full -> {
                    if (full.getLxtFullSignature() != null && !full.getLxtFullSignature().isBlank()) {
                        methods.add(full.getLxtFullSignature());
                    }
                });
            } else if (candidate.getZyfShortSignature() != null && !candidate.getZyfShortSignature().isBlank()) {
                methods.add(candidate.getZyfShortSignature());
            }
        });
        return List.copyOf(methods);
    }

    private String featureClusterId(ModuleResult module, FeatureResult feature) {
        Set<Integer> clusterIds = codeMapService.clusterMap(feature);
        if (clusterIds.isEmpty()) {
            return module.getModuleId() == null ? "" : String.valueOf(module.getModuleId());
        }
        return clusterIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
