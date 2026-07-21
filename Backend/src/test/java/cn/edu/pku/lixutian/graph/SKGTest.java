package cn.edu.pku.lixutian.graph;

import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SKGTest {
    @Test
    void getMaxGraphFiltersByExplicitClusterIdsWithoutClusterState() {
        SKG graph = new SKG();
        Vertex<ClassOrInterfaceDeclaration> selectedClass = new Vertex<>(
                new ClassOrInterfaceDeclaration().setName("Selected"),
                "demo.Selected"
        );
        Vertex<MethodDeclaration> selectedMethod = new Vertex<>(
                new MethodDeclaration().setName("run").setType("void"),
                "demo.Selected.run()"
        );
        Vertex<ClassOrInterfaceDeclaration> otherClass = new Vertex<>(
                new ClassOrInterfaceDeclaration().setName("Other"),
                "demo.Other"
        );
        selectedClass.getClusterIds().add(7);
        selectedMethod.getClusterIds().add(7);
        otherClass.getClusterIds().add(9);
        graph.addVertex(selectedClass);
        graph.addVertex(selectedMethod);
        graph.addVertex(otherClass);
        ClassArc.MemberArc.MethodMember member = new ClassArc.MemberArc.MethodMember();
        member.getClusterIds().add(7);
        graph.addEdge(selectedClass, selectedMethod, member);

        SKG result = graph.getMaxGraph(Set.of(7));

        assertTrue(result.vertexSet().size() == 2);
        assertTrue(result.vertexSet().contains(selectedClass));
        assertTrue(result.vertexSet().contains(selectedMethod));
        assertTrue(result.edgeSet().size() == 1);
        assertTrue(result.containsEdge(selectedClass, selectedMethod));
    }
}
