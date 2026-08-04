package com.mycode.dto.result;

import com.mycode.graph.softwareGraph.SoftwareGraph;
import com.mycode.graph.softwareGraph.arc.Arc;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
public class GraphResult{
    private Set<Node> nodes;
    private Set<Edge> edges;

    public GraphResult() {
        nodes = new HashSet<>();
        edges = new HashSet<>();
    }

    public GraphResult(SoftwareGraph<?> softwareGraph) {
        nodes = softwareGraph.vertexSet().stream().map(Node::new).collect(Collectors.toSet());
        edges = softwareGraph.edgeSet().stream().map(Edge::new).collect(Collectors.toSet());
    }

    @Getter
    @Setter
    public static class Node {
        private String id;
        private String label;
        private String type;

        public Node(Vertex<?> vertex) {
            this.id = vertex.getId();
            this.label = vertex.getId();
            this.type = computeType(vertex.getDeclaration());
        }

        private String computeType(BodyDeclaration<?> declaration) {
            if (declaration.isClassOrInterfaceDeclaration()) {
                if (((ClassOrInterfaceDeclaration) declaration).isInterface()) {
                    return "Interface";
                } else {
                    return "Class";
                }
            } else if (declaration.isEnumDeclaration()) {
                return "Enum";
            } else if (declaration.isMethodDeclaration()) {
                return "Method";
            } else if (declaration.isConstructorDeclaration()) {
                return "Constructor";
            } else if (declaration.isFieldDeclaration()) {
                return "Field";
            } else if (declaration.isInitializerDeclaration()) {
                if (declaration.asInitializerDeclaration().isStatic()) {
                    return "StaticInitializer";
                } else {
                    return "NormalInitializer";
                }
            } else if (declaration.isAnnotationDeclaration()) {
                return "Annotation";
            } else if (declaration.isAnnotationMemberDeclaration()) {
                return "AnnotationMember";
            } else {
                throw new RuntimeException("未知的类型：Class/Method/Field/Enum/Annotation/AnnotationMember/StaticInitializer/NormalInitializer/???");
            }
        }
    }

    @Getter
    @Setter
    class Edge {
        private String id;
        private String from;
        private String to;
        private String label;

        public Edge(Arc arc) {
            this.from = arc.getSource().getId();
            this.to = arc.getTarget().getId();
            this.label = arc.getLabel();
        }
        // 生成 hashCode
        @Override
        public int hashCode() {
            return Objects.hash(from, to, label);
        }

        // 生成 equals 方法，确保哈希集合中不会重复
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Edge other = (Edge) obj;
            return Objects.equals(from, other.from)
                    && Objects.equals(to, other.to)
                    && Objects.equals(label, other.label);
        }

        // 可选：toString 方法便于调试
        @Override
        public String toString() {
            return "Edge{" + "from='" + from + '\'' + ", to='" + to + '\'' + ", label='" + label + '\'' + '}';
        }

    }
}
