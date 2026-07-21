package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.Arc;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.CallArc;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class JavaGraphContextService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PROGRESS_PREFIX = "__FOCUSGRAPH_PROGRESS__";

    private final CodeMapService codeMapService;
    private final OperationProgressService progressService;

    public JavaGraphContextService(CodeMapService codeMapService, OperationProgressService progressService) {
        this.codeMapService = codeMapService;
        this.progressService = progressService;
    }

    public FocusGraphContextResult buildModifyContext(
            FeatureResult currentFeature,
            String oldDescription,
            String newDescription,
            String deltaQuery,
            AgentLanguage language
    ) throws IOException, InterruptedException {
        return buildContext(
                "modify",
                currentFeature,
                oldDescription,
                newDescription,
                deltaQuery,
                language
        );
    }

    public FocusGraphContextResult buildAddContext(
            String newDescription,
            AgentLanguage language
    ) throws IOException, InterruptedException {
        return buildContext("add", null, "", newDescription, newDescription, language);
    }

    public FocusGraphContextResult buildDeleteContext(
            FeatureResult currentFeature,
            String description,
            AgentLanguage language
    ) throws IOException, InterruptedException {
        String query = "Delete feature and remove related implementation: " + valueOrEmpty(description);
        return buildContext("delete", currentFeature, description, "", query, language);
    }

    private FocusGraphContextResult buildContext(
            String operation,
            FeatureResult currentFeature,
            String oldDescription,
            String newDescription,
            String query,
            AgentLanguage language
    ) throws IOException, InterruptedException {
        List<FeatureCandidate> candidates = loadFeatureCandidates(language);
        Map<Integer, FeatureCandidate> candidateById = candidates.stream()
                .collect(Collectors.toMap(candidate -> candidate.featureId, candidate -> candidate, (left, right) -> left, LinkedHashMap::new));

        RetrievalResult retrieval = "delete".equals(operation)
                ? forcedDeleteRetrieval(currentFeature, candidateById)
                : retrieveFeatures(operation, currentFeature, query, candidates);
        List<FeatureCandidate> selectedCandidates = retrieval.selectedFeatures.stream()
                .map(selected -> parseInteger(selected.getFeatureId()))
                .filter(Objects::nonNull)
                .map(candidateById::get)
                .filter(Objects::nonNull)
                .toList();

        Set<Integer> selectedClusterIds = selectedCandidates.stream()
                .flatMap(candidate -> candidate.clusterIds.stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> seedMethods = new LinkedHashSet<>(retrieval.seedMethods);
        Set<String> currentCodeMapMethods = currentFeature == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(allCodeMapMethods(currentFeature));
        Set<String> currentResolvedMethods = currentFeature == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(resolvedMethods(currentFeature));

        progressService.update(
                "max-graph",
                "Building the Java Expanded Graph directly from SKG.getMaxGraph(selectedClusterIds).",
                5,
                8,
                Map.of("selectedClusterCount", selectedClusterIds.size())
        );
        SKG expandedGraph = SKG.getInstance().getMaxGraph(selectedClusterIds);
        GraphData graphData = graphData(
                expandedGraph,
                selectedCandidates,
                retrieval.selectedFeatures,
                seedMethods,
                currentResolvedMethods
        );
        Set<String> initialNodeIds = initialNodeIds(expandedGraph, graphData.nodes, seedMethods);
        initialNodeIds.forEach(nodeId -> graphData.nodes.computeIfPresent(
                nodeId,
                (ignored, node) -> node.withSrcType("original")
        ));
        List<GraphEdgeData> initialEdges = graphData.edges.stream()
                .filter(edge -> initialNodeIds.contains(edge.from) && initialNodeIds.contains(edge.to))
                .toList();

        RankResult rankResult = rankGraph(query, graphData);
        Set<String> reasoningNodeIds = new LinkedHashSet<>(rankResult.selectedNodeIds);
        List<GraphEdgeData> reasoningEdges = graphData.edges.stream()
                .filter(edge -> reasoningNodeIds.contains(edge.from) && reasoningNodeIds.contains(edge.to))
                .toList();

        FocusGraphContextResult result = new FocusGraphContextResult();
        result.setQuery(query);
        result.setSelectedFeatures(retrieval.selectedFeatures);
        result.setSeedMethods(new ArrayList<>(seedMethods));
        result.setLocalizedMethods(reasoningNodeIds.stream()
                .map(graphData.nodes::get)
                .filter(Objects::nonNull)
                .filter(JavaNodeData::isCallable)
                .map(node -> node.methodSignature)
                .toList());
        result.setAffectedFiles(reasoningNodeIds.stream()
                .map(graphData.nodes::get)
                .filter(Objects::nonNull)
                .map(node -> node.funcFile)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList());
        result.setReasoningGraph(reasoningGraph(reasoningNodeIds, reasoningEdges, graphData, rankResult.rankScores));
        result.setGraphStages(List.of(
                graphStage(
                        "initial",
                        "Initial Graph",
                        "Top-k Java feature CodeMap methods, their owner types, and direct SKG relations.",
                        initialNodeIds,
                        initialEdges,
                        graphData,
                        rankResult.rankScores
                ),
                graphStage(
                        "expanded",
                        "Expanded Graph",
                        "The existing Java SKG maxGraph for the selected feature clusterIds.",
                        graphData.nodes.keySet(),
                        graphData.edges,
                        graphData,
                        rankResult.rankScores
                ),
                graphStage(
                        "reasoning",
                        "Reasoning Graph",
                        "BGE-code similarity and personalized PageRank Top-K induced subgraph.",
                        reasoningNodeIds,
                        reasoningEdges,
                        graphData,
                        rankResult.rankScores
                )
        ));

        progressService.update(
                "context-prompt",
                "Assembling Java request, complete CodeMap, class skeletons, code, and graph relations.",
                7,
                8
        );
        result.setContextPrompt(contextPrompt(
                operation,
                oldDescription,
                newDescription,
                query,
                retrieval,
                currentCodeMapMethods,
                reasoningNodeIds,
                reasoningEdges,
                graphData
        ));
        return result;
    }

    private List<FeatureCandidate> loadFeatureCandidates(AgentLanguage language) {
        Integer repoId = ProjectState.getInstance().getRepoId();
        if (repoId == null) {
            return List.of();
        }
        List<FeatureCandidate> candidates = new ArrayList<>();
        for (ModuleResult module : codeMapService.readFeatureFromDatabase(repoId)) {
            String moduleDescription = localized(module.getModuleDesc(), module.getModuleDescCN(), language);
            for (FeatureResult feature : module.getFeatureList()) {
                candidates.add(new FeatureCandidate(
                        feature.getFeatureId(),
                        module.getModuleId(),
                        moduleDescription,
                        localized(feature.getFeatureDescription(), feature.getFeatureDescriptionCn(), language),
                        resolvedMethods(feature),
                        codeMapService.clusterMap(feature)
                ));
            }
        }
        return candidates;
    }

    private RetrievalResult retrieveFeatures(
            String operation,
            FeatureResult currentFeature,
            String query,
            List<FeatureCandidate> candidates
    ) throws IOException, InterruptedException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("mode", "retrieve");
        request.put("operation", operation);
        request.put("query", valueOrEmpty(query));
        request.put("featureId", currentFeature == null ? "" : String.valueOf(currentFeature.getFeatureId()));
        request.put("topKFeatures", positiveIntEnv("FOCUSGRAPH_TOP_K_FEATURES", 3));
        ArrayNode features = request.putArray("features");
        for (FeatureCandidate candidate : candidates) {
            ObjectNode feature = features.addObject();
            feature.put("featureId", String.valueOf(candidate.featureId));
            feature.put("moduleId", String.valueOf(candidate.moduleId));
            feature.put("moduleDesc", candidate.moduleDescription);
            feature.put("description", candidate.description);
            ArrayNode methods = feature.putArray("methods");
            candidate.methods.forEach(methods::add);
            ArrayNode clusterIds = feature.putArray("clusterIds");
            candidate.clusterIds.stream().sorted().forEach(clusterIds::add);
        }

        JsonNode response = runCli(request);
        RetrievalResult result = new RetrievalResult();
        result.selectedFeatures = objectMapper.convertValue(
                response.path("selectedFeatures"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, FocusGraphContextResult.SelectedFeature.class)
        );
        response.path("seedMethods").forEach(method -> result.seedMethods.add(method.asText()));
        return result;
    }

    private RetrievalResult forcedDeleteRetrieval(
            FeatureResult currentFeature,
            Map<Integer, FeatureCandidate> candidateById
    ) {
        RetrievalResult result = new RetrievalResult();
        if (currentFeature == null) {
            return result;
        }
        FeatureCandidate candidate = candidateById.get(currentFeature.getFeatureId());
        FocusGraphContextResult.SelectedFeature selected = new FocusGraphContextResult.SelectedFeature();
        selected.setRank(1);
        selected.setFeatureId(String.valueOf(currentFeature.getFeatureId()));
        selected.setClusterId(candidate == null ? "" : joinIntegers(candidate.clusterIds));
        selected.setModuleDesc(candidate == null ? "" : candidate.moduleDescription);
        selected.setDescription(candidate == null ? valueOrEmpty(currentFeature.getFeatureDescription()) : candidate.description);
        selected.setReason("forced_current_feature_delete_seed");
        selected.setScore(1.0);
        result.selectedFeatures.add(selected);
        result.seedMethods.addAll(resolvedMethods(currentFeature));
        return result;
    }

    private GraphData graphData(
            SKG expandedGraph,
            List<FeatureCandidate> selectedCandidates,
            List<FocusGraphContextResult.SelectedFeature> selectedFeatures,
            Set<String> seedMethods,
            Set<String> currentMethods
    ) {
        Map<Integer, Double> selectedScores = selectedFeatures.stream()
                .filter(feature -> parseInteger(feature.getFeatureId()) != null)
                .collect(Collectors.toMap(
                        feature -> parseInteger(feature.getFeatureId()),
                        feature -> feature.getScore() == null ? 0.0 : feature.getScore(),
                        Math::max,
                        LinkedHashMap::new
                ));
        Map<String, String> ownerByNode = ownerByNode(expandedGraph);
        Map<String, JavaNodeData> nodes = expandedGraph.vertexSet().stream()
                .sorted(Comparator.comparing(Vertex::getId))
                .map(vertex -> javaNodeData(
                        vertex,
                        ownerByNode.get(vertex.getId()),
                        selectedCandidates,
                        selectedScores,
                        seedMethods,
                        currentMethods
                ))
                .collect(Collectors.toMap(node -> node.id, node -> node, (left, right) -> left, LinkedHashMap::new));
        List<GraphEdgeData> edges = expandedGraph.edgeSet().stream()
                .map(this::graphEdgeData)
                .filter(edge -> nodes.containsKey(edge.from) && nodes.containsKey(edge.to))
                .sorted(Comparator.comparing((GraphEdgeData edge) -> edge.from)
                        .thenComparing(edge -> edge.to)
                        .thenComparing(edge -> edge.type))
                .toList();
        return new GraphData(nodes, edges);
    }

    private JavaNodeData javaNodeData(
            Vertex<?> vertex,
            String ownerClass,
            List<FeatureCandidate> selectedCandidates,
            Map<Integer, Double> selectedScores,
            Set<String> seedMethods,
            Set<String> currentMethods
    ) {
        List<String> sourceFeatureIds = new ArrayList<>();
        double selectedFeatureScore = 0.0;
        for (FeatureCandidate candidate : selectedCandidates) {
            if (Collections.disjoint(candidate.clusterIds, vertex.getClusterIds())) {
                continue;
            }
            sourceFeatureIds.add(String.valueOf(candidate.featureId));
            selectedFeatureScore = Math.max(selectedFeatureScore, selectedScores.getOrDefault(candidate.featureId, 0.0));
        }
        String category = category(vertex.getDeclaration());
        String resolvedOwner = ownerClass;
        if (resolvedOwner == null && vertex.getDeclaration() instanceof TypeDeclaration<?>) {
            resolvedOwner = vertex.getId();
        }
        return new JavaNodeData(
                vertex.getId(),
                label(vertex),
                category,
                seedMethods.contains(vertex.getId()) ? "original" : "expand",
                isCallable(vertex.getDeclaration()) ? vertex.getId() : "",
                sourceFile(vertex.getDeclaration()),
                resolvedOwner,
                nodeText(vertex.getDeclaration()),
                selectedFeatureScore,
                seedMethods.contains(vertex.getId()),
                currentMethods.contains(vertex.getId()),
                sourceFeatureIds,
                vertex
        );
    }

    private Set<String> initialNodeIds(
            SKG expandedGraph,
            Map<String, JavaNodeData> nodes,
            Set<String> seedMethods
    ) {
        Set<String> initial = seedMethods.stream()
                .filter(nodes::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Arc edge : expandedGraph.edgeSet()) {
            if (!(edge instanceof ClassArc.MemberArc) || !initial.contains(edge.getTarget().getId())) {
                continue;
            }
            initial.add(edge.getSource().getId());
        }
        return initial;
    }

    private RankResult rankGraph(String query, GraphData graphData) throws IOException, InterruptedException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("mode", "rank");
        request.put("query", valueOrEmpty(query));
        request.put("topKNodes", positiveIntEnv("FOCUSGRAPH_TOP_K_NODES", 15));
        ArrayNode nodes = request.putArray("nodes");
        graphData.nodes.values().forEach(node -> {
            ObjectNode item = nodes.addObject();
            item.put("id", node.id);
            item.put("category", node.category);
            item.put("text", node.text);
            item.put("selectedFeatureScore", node.selectedFeatureScore);
            item.put("seed", node.seed);
            item.put("currentFeature", node.currentFeature);
        });
        ArrayNode edges = request.putArray("edges");
        graphData.edges.forEach(edge -> {
            ObjectNode item = edges.addObject();
            item.put("from", edge.from);
            item.put("to", edge.to);
            item.put("type", edge.type);
        });

        JsonNode response = runCli(request);
        RankResult result = new RankResult();
        response.path("selectedNodeIds").forEach(node -> result.selectedNodeIds.add(node.asText()));
        response.path("rankScores").fields().forEachRemaining(entry -> result.rankScores.put(entry.getKey(), entry.getValue().asDouble()));
        return result;
    }

    private FocusGraphContextResult.ReasoningGraph reasoningGraph(
            Collection<String> nodeIds,
            List<GraphEdgeData> edges,
            GraphData graphData,
            Map<String, Double> scores
    ) {
        FocusGraphContextResult.ReasoningGraph graph = new FocusGraphContextResult.ReasoningGraph();
        graph.setNodes(graphNodes(nodeIds, graphData, scores));
        graph.setEdges(graphEdges(edges));
        return graph;
    }

    private FocusGraphContextResult.GraphStage graphStage(
            String id,
            String label,
            String description,
            Collection<String> nodeIds,
            List<GraphEdgeData> edges,
            GraphData graphData,
            Map<String, Double> scores
    ) {
        FocusGraphContextResult.GraphStage stage = new FocusGraphContextResult.GraphStage();
        stage.setId(id);
        stage.setLabel(label);
        stage.setDescription(description);
        stage.setNodes(graphNodes(nodeIds, graphData, scores));
        stage.setEdges(graphEdges(edges));
        return stage;
    }

    private List<FocusGraphContextResult.Node> graphNodes(
            Collection<String> nodeIds,
            GraphData graphData,
            Map<String, Double> scores
    ) {
        return nodeIds.stream()
                .map(graphData.nodes::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(node -> node.id))
                .map(node -> nodeResult(node, scores.get(node.id)))
                .toList();
    }

    private List<FocusGraphContextResult.Edge> graphEdges(List<GraphEdgeData> edges) {
        return edges.stream().map(edge -> {
            FocusGraphContextResult.Edge result = new FocusGraphContextResult.Edge();
            result.setFrom(edge.from);
            result.setTo(edge.to);
            result.setType(edge.type);
            return result;
        }).toList();
    }

    private FocusGraphContextResult.Node nodeResult(JavaNodeData node, Double score) {
        FocusGraphContextResult.Node result = new FocusGraphContextResult.Node();
        result.setId(node.id);
        result.setLabel(node.label);
        result.setCategory(node.category);
        result.setSrcType(node.srcType);
        result.setMethodSignature(node.methodSignature);
        result.setFuncFile(node.funcFile);
        result.setOwnerClass(node.ownerClass);
        result.setSourceFeatureIds(node.sourceFeatureIds);
        result.setScore(score);
        return result;
    }

    private String contextPrompt(
            String operation,
            String oldDescription,
            String newDescription,
            String query,
            RetrievalResult retrieval,
            Set<String> currentMethods,
            Set<String> reasoningNodeIds,
            List<GraphEdgeData> reasoningEdges,
            GraphData graphData
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## Java ").append(capitalize(operation)).append(" Feature Request\n\n");
        if ("modify".equals(operation)) {
            prompt.append("Old feature description:\n").append(valueOrEmpty(oldDescription)).append("\n\n");
            prompt.append("New feature description:\n").append(valueOrEmpty(newDescription)).append("\n\n");
            prompt.append("Delta query used for Java reasoning graph retrieval:\n").append(valueOrEmpty(query)).append("\n\n");
        } else if ("add".equals(operation)) {
            prompt.append("New feature description:\n").append(valueOrEmpty(newDescription)).append("\n\n");
            prompt.append("Query used for Java reasoning graph retrieval:\n").append(valueOrEmpty(query)).append("\n\n");
        } else {
            prompt.append("Feature description to delete:\n").append(valueOrEmpty(oldDescription)).append("\n\n");
            prompt.append("Query used for Java reasoning graph ranking:\n").append(valueOrEmpty(query)).append("\n\n");
        }

        prompt.append("## Retrieved Similar Features\n\n");
        for (FocusGraphContextResult.SelectedFeature feature : retrieval.selectedFeatures) {
            prompt.append("- rank=").append(feature.getRank())
                    .append(" featureId=").append(feature.getFeatureId())
                    .append(" reason=").append(feature.getReason())
                    .append(" score=").append(feature.getScore())
                    .append(" description=").append(valueOrEmpty(feature.getDescription()))
                    .append("\n");
        }
        prompt.append("\n## Seed Methods\n\n");
        retrieval.seedMethods.forEach(method -> prompt.append("- ").append(method).append("\n"));

        if (!"add".equals(operation)) {
            prompt.append("\n## Current Feature Complete CodeMap\n\n");
            appendCurrentCodeMap(prompt, currentMethods, graphData);
        }

        prompt.append("\n## Java Reasoning Context\n");
        appendReasoningContext(prompt, reasoningNodeIds, reasoningEdges, graphData);
        return prompt.toString();
    }

    private void appendCurrentCodeMap(StringBuilder prompt, Set<String> currentMethods, GraphData graphData) {
        VertexMap vertexMap = VertexMap.getInstance();
        for (String methodId : currentMethods.stream().sorted().toList()) {
            Vertex<CallableDeclaration<?>> vertex = vertexMap == null ? null : vertexMap.getMethodDeclaration(methodId);
            if (vertex == null) {
                prompt.append("### ").append(methodId).append("\n\nCode could not be resolved from the current SKG.\n\n");
                continue;
            }
            JavaNodeData node = graphData.nodes.get(methodId);
            prompt.append("### ").append(methodId).append("\n\n");
            if (node != null && node.funcFile != null && !node.funcFile.isBlank()) {
                prompt.append("File: ").append(node.funcFile).append("\n\n");
            }
            prompt.append("```java\n").append(vertex.getDeclaration()).append("\n```\n\n");
        }
    }

    private void appendReasoningContext(
            StringBuilder prompt,
            Set<String> reasoningNodeIds,
            List<GraphEdgeData> reasoningEdges,
            GraphData graphData
    ) {
        Map<String, List<JavaNodeData>> nodesByFile = reasoningNodeIds.stream()
                .map(graphData.nodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        node -> node.funcFile == null || node.funcFile.isBlank() ? "unknown" : node.funcFile,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        for (Map.Entry<String, List<JavaNodeData>> entry : nodesByFile.entrySet()) {
            String file = entry.getKey();
            List<JavaNodeData> fileNodes = entry.getValue();
            prompt.append("\n### File: ").append(file).append("\n\n");
            Set<String> ownerClasses = fileNodes.stream()
                    .map(node -> node.ownerClass)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String ownerClass : ownerClasses) {
                JavaNodeData owner = graphData.nodes.get(ownerClass);
                if (owner != null) {
                    prompt.append("#### Class: ").append(ownerClass).append("\n\nClass skeleton:\n```java\n")
                            .append(owner.text)
                            .append("\n```\n\n");
                }
                List<JavaNodeData> declarations = fileNodes.stream()
                        .filter(node -> Objects.equals(ownerClass, node.ownerClass))
                        .filter(node -> !node.id.equals(ownerClass))
                        .sorted(Comparator.comparing(node -> node.id))
                        .toList();
                if (!declarations.isEmpty()) {
                    prompt.append("Selected declarations:\n");
                    for (JavaNodeData declaration : declarations) {
                        prompt.append("```java\n").append(declaration.vertex.getDeclaration()).append("\n```\n");
                    }
                    prompt.append("\n");
                }
            }
            List<GraphEdgeData> fileRelations = reasoningEdges.stream()
                    .filter(edge -> belongsToFile(graphData.nodes.get(edge.from), file)
                            || belongsToFile(graphData.nodes.get(edge.to), file))
                    .toList();
            if (!fileRelations.isEmpty()) {
                prompt.append("Relations:\n\n");
                fileRelations.forEach(edge -> prompt.append("- ")
                        .append(edge.from).append(" -> ").append(edge.to)
                        .append(" [").append(edge.type).append("]\n"));
            }
        }
    }

    private boolean belongsToFile(JavaNodeData node, String file) {
        return node != null && Objects.equals(node.funcFile == null || node.funcFile.isBlank() ? "unknown" : node.funcFile, file);
    }

    private Map<String, String> ownerByNode(SKG graph) {
        Map<String, String> owners = new HashMap<>();
        for (Arc edge : graph.edgeSet()) {
            if (edge instanceof ClassArc.MemberArc) {
                owners.put(edge.getTarget().getId(), edge.getSource().getId());
            }
        }
        return owners;
    }

    private GraphEdgeData graphEdgeData(Arc edge) {
        return new GraphEdgeData(edge.getSource().getId(), edge.getTarget().getId(), edgeType(edge));
    }

    private String edgeType(Arc edge) {
        if (edge instanceof CallArc.NormalCallArc) {
            return "CallArc";
        }
        return edge.getLabel() == null || edge.getLabel().isBlank()
                ? edge.getClass().getSimpleName()
                : edge.getLabel();
    }

    private String category(BodyDeclaration<?> declaration) {
        if (declaration instanceof ClassOrInterfaceDeclaration type) {
            return type.isInterface() ? "Interface" : "Class";
        }
        if (declaration instanceof EnumDeclaration) return "Enum";
        if (declaration instanceof AnnotationDeclaration) return "Annotation";
        if (declaration instanceof MethodDeclaration) return "Method";
        if (declaration instanceof ConstructorDeclaration) return "Constructor";
        if (declaration instanceof InitializerDeclaration) return "Initializer";
        if (declaration instanceof FieldDeclaration) return "Field";
        if (declaration instanceof AnnotationMemberDeclaration) return "AnnotationMember";
        return declaration.getClass().getSimpleName();
    }

    private String label(Vertex<?> vertex) {
        BodyDeclaration<?> declaration = vertex.getDeclaration();
        if (declaration instanceof CallableDeclaration<?> callable) {
            return callable.getSignature().asString();
        }
        if (declaration instanceof TypeDeclaration<?> type) {
            return type.getNameAsString();
        }
        return vertex.getId();
    }

    private String nodeText(BodyDeclaration<?> declaration) {
        if (declaration instanceof TypeDeclaration<?> type) {
            return classSkeleton(type);
        }
        return declaration.toString();
    }

    private String classSkeleton(TypeDeclaration<?> declaration) {
        TypeDeclaration<?> skeleton = declaration.clone();
        skeleton.findAll(MethodDeclaration.class).forEach(method -> method.removeBody());
        skeleton.findAll(ConstructorDeclaration.class).forEach(constructor -> constructor.getBody().getStatements().clear());
        skeleton.findAll(InitializerDeclaration.class).forEach(Node::remove);
        return skeleton.toString();
    }

    private String sourceFile(BodyDeclaration<?> declaration) {
        try {
            Path source = declaration.findCompilationUnit().orElseThrow().getStorage().orElseThrow().getPath().toAbsolutePath().normalize();
            Path preprocessRoot = Path.of(ProjectState.getInstance().getPreprocess2Path()).toAbsolutePath().normalize();
            if (source.startsWith(preprocessRoot)) {
                return preprocessRoot.relativize(source).toString().replace(File.separatorChar, '/');
            }
            Path sourceRoot = Path.of(ProjectState.getInstance().getSrcPath()).toAbsolutePath().normalize();
            if (source.startsWith(sourceRoot)) {
                return sourceRoot.relativize(source).toString().replace(File.separatorChar, '/');
            }
            return source.getFileName().toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isCallable(BodyDeclaration<?> declaration) {
        return declaration instanceof CallableDeclaration<?> || declaration instanceof InitializerDeclaration;
    }

    private List<String> resolvedMethods(FeatureResult feature) {
        if (feature == null || feature.getCandidateMethods() == null) {
            return List.of();
        }
        Set<String> methods = new LinkedHashSet<>();
        feature.getCandidateMethods().forEach(candidate -> {
            if (candidate.getLxtFull() == null) return;
            candidate.getLxtFull().forEach(full -> {
                if (full.getLxtFullSignature() != null && !full.getLxtFullSignature().isBlank()) {
                    methods.add(full.getLxtFullSignature());
                }
            });
        });
        return new ArrayList<>(methods);
    }

    private List<String> allCodeMapMethods(FeatureResult feature) {
        if (feature == null || feature.getCandidateMethods() == null) {
            return List.of();
        }
        Set<String> methods = new LinkedHashSet<>();
        feature.getCandidateMethods().forEach(candidate -> {
            boolean resolved = candidate.getLxtFull() != null && !candidate.getLxtFull().isEmpty();
            if (resolved) {
                candidate.getLxtFull().forEach(full -> {
                    if (full.getLxtFullSignature() != null && !full.getLxtFullSignature().isBlank()) {
                        methods.add(full.getLxtFullSignature());
                    }
                });
            } else if (candidate.getZyfShortSignature() != null && !candidate.getZyfShortSignature().isBlank()) {
                methods.add(candidate.getZyfShortSignature());
            }
        });
        return new ArrayList<>(methods);
    }

    private JsonNode runCli(JsonNode request) throws IOException, InterruptedException {
        String pythonExec = envOrDefault("REPOSUMMARY_PYTHON", "python3");
        String repoSummaryDir = envOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        ProcessBuilder processBuilder = new ProcessBuilder(pythonExec, "src/java_graph_rank_cli.py");
        processBuilder.directory(new File(repoSummaryDir));
        Process process = processBuilder.start();
        ProjectState.CapturedContext projectContext = ProjectState.capture();
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                () -> projectContext.call(() -> readStream(process.getInputStream()))
        );
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                () -> projectContext.call(() -> readProgressStream(process.getErrorStream()))
        );
        try (OutputStream stdin = process.getOutputStream()) {
            objectMapper.writeValue(stdin, request);
        }

        int timeout = positiveIntEnv("JAVA_GRAPH_CONTEXT_TIMEOUT_SECONDS", 900);
        boolean exited = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String stdout = readCompleted(stdoutFuture);
        String stderr = readCompleted(stderrFuture);
        if (!exited) {
            throw new IOException("Java graph context CLI timed out.\n" + stderr);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Java graph context CLI failed with exit code " + process.exitValue()
                    + "\nSTDERR:\n" + stderr + "\nSTDOUT:\n" + stdout);
        }
        return objectMapper.readTree(stdout);
    }

    private String readStream(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readProgressStream(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (line.startsWith(PROGRESS_PREFIX)) {
                    updateProgress(line.substring(PROGRESS_PREFIX.length()));
                } else if (!line.isBlank()) {
                    System.out.println("[JavaGraphCLI] " + line);
                }
            }
            return output.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void updateProgress(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Map<String, Object> details = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (Set.of("stage", "message", "step", "total").contains(key)) return;
                JsonNode value = entry.getValue();
                details.put(key, value.isNumber() ? value.numberValue() : value.asText());
            });
            progressService.update(
                    root.path("stage").asText("java-graph"),
                    root.path("message").asText("Building Java reasoning graph."),
                    root.path("step").asInt(3),
                    root.path("total").asInt(8),
                    details
            );
        } catch (Exception ignored) {
        }
    }

    private String readCompleted(CompletableFuture<String> future) throws IOException, InterruptedException {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof UncheckedIOException uncheckedIOException) {
                throw uncheckedIOException.getCause();
            }
            throw new IOException("Failed to read Java graph context CLI output.", cause);
        } catch (TimeoutException exception) {
            throw new IOException("Timed out while reading Java graph context CLI output.", exception);
        }
    }

    private int positiveIntEnv(String name, int defaultValue) {
        try {
            int value = Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(defaultValue)).trim());
            return value > 0 ? value : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String localized(String english, String chinese, AgentLanguage language) {
        if (language == AgentLanguage.CN && chinese != null && !chinese.isBlank()) return chinese;
        if (english != null && !english.isBlank()) return english;
        return valueOrEmpty(chinese);
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String joinIntegers(Set<Integer> values) {
        return values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String capitalize(String value) {
        return value == null || value.isBlank() ? "" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class RetrievalResult {
        private List<FocusGraphContextResult.SelectedFeature> selectedFeatures = new ArrayList<>();
        private List<String> seedMethods = new ArrayList<>();
    }

    private static class RankResult {
        private final List<String> selectedNodeIds = new ArrayList<>();
        private final Map<String, Double> rankScores = new LinkedHashMap<>();
    }

    private record FeatureCandidate(
            Integer featureId,
            Integer moduleId,
            String moduleDescription,
            String description,
            List<String> methods,
            Set<Integer> clusterIds
    ) {
    }

    private record GraphData(Map<String, JavaNodeData> nodes, List<GraphEdgeData> edges) {
    }

    private record GraphEdgeData(String from, String to, String type) {
    }

    private record JavaNodeData(
            String id,
            String label,
            String category,
            String srcType,
            String methodSignature,
            String funcFile,
            String ownerClass,
            String text,
            double selectedFeatureScore,
            boolean seed,
            boolean currentFeature,
            List<String> sourceFeatureIds,
            Vertex<?> vertex
    ) {
        private boolean isCallable() {
            return "Method".equals(category) || "Constructor".equals(category) || "Initializer".equals(category);
        }

        private JavaNodeData withSrcType(String nextSrcType) {
            return new JavaNodeData(
                    id,
                    label,
                    category,
                    nextSrcType,
                    methodSignature,
                    funcFile,
                    ownerClass,
                    text,
                    selectedFeatureScore,
                    seed,
                    currentFeature,
                    sourceFeatureIds,
                    vertex
            );
        }
    }
}
