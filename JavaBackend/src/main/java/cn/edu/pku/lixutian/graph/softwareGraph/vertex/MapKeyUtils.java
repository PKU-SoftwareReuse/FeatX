package cn.edu.pku.lixutian.graph.softwareGraph.vertex;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.resolution.declarations.ResolvedClassDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

public class MapKeyUtils {
    public static String mapKey(TypeDeclaration<?> n) {
        return n.getFullyQualifiedName().orElseThrow();
    }

    public static String mapKey(ResolvedClassDeclaration n) {
        return n.getQualifiedName();
    }

    public static String mapKey(ResolvedReferenceType n) {
        if (n == null) {
            return null;
        }
        return n.getQualifiedName();
    }

    public static String mapKey(ResolvedType n) {
        assert n instanceof ResolvedReferenceType;
        return ((ResolvedReferenceType) n).getQualifiedName();
    }

    // 这里可能有问题
    public static String mapKey(FieldDeclaration n, TypeDeclaration<?> c) {
        assert n.getVariables().size() == 1;
        FieldDeclaration pure = n.clone();
        pure.getComment().ifPresent(Comment::remove);
        return c.getFullyQualifiedName().orElseThrow() + "." + pure.toString();
    }

    public static String mapKey(CallableDeclaration<?> n, TypeDeclaration<?> c) {
        return c.getFullyQualifiedName().orElseThrow() + "." + n.getSignature().asString();
    }

    public static String mapKey(AnnotationMemberDeclaration n, TypeDeclaration<?> c) {
        return c.getFullyQualifiedName().orElseThrow() + "." + n.getNameAsString();
    }

    public static String mapKey(InitializerDeclaration n, TypeDeclaration<?> c) {
        return c.getFullyQualifiedName().orElseThrow() + "." + n.toString();
    }
}
