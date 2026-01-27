package cn.edu.pku.lixutian.helper.graphAggregationHelper;

import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DeleteHelper extends GraphAggregationHelper {

    public DeleteHelper(SKG graph, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        super(graph, topVertex, clusterIds);
    }

    @Override
    public String generateCode() {
        {
            if (clusterIds.containsAll(topVertex.getClusterIds())) {
                return "";
            }
            graph.vertexSet().stream()
                    .filter(vertex -> isClassVertex(vertex))
                    .forEach(vertex -> {
                        classPureDeclarationMap.put(vertex.getId(), classVertex2Declaration(vertex));
                        classFullDeclarationMap.put(vertex.getId(), (TypeDeclaration<?>) vertex.getDeclaration());
                    });
            BodyDeclaration<?> declaration = topClassVertex2Declaration(topVertex);
            return declaration.toString();
        }
    }

    @Override
    protected TypeDeclaration<?> classVertex2Declaration(Vertex<?> vertex) {
        TypeDeclaration<?> pureDeclaration = (TypeDeclaration<?>) vertex.getDeclaration().clone();
        pureDeclaration.setParentNode(null);

        List<Node> toRemove = pureDeclaration.getChildNodes().stream()
                .filter(childNode -> childNode instanceof BodyDeclaration<?>)
                .collect(Collectors.toList());
        graph.outgoingEdgesOf(vertex).stream()
                .filter(ClassArc.MemberArc.class::isInstance)
                .map(arc -> arc.getTarget())
                .filter(toReserveVertex -> {
                    // 去除工具运行中自动生成的声明
                    for (AnnotationExpr annotation : toReserveVertex.getDeclaration().getAnnotations()) {
                        if (annotation.getNameAsString().equals("lombok.Generated")
                                || annotation.getNameAsString().equals("ltm.Generated")) {
                            return false;
                        }
                    }
                    // 保留无关代码
                    if (clusterIds.containsAll(toReserveVertex.getClusterIds())) {
                        return false;
                    }
                    return true;
                })
                .map(toReserveVertex -> toReserveVertex.getDeclaration())
                .forEach(toReserve -> {
                    toRemove.remove(toReserve);
                });
        toRemove.forEach(node -> {
            if (node instanceof EnumConstantDeclaration || node instanceof AnnotationMemberDeclaration) {
                // pass
            } else {
                pureDeclaration.remove(node);
            }
        });

        // 还原 lombok 注解
        postprocess(pureDeclaration);
        return pureDeclaration;
    }
}
