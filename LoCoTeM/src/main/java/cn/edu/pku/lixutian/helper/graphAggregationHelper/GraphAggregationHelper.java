package cn.edu.pku.lixutian.helper.graphAggregationHelper;

import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class GraphAggregationHelper {
    protected SKG graph;
    protected Vertex<TypeDeclaration<?>> topVertex;
    protected Set<Integer> clusterIds;

    public GraphAggregationHelper(SKG graph, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        this.graph = graph;
        this.topVertex = topVertex;
        this.clusterIds = clusterIds;
    }

    Map<String, TypeDeclaration<?>> classPureDeclarationMap = new HashMap<>();
    Map<String, TypeDeclaration<?>> classFullDeclarationMap = new HashMap<>();

    abstract public String generateCode();
//    {
//        if (debloatingFlag && clusterIds.containsAll(topVertex.getClusterIds())) {
//            return "";
//        }
//        graph.vertexSet().stream()
//                .filter(vertex -> isClassVertex(vertex))
//                .forEach(vertex -> {
//                    classPureDeclarationMap.put(vertex.getId(), classVertex2Declaration(vertex, debloatingFlag));
//                    classFullDeclarationMap.put(vertex.getId(), (TypeDeclaration<?>) vertex.getDeclaration());
//                });
//        BodyDeclaration<?> declaration = topClassVertex2Declaration(topVertex);
//        return declaration.toString();
//    }

//        protected boolean isTopClassVertex(Vertex<?> vertex) {
//            return isClassVertex(vertex) && isTopVertex(vertex);
//        }

    protected static boolean isClassVertex(Vertex<?> vertex) {
        BodyDeclaration<?> declaration = vertex.getDeclaration();
        boolean isClass = declaration.isTypeDeclaration();
        return isClass;
    }

//        protected boolean isTopVertex(Vertex<?> vertex) {
//            return graph.incomingEdgesOf(vertex).stream()
//                    .filter(ClassArc.InnerArc.class::isInstance)
//                    .count() == 0;
//        }

    abstract protected TypeDeclaration<?> classVertex2Declaration(Vertex<?> vertex);
//    {
//        TypeDeclaration<?> pureDeclaration = (TypeDeclaration<?>) vertex.getDeclaration().clone();
//        pureDeclaration.setParentNode(null);
//
//        List<Node> toRemove = pureDeclaration.getChildNodes().stream()
//                .filter(childNode -> childNode instanceof BodyDeclaration<?>)
//                .collect(Collectors.toList());
//        graph.outgoingEdgesOf(vertex).stream()
//                .filter(ClassArc.MemberArc.class::isInstance)
//                .map(arc -> arc.getTarget())
//                .filter(toReserveVertex -> {
//                    // 去除工具运行中自动生成的声明
//                    for (AnnotationExpr annotation : toReserveVertex.getDeclaration().getAnnotations()) {
//                        if (annotation.getNameAsString().equals("lombok.Generated")
//                                || annotation.getNameAsString().equals("ltm.Generated")) {
//                            return false;
//                        }
//                    }
//                    // 保留无关代码
//                    if (debloatingFlag && clusterIds.containsAll(toReserveVertex.getClusterIds())) {
//                        return false;
//                    }
//                    return true;
//                })
//                .map(toReserveVertex -> toReserveVertex.getDeclaration())
//                .forEach(toReserve -> {
//                    toRemove.remove(toReserve);
//                });
//        toRemove.forEach(node -> {
//            if (node instanceof EnumConstantDeclaration || node instanceof AnnotationMemberDeclaration) {
//                // pass
//            } else {
//                pureDeclaration.remove(node);
//            }
//        });
//
//        // 还原 lombok 注解
//        postprocess(pureDeclaration);
//        return pureDeclaration;
//    }

    protected void postprocess(TypeDeclaration<?> declaration) {
        declaration.accept(new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(MarkerAnnotationExpr n, Void arg) {
                processAnnotation(n);
                return super.visit(n, arg);
            }

            @Override
            public Visitable visit(SingleMemberAnnotationExpr n, Void arg) {
                processAnnotation(n);
                return super.visit(n, arg);
            }

            @Override
            public Visitable visit(NormalAnnotationExpr n, Void arg) {
                processAnnotation(n);
                return super.visit(n, arg);
            }

            private void processAnnotation(AnnotationExpr n) {
                String annotationName = n.getNameAsString();
                if (annotationName.startsWith("ltm.lombok.")) {
                    // 提取原始 lombok 注解名
                    String simpleName = annotationName.substring("ltm.lombok.".length());

                    // 构建 lombok 注解（与原注解类型保持一致）
                    AnnotationExpr newAnnotation;
                    if (n instanceof MarkerAnnotationExpr) {
                        newAnnotation = new MarkerAnnotationExpr(simpleName);
                    } else if (n instanceof SingleMemberAnnotationExpr) {
                        Expression value = ((SingleMemberAnnotationExpr) n).getMemberValue();
                        newAnnotation = new SingleMemberAnnotationExpr(new Name(simpleName), value);
                    } else if (n instanceof NormalAnnotationExpr) {
                        NodeList<MemberValuePair> pairs = ((NormalAnnotationExpr) n).getPairs();
                        newAnnotation = new NormalAnnotationExpr(new Name(simpleName), pairs);
                    } else {
                        return;
                    }

                    // 找到注解的父节点，执行替换
                    n.getParentNode().ifPresent(parent -> {
                        if (parent instanceof NodeWithAnnotations) {
                            NodeWithAnnotations<?> annotatedParent = (NodeWithAnnotations<?>) parent;

                            // 替换当前注解
                            NodeList<AnnotationExpr> annotations = annotatedParent.getAnnotations();
                            int index = annotations.indexOf(n);
                            if (index >= 0) {
                                annotations.set(index, newAnnotation);
                            }
                        }
                    });
                }
            }

        }, null);
    }

    protected BodyDeclaration<?> topClassVertex2Declaration(Vertex<?> vertex) {
        dfsInnerClass(vertex);
        return classPureDeclarationMap.get(vertex.getId());
    }

    protected void dfsInnerClass(Vertex<?> outerVertex) {
        List<Vertex<?>> innerVertexList = graph.outgoingEdgesOf(outerVertex).stream()
                .filter(ClassArc.InnerArc.class::isInstance)
                .map(arc -> arc.getTarget())
                .filter(toReserveVertex -> {
                    BodyDeclaration<?> toReserve = toReserveVertex.getDeclaration();
                    // 去除工具运行中自动生成的声明
                    for (AnnotationExpr annotation : toReserve.getAnnotations()) {
                        if (annotation.getNameAsString().equals("lombok.Generated")
                                || annotation.getNameAsString().equals("ltm.Generated")) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        for (Vertex<?> innerVertex : innerVertexList) {
            dfsInnerClass(innerVertex);
            addMember(outerVertex.getId(), innerVertex.getId());
        }
    }

    protected void addMember(String outerId, String innerId) {
        TypeDeclaration<?> outerPureDeclaration = classPureDeclarationMap.get(outerId);
        TypeDeclaration<?> innerPureDeclaration = classPureDeclarationMap.get(innerId);
        TypeDeclaration<?> outerFullDeclaration = classFullDeclarationMap.get(outerId);

        List<BodyDeclaration<?>> outerFullMembers = outerFullDeclaration.getMembers();
        List<BodyDeclaration<?>> outerPureMembers = outerPureDeclaration.getMembers();
        int targetIndex = outerFullMembers.indexOf(classFullDeclarationMap.get(innerId));

        int insertPos = 0;
        for (int i = 0; i < targetIndex; i++) {
            BodyDeclaration<?> fullBefore = outerFullMembers.get(i);
            if (isElementIn(outerPureMembers, fullBefore)) {
                insertPos++;
            }
        }
        outerPureMembers.add(insertPos, innerPureDeclaration);
    }

    protected boolean isElementIn(List<BodyDeclaration<?>> members, BodyDeclaration<?> member) {
        if (members.contains(member)) {
            return true;
        }
        if (member.isTypeDeclaration()) {
            List<TypeDeclaration<?>> candidateMembers = members.stream()
                    .filter(m -> m.isTypeDeclaration())
                    .map(m -> (TypeDeclaration<?>) m)
                    .collect(Collectors.toList());
            for (TypeDeclaration<?> candidate : candidateMembers) {
                if (candidate.getNameAsString().equals(((TypeDeclaration<?>) member).getNameAsString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
