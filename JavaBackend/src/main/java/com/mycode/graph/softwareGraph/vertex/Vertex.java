package com.mycode.graph.softwareGraph.vertex;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * A vertex containing the declaration it represents. It only exists because
 * JGraphT relies heavily on equals comparison, which may not be correct in declarations.
 */
public class Vertex<T extends BodyDeclaration<?>> {
    @Getter
    protected final T declaration;

    @Getter
    private final String id;

    @Getter
    @Setter
    private Set<Integer> clusterIds;

//    @Getter
//    @Setter
//    private boolean toBeDeleted;

    public Vertex(T declaration, String id) {
        this.declaration = declaration;
        this.id = id;
        this.clusterIds = new HashSet<>();
//        this.toBeDeleted = false;
    }


    @Override
    public String toString() {
        return declaration.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Vertex<?> && ASTUtils.equalsWithRangeInCU(declaration, ((Vertex<?>) o).declaration);
    }

    @Override
    public int hashCode() {
        if (declaration instanceof NodeWithName<?>) {
            return Objects.hash(((NodeWithName<?>) declaration).getNameAsString());
        }
        if (declaration instanceof NodeWithSimpleName<?>) {
            return Objects.hash(((NodeWithSimpleName<?>) declaration).getNameAsString());
        }
        if (declaration instanceof FieldDeclaration) {
            return Objects.hash(String.valueOf(declaration));
        }
        if (declaration instanceof InitializerDeclaration) {
            return Objects.hash(String.valueOf(declaration));
        }
        throw new IllegalStateException("Invalid Vertex in Graph");
    }
}
