package cn.edu.pku.lixutian.graph;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.softwareGraph.SoftwareGraph;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ReferenceArc;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.utils.Pair;

import java.util.*;

import static cn.edu.pku.lixutian.graph.softwareGraph.vertex.MapKeyUtils.mapKey;

public class ReferenceGraph extends SoftwareGraph<ReferenceArc> {
    private static final Map<Integer, ReferenceGraph> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    public static ReferenceGraph getNewInstance() {
        ReferenceGraph instance = new ReferenceGraph();
        INSTANCES.put(ProjectState.currentRepositoryKey(), instance);
        return instance;
    }

    public static ReferenceGraph getInstance() {
        ReferenceGraph instance = INSTANCES.get(ProjectState.currentRepositoryKey());
        assert instance != null;
        return instance;
    }

    public static void clearRepository(Integer repositoryId) {
        if (repositoryId != null) {
            INSTANCES.remove(repositoryId);
        }
    }

    private final VertexMap vertexMap;
    private final ClassGraph classGraph;

    protected ReferenceGraph() {
        this.vertexMap = VertexMap.getInstance();
        this.classGraph = ClassGraph.getInstance();
    }

    @Override
    protected void buildVertices() {
        vertexMap.getAllVertexMap().forEach((t1, map) -> {
            map.forEach((t2, vertex) -> {
                addVertex(vertex);
            });
        });
    }

    @Override
    protected void buildEdges(NodeList<CompilationUnit> arg, Set<String> unsolvedSymbols) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            private final Deque<TypeDeclaration<?>> typeStack = new LinkedList<>();
            private final Deque<BodyDeclaration<?>> declStack = new LinkedList<>();

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(AnnotationDeclaration n, Void arg) {
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(InitializerDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            // Add Annotation Use ARC
            @Override
            public void visit(MarkerAnnotationExpr n, Void arg) {
                processAnnotation(n);
                super.visit(n, arg);
            }


            @Override
            public void visit(SingleMemberAnnotationExpr n, Void arg) {
                processAnnotation(n);
                super.visit(n, arg);
            }

            @Override
            public void visit(NormalAnnotationExpr n, Void arg) {
                processAnnotation(n);
                super.visit(n, arg);
            }

            private void processAnnotation(AnnotationExpr n) {
                Vertex<? extends BodyDeclaration<?>> sourceVertex = getSourceVertex();
                try {
                    ResolvedAnnotationDeclaration declaration = n.resolve();
                    declaration.toAst().ifPresent(declAst -> {
                        assert declAst instanceof AnnotationDeclaration;
                        Vertex<TypeDeclaration<?>> targetVertex = vertexMap.getClassDeclaration(mapKey((TypeDeclaration<?>) declAst));
                        addEdge(sourceVertex, targetVertex, new ReferenceArc.AnnotationReference.AnnotationUse());
                    });
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                }
            }

            // Add Filed Type ARC
            @Override
            public void visit(FieldDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> clazz = typeStack.peek();

                try {
                    Vertex<FieldDeclaration> field = vertexMap.getFieldDeclaration(mapKey(n, clazz));
                    ResolvedReferenceType type = getResolved(n.getVariable(0).getType());
                    dfsAddReferenceType(field, type, ReferenceArc.FieldReference.FieldType.class, 0);

                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } catch (NoSuchElementException e) {
                    System.out.println("忽略");
                }

            }

            // Add AnnotationMember Type ARC
            @Override
            public void visit(AnnotationMemberDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> clazz = typeStack.peek();
                Vertex<AnnotationMemberDeclaration> member = vertexMap.getAnnotationMemberDeclaration(mapKey(n, clazz));
                ResolvedReferenceType type = getResolved(n.getType());
                dfsAddReferenceType(member, type, ReferenceArc.FieldReference.AnnotationMemberType.class, 0);

                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            // Add Method Return Type ARC and Parameters Type ARC
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if (typeStack.peek() != null) {
                    TypeDeclaration<?> clazz = typeStack.peek();
                    try{
                        Vertex<CallableDeclaration<?>> method = vertexMap.getMethodDeclaration(mapKey(n, clazz));

                        // ReturnType
                        ResolvedReferenceType returnType = getResolved(n.getType());
                        dfsAddReferenceType(method, returnType, ReferenceArc.MethodReference.ReturnType.class, 0);
                        // ParamType
                        n.getParameters().forEach(parameter -> {
                            ResolvedReferenceType parameterType = getResolved(parameter.getType());
                            dfsAddReferenceType(method, parameterType, ReferenceArc.MethodReference.ParameterType.class, 0);
                        });

                        // Visit Recursively
                        declStack.push(n);
                        super.visit(n, arg);
                        declStack.pop();
                    }catch (NoSuchElementException e) {
                        System.out.println("忽略");
                    }

                } else {
                    // TODO 忽略record
                }
            }

            // Add Constructor Method Parameters Type ARC
            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> clazz = typeStack.peek();

                try {
                    Vertex<CallableDeclaration<?>> method = vertexMap.getMethodDeclaration(mapKey(n, clazz));

                    // ParamType
                    n.getParameters().forEach(parameter -> {
                        ResolvedReferenceType parameterType = getResolved(parameter.getType());
                        dfsAddReferenceType(method, parameterType, ReferenceArc.MethodReference.ParameterType.class, 0);
                    });

                    // Visit Recursively
                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } catch (NoSuchElementException e) {
                    System.out.println("忽略");
                }
            }

            // Add Method VariableType Arc and FieldUseArc
            @Override
            public void visit(NameExpr n, Void arg) {
                Vertex<? extends BodyDeclaration<?>> sourceVertex = getSourceVertex();

                try {
                    ResolvedValueDeclaration declaration = n.resolve();
                    if (declaration.isParameter()) {
                        // 已经在上面处理过，这里不做处理
                    } else if (declaration.isField()) {
                        declaration.toAst().ifPresent(declAst -> {
                            assert declAst instanceof FieldDeclaration;
                            assert declAst.getParentNode().isPresent();
                            assert declAst.getParentNode().get() instanceof TypeDeclaration<?>;
                            TypeDeclaration<?> c = (TypeDeclaration<?>) declAst.getParentNode().get();
                            Vertex<FieldDeclaration> targetVertex = vertexMap.getFieldDeclaration(mapKey((FieldDeclaration) declAst, c));
                            addEdge(sourceVertex, targetVertex, getFieldUseOrDefArc(n));
                        });
                    } else if (declaration.isVariable()) {
                        ResolvedReferenceType type = getResolvedReference(declaration.getType());
                        dfsAddReferenceType(sourceVertex, type, ReferenceArc.MethodReference.VariableType.class, 0);
                    } else if (declaration.isEnumConstant()) {
                        declaration.toAst().ifPresent(declAst -> {
                            assert declAst.getParentNode().isPresent();
                            assert declAst.getParentNode().get() instanceof TypeDeclaration<?>;
                            TypeDeclaration<?> c = (TypeDeclaration<?>) declAst.getParentNode().get();
                            Vertex<TypeDeclaration<?>> targetVertex = vertexMap.getClassDeclaration(mapKey(c));
                            addEdge(sourceVertex, targetVertex, new ReferenceArc.MethodReference.UseReference.EnumUse());
                        });
                    } else {
                        System.out.println("未考虑到的NameExpr类型");
                    }

                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                }
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                Vertex<? extends BodyDeclaration<?>> sourceVertex = getSourceVertex();
                try {
                    n.resolve().toAst().ifPresent(declAst -> {
                        if (declAst instanceof FieldDeclaration) {
                            assert declAst.getParentNode().isPresent();
                            if( declAst.getParentNode().get() instanceof TypeDeclaration<?>){
                                TypeDeclaration<?> c = (TypeDeclaration<?>) declAst.getParentNode().get();
                                Vertex<FieldDeclaration> targetVertex = vertexMap.getFieldDeclaration(mapKey((FieldDeclaration) declAst, c));
                                addEdge(sourceVertex, targetVertex, getFieldUseOrDefArc(n));
                            }else{
                                System.out.println("忽略");
                            }

                        } else if (declAst instanceof EnumConstantDeclaration) {
                            assert declAst.getParentNode().isPresent();
                            assert declAst.getParentNode().get() instanceof TypeDeclaration<?>;
                            TypeDeclaration<?> c = (TypeDeclaration<?>) declAst.getParentNode().get();
                            Vertex<TypeDeclaration<?>> targetVertex = vertexMap.getClassDeclaration(mapKey(c));
                            addEdge(sourceVertex, targetVertex, new ReferenceArc.MethodReference.UseReference.EnumUse());
                        } else {
                            throw new IllegalStateException("未能处理的FieldAccessExpr");
                        }

                    });
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                }
                super.visit(n, arg);
            }

            private Vertex<? extends BodyDeclaration<?>> getSourceVertex() {
                assert typeStack.peek() != null;
                try{
                    if (declStack.peek() != null) {
                        TypeDeclaration<?> clazz = typeStack.peek();
                        BodyDeclaration<?> decl = declStack.peek();
                        if (decl.isCallableDeclaration()) {
                            return vertexMap.getMethodDeclaration(mapKey(decl.asCallableDeclaration(), clazz));
                        } else if (decl.isInitializerDeclaration()) {
                            return vertexMap.getInitializerDeclaration(mapKey(decl.asInitializerDeclaration(), clazz));
                        } else if (decl.isFieldDeclaration()) {
                            return vertexMap.getFieldDeclaration(mapKey(decl.asFieldDeclaration(), clazz));
                        } else if (decl.isAnnotationMemberDeclaration()) {
                            return vertexMap.getAnnotationMemberDeclaration(mapKey(decl.asAnnotationMemberDeclaration(), clazz));
                        } else {
                            throw new IllegalStateException("declStack中出现预期之外的类型");
                        }
                    } else {
                        TypeDeclaration<?> clazz = typeStack.peek();
                        return vertexMap.getClassDeclaration(mapKey(clazz));
                    }
                }catch (NoSuchElementException e) {
                    return null;
                }

            }

            private ReferenceArc.MethodReference.UseReference getFieldUseOrDefArc(Expression expr) {
                if (isDef(expr)) {
                    return new ReferenceArc.MethodReference.UseReference.FieldDef();
                } else {
                    return new ReferenceArc.MethodReference.UseReference.FieldUse();
                }
            }

            private boolean isDef(Expression expr) {
                if (expr.getParentNode().isPresent() && expr.getParentNode().get() instanceof AssignExpr) {
                    return ((AssignExpr) expr.getParentNode().get()).getTarget().equals(expr);
                }
                return false;
            }

            private ResolvedReferenceType getResolved(Type t) {
                try {
                    ResolvedType type = t.resolve();
                    return getResolvedReference(type);
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                    return null;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }

            private ResolvedReferenceType getResolvedReference(ResolvedType type) {
                if (type.isArray()) {
                    ResolvedType ct = type.asArrayType().getComponentType();
                    if (ct.isReferenceType()) {
                        return ct.asReferenceType();
                    } else if (ct.isPrimitive()) {
                        return null;
                    } else if (ct.isTypeVariable()) {
                        if (ct.asTypeVariable().asTypeParameter().getBounds().isEmpty()) {
                            return null;
                        } else {
                            return ct.asTypeVariable().asTypeParameter().getBounds().get(0).getType().asReferenceType();
                        }
                    } else {
                        return getResolvedReference(ct);
                    }
                } else if (type.isReferenceType()) {
                    return type.asReferenceType();
                } else if (type.isPrimitive()) {
                    return null;
                } else if (type.isTypeVariable()) {
                    if (type.asTypeVariable().asTypeParameter().getBounds().isEmpty()) {
                        return null;
                    } else {
                        return type.asTypeVariable().asTypeParameter().getBounds().get(0).getType().asReferenceType();
                    }
                } else if (type.isVoid()) {
                    return null;
                } else {
                    throw new IllegalStateException("类型未知");
                }
            }

            private void dfsAddReferenceType(Vertex<? extends BodyDeclaration<?>> source, ResolvedReferenceType type, Class<? extends ReferenceArc> arcClass, int depth) {
                if (type == null) {
                    return;
                }
                addReferenceType(source, type, arcClass);
                if (depth > 10) {
                    return;
                }
                for (Pair<ResolvedTypeParameterDeclaration, ResolvedType> pair : type.getTypeParametersMap()) {
                    if (pair.b.isReferenceType()) {
                        dfsAddReferenceType(source, pair.b.asReferenceType(), arcClass, depth + 1);
                    } else if (pair.b.isWildcard()) {
                        if (pair.b.asWildcard().isExtends()) {
                            ResolvedType bound = pair.b.asWildcard().getBoundedType();
                            if (bound.isReferenceType()) {
                                dfsAddReferenceType(source, bound.asReferenceType(), arcClass, depth + 1);
                            } else if (bound.isTypeVariable()) {
                                List<ResolvedTypeParameterDeclaration.Bound> bounds = bound.asTypeVariable().asTypeParameter().getBounds();
                                for (ResolvedTypeParameterDeclaration.Bound b : bounds) {
                                    ResolvedType boundType = b.getType();
                                    assert boundType.isReferenceType();
                                    dfsAddReferenceType(source, boundType.asReferenceType(), arcClass, depth + 1);
                                }
                            }
                        }
                    } else if (pair.b.isTypeVariable()) {
                        List<ResolvedTypeParameterDeclaration.Bound> bounds = pair.b.asTypeVariable().asTypeParameter().getBounds();
                        for (ResolvedTypeParameterDeclaration.Bound bound : bounds) {
                            ResolvedType boundType = bound.getType();
                            assert boundType.isReferenceType();
                            dfsAddReferenceType(source, boundType.asReferenceType(), arcClass, depth + 1);
                        }
                    } else if (pair.b.isArray()) {
                        ResolvedType ct = pair.b.asArrayType().getComponentType();
                        assert ct.isReferenceType();
                        dfsAddReferenceType(source, ct.asReferenceType(), arcClass, depth + 1);
                    } else {
                        throw new IllegalStateException("未考虑");
                    }

                }
            }

            public void addReferenceType(Vertex<? extends BodyDeclaration<?>> source, ResolvedReferenceType type, Class<? extends ReferenceArc> arcClass) {
                if (type == null) {
                    return;
                }
                Set<? extends TypeDeclaration<?>> dynamicTypes = classGraph.subclassesOf(type);
                dynamicTypes.forEach(t -> {
                    Vertex<TypeDeclaration<?>> typeVertex = vertexMap.getClassDeclaration(mapKey(t));
                    if (typeVertex != null && containsVertex(typeVertex)) {
                        try {
                            addEdge(source, typeVertex, arcClass.getDeclaredConstructor().newInstance());
                        } catch (Exception e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }
                });
            }


        }, null);
    }
}
