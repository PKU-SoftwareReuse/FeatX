package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dao.GraphEdge;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.Arc;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.CodeDiffHelper;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
public class FeatureGraphResult {
    private Set<Node> nodes;
    private Set<Edge> edges;

    public FeatureGraphResult(Set<Node> nodes, Set<Edge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public FeatureGraphResult(SKG skg) {
        nodes = new HashSet<>();
        edges = new HashSet<>();

        // Step 1: 收集 Class 节点并构建 Node
        Map<String, Vertex<?>> classVertexMap = new HashMap<>(); // id -> vertex
        skg.vertexSet().forEach(vertex -> {
            if (isClassVertex(vertex)) {
                classVertexMap.put(vertex.getId(), vertex);

                Node newNode = new Node(vertex.getId());
                skg.outgoingEdgesOf(vertex).stream()
                        .filter(ClassArc.MemberArc.class::isInstance)
                        .map(arc -> arc.getTarget())
                        .filter(vertex1 -> vertex1.getDeclaration() instanceof CallableDeclaration<?>)
                        .filter(vertex1 -> {
                            for (AnnotationExpr annotation : vertex1.getDeclaration().getAnnotations()) {
                                if (annotation.getNameAsString().equals("lombok.Generated")
                                        || annotation.getNameAsString().equals("ltm.Generated")) {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .forEach(vertex1 -> {
                            String longName = vertex1.getId();
                            String shortName = longName.substring(longName.lastIndexOf(".") + 1);
                            newNode.addMethod(shortName);
                        });

                nodes.add(newNode);
            }
        });
        // Step2: 交给外部Database来做了
        // Step 2: 对每个 Class 节点 BFS 找可达的其他 Class 节点
//        for (Vertex<?> startClass : classVertexMap.values()) {
//            Set<Vertex<?>> visited = new HashSet<>();
//            Queue<Vertex<?>> queue = new LinkedList<>();
//            queue.add(startClass);
//
//            while (!queue.isEmpty()) {
//                Vertex<?> current = queue.poll();
//                for (Arc edge : skg.outgoingEdgesOf(current)) {
//                    Vertex<?> target = edge.getTarget();
//
//                    if (visited.contains(target)) continue;
//                    visited.add(target);
//
//                    if (isClassVertex(target)) {
//                        // 找到另一个 Class 节点 → 新图加边
//                        edges.add(new Edge(startClass.getId(), target.getId()));
//                    } else {
//                        // 继续搜索非 Class 节点
//                        queue.add(target);
//                    }
//                }
//            }
//        }

    }

    public FeatureGraphResult(Set<String> nodeIds) {
        nodes = new HashSet<>();
        nodeIds.forEach(nodeId -> {
            nodes.add(new Node(nodeId));
        });
        edges = new HashSet<>();
    }

    private boolean isClassVertex(Vertex<?> vertex) {
        return vertex.getDeclaration().isClassOrInterfaceDeclaration();
    }

    public FeatureGraphResult setNewType(FeatureGraphResult newGraph) {
        Set<String> newNodeIds = new HashSet<>();
        newGraph.nodes.forEach(node -> {
            newNodeIds.add(node.getId());
        });
        nodes.forEach(node -> {
            if (newNodeIds.contains(node.getId())) {
                node.setType("Modify");
                newNodeIds.remove(node.getId());
            }
        });
        newGraph.nodes.forEach(node -> {
            if (newNodeIds.contains(node.getId())) {
                node.setType("Modify");
                nodes.add(node);
                newNodeIds.remove(node.getId());
            }
        });
        return this;
    }

    public FeatureGraphResult setDebloatType() {
        nodes.forEach(node -> {
            VertexMap vertexMap = VertexMap.getInstance();
            Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(node.getId());
            boolean isSame = CodeDiffHelper.justifyDeleteDiff(SKG.getInstance(), classVertex, ClusterState.getInstance().getClusterIds());
            if (!isSame) {
                node.setType("Modify");
            }
        });
        return this;
    }

    @Getter
    @Setter
    public static class Node {
        private String id;
        private String label;
        private String type;
        private List<String> methods;

        public Node(String id) {
            this.id = id;
            this.label = id;
            this.methods = new ArrayList<>();
            this.type = "Default";
        }

        public Node(String id, List<String> methods) {
            this.id = id;
            this.label = id;
            this.methods = methods;
            this.type = "Default";
        }

        public void addMethod(String method) {
            this.methods.add(method);
        }
    }

    @Getter
    @Setter
    public static class Edge {
        private String id;
        private String from;
        private String to;

        public Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public Edge(Node from, Node to) {
            this.from = from.id;
            this.to = to.id;
        }

    }
}
