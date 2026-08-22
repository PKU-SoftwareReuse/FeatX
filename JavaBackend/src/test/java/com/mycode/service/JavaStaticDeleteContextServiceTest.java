package com.mycode.service;

import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.arc.ClassArc;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaStaticDeleteContextServiceTest {
    private static final int REPOSITORY_ID = 98;

    @AfterEach
    void cleanUp() {
        SKG.clearRepository(REPOSITORY_ID);
        VertexMap.clearRepository(REPOSITORY_ID);
        ClusterState.getInstance().setClusterIds(null);
        ProjectState.getInstance().setRepoId(null);
    }

    @Test
    void rendersCompleteLegacyDeleteDiffForAffectedClass(@TempDir Path projectRoot) throws Exception {
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Feature.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                public class Feature {
                    public void removeMe() {
                    }

                    public void keepMe() {
                    }
                }
                """);
        ProjectState project = ProjectState.getInstance();
        project.setProjectPath(projectRoot.toString(), "JAVA");
        project.setRepoId(REPOSITORY_ID);

        CompilationUnit compilationUnit = StaticJavaParser.parse(sourceFile);
        VertexMap vertexMap = VertexMap.getNewInstance();
        vertexMap.build(new NodeList<>(compilationUnit));
        Vertex<TypeDeclaration<?>> type = vertexMap.getClassDeclaration("demo.Feature");
        Vertex<CallableDeclaration<?>> remove = vertexMap.getMethodDeclarationMap().values().stream()
                .filter(vertex -> vertex.getDeclaration().getNameAsString().equals("removeMe"))
                .findFirst().orElseThrow();
        Vertex<CallableDeclaration<?>> keep = vertexMap.getMethodDeclarationMap().values().stream()
                .filter(vertex -> vertex.getDeclaration().getNameAsString().equals("keepMe"))
                .findFirst().orElseThrow();

        type.getClusterIds().addAll(Set.of(7, 9));
        remove.getClusterIds().add(7);
        keep.getClusterIds().add(9);
        SKG graph = SKG.getNewInstance();
        graph.addVertex(type);
        graph.addVertex(remove);
        graph.addVertex(keep);
        ClassArc.MemberArc.MethodMember removeMember = new ClassArc.MemberArc.MethodMember();
        removeMember.getClusterIds().add(7);
        graph.addEdge(type, remove, removeMember);
        ClassArc.MemberArc.MethodMember keepMember = new ClassArc.MemberArc.MethodMember();
        keepMember.getClusterIds().add(9);
        graph.addEdge(type, keep, keepMember);
        ClusterState.getInstance().setClusterIds(Set.of(7));

        String context = new JavaStaticDeleteContextService().buildContext();

        assertTrue(context.contains("## Deterministic Java Static Deletion Context"));
        assertTrue(context.contains("STATIC_DELETE_AFFECTED_FILE: src/main/java/demo/Feature.java"));
        assertTrue(context.contains("-    public void removeMe()"));
        assertTrue(context.contains("public void keepMe()"));
        assertFalse(context.contains("STATIC_DELETE_AFFECTED_FILE: src/main/java/demo/Keep.java"));
    }
}
