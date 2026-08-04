package com.mycode.graph.softwareGraph.vertex;

import com.github.javaparser.ast.Node;

import java.util.Objects;

public class ASTUtils {

    /**
     * Compares two JavaParser nodes and their ranges (position in the file). If you need to compare between nodes
     * from different files, you may want to use {@link #equalsWithRangeInCU(Node, Node)}
     */
    public static boolean equalsWithRange(Node node1, Node node2) {
        if (node1 == null || node2 == null) {
            return node1 == node2;
        }
        return Objects.equals(node1.getRange(), node2.getRange()) && Objects.equals(node1, node2);
    }

    /**
     * Compares two JavaParser nodes, their ranges (position in the file) and compilation units (file they're in).
     * If the nodes belong to the same CU or have no CU, you can use {@link #equalsWithRange(Node, Node)}
     */
    public static boolean equalsWithRangeInCU(Node node1, Node node2) {
        if (node1 == null || node2 == null) {
            return node1 == node2;
        }
        return node1.findCompilationUnit().equals(node2.findCompilationUnit()) && equalsWithRange(node1, node2);
    }
}
