package cn.edu.pku.lixutian.graph;

import cn.edu.pku.lixutian.graph.softwareGraph.SoftwareGraph;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.CallArc;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ClassArc;
import cn.edu.pku.lixutian.graph.softwareGraph.arc.ReferenceArc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.pku.lixutian.graph.softwareGraph.vertex.MapKeyUtils.mapKey;

public class CallGraph extends SoftwareGraph<CallArc> {
    public static CallGraph instance = null;


    public static CallGraph getNewInstance() {
        instance = new CallGraph();
        return instance;
    }

    public static CallGraph getInstance() {
        assert instance != null;
        return instance;
    }

    private final VertexMap vertexMap;
    private final ClassGraph classGraph;
    private final ReferenceGraph referenceGraph;

    protected CallGraph() {
        this.vertexMap = VertexMap.getInstance();
        this.classGraph = ClassGraph.getInstance();
        this.referenceGraph = ReferenceGraph.getInstance();
    }

    /**
     * Find the method and constructor declarations (vertices) in the given list of compilation units.
     */
    @Override
    protected void buildVertices() {
        List<String> mapFilter = new ArrayList<>();
        mapFilter.add("methodDeclarationMap");
        mapFilter.add("initializerDeclarationMap");
        mapFilter.add("fieldDeclarationMap");
        mapFilter.add("annotationMemberDeclarationMap");

        vertexMap.getAllVertexMap().forEach((t1, map) -> {
            if (mapFilter.contains(t1)) {
                map.forEach((t2, vertex) -> {
                    addVertex(vertex);
                });
            }
        });
    }

    /**
     * Find the calls to methods and constructors (edges) in the given list of compilation units.
     */
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

            /**
             * ============ Method declarations ===========
             * There are some locations not considered, which may lead to an error in the stack.
             * 1. Method calls in non-static field initializations are assigned to all constructors of that class
             * 2. Method calls in static field initializations are assigned to the static block of that class
             */
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if (typeStack.peek() != null) {
                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } else {
                    // TODO 忽略record
                }
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            // TODO 这段代码可能没什么用 InitializerCallArc可能也没什么用
            @Override
            public void visit(InitializerDeclaration n, Void arg) {
                if (declStack.isEmpty()) {  // 正常情况
                    if (!n.isStatic()) {
                        for (ConstructorDeclaration cd : typeStack.peek().getConstructors()) {
                            addEdge(findVertexByDeclaration(cd), findVertexByDeclaration(n), new CallArc.InitializerCallArc());
                        }
                    }
                    if (n.isStatic()) {

                    }
                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } else {    // 方法中嵌套类方法
                    super.visit(n, arg);
                }

            }

            /**
             * =============== Method calls ===============
             */
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                try {
                    n.resolve().toAst().ifPresent(decl -> {
                        if (decl instanceof MethodDeclaration) {
                            createPolyEdges((MethodDeclaration) decl, n);
                        } else if (decl instanceof EnumDeclaration) {
                            // Enum.values() or Enum.valueOf()
                            // 已在 ReferenceGraph 中处理
                        } else {
                            System.out.println("尚未分析的问题");
                        }
                    });
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                } catch (UnsupportedOperationException e) {
                    if (e.getMessage() != null && e.getMessage().equals("AnnotationMember, need resolve manually")) {
                        assert n.getScope().isPresent();
                        ResolvedType type = n.getScope().get().calculateResolvedType();
                        if (type.isReferenceType()) {
                            assert type.isReferenceType();
                        } else if (type.isConstraint()) {
                            assert type.asConstraintType().getBound().isReferenceType();
                            type = type.asConstraintType().getBound();
                        }
                        TypeDeclaration<?> typeDeclaration = vertexMap.getClassDeclaration(mapKey(type)).getDeclaration();
                        assert typeDeclaration.isAnnotationDeclaration();
                        assert n.getArguments().isEmpty();
                        List<AnnotationMemberDeclaration> memberList = typeDeclaration.asAnnotationDeclaration().getMembers().stream()
                                .filter(AnnotationMemberDeclaration.class::isInstance)
                                .map(AnnotationMemberDeclaration.class::cast)
                                .filter(member -> member.getNameAsString().equals(n.getNameAsString()))
                                .collect(Collectors.toList());
                        assert memberList.size() == 1;
                        referenceGraph.addEdge(findVertexByDeclaration(declStack.peek()), findVertexByDeclaration(memberList.get(0)), new ReferenceArc.MethodReference.UseReference.AnnotationMemberUse());
                    } else {
                        System.out.println(e.getMessage() + "新问题UnsupportedOperation！！！！！！！！");
                        e.printStackTrace();
                    }

                } catch (Exception e) {
                    System.out.println(e.getMessage() + "新问题！！！！！！！！");
                    e.printStackTrace();
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                try {
                    n.resolve().toAst().ifPresent(decl -> {
                        assert decl instanceof CallableDeclaration<?>;
                        createNormalEdge((CallableDeclaration<?>) decl, n);
                    });
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                } catch (IllegalStateException e) {
                    System.out.println("忽略Record");
                    unsolvedSymbols.add(e.getMessage());
                } catch (Exception e) {
                    // TODO JavaParser
                    System.out.println("复合异常类型");
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
                try {
                    n.resolve().toAst().ifPresent(decl -> {
                        assert decl instanceof CallableDeclaration<?>;
                        createNormalEdge((CallableDeclaration<?>) decl, n);
                    });
                } catch (UnsolvedSymbolException e) {
                    unsolvedSymbols.add(e.getName());
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldDeclaration n, Void arg) {
                if (declStack.isEmpty()) {
                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } else {
                    // TODO
                }

            }

            @Override
            public void visit(AnnotationMemberDeclaration n, Void arg) {
                if (declStack.isEmpty()) {
                    declStack.push(n);
                    super.visit(n, arg);
                    declStack.pop();
                } else {
                    // TODO
                }

            }

            protected void createNormalEdge(BodyDeclaration<?> decl, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
                assert typeStack.peek() != null;
                if (declStack.peek() != null) {
                    addEdge(findVertexByDeclaration(declStack.peek()), findVertexByDeclaration(decl), new CallArc.NormalCallArc(call));
                } else {
                    throw new IllegalStateException("类中直接CallExpr应该不合语法");
                }
            }

            protected void createPolyEdges(MethodDeclaration decl, MethodCallExpr call) {
                /*
                  Static Call
                 */
                if (decl.isStatic()) {
                    createNormalEdge(decl, call);
                    return;
                }

                Optional<Expression> scope = call.getScope();
                /*
                 * Determine the type of the call's scope*
                 */
                Set<? extends TypeDeclaration<?>> dynamicTypes;
                if (scope.isEmpty()) {
                    classGraph.overriddenSetOf(decl)
                            .forEach(methodDeclaration -> createNormalEdge(methodDeclaration, call));
                    return;
                } else {
                    ResolvedReferenceType type;
                    if (scope.get().calculateResolvedType().isReferenceType()) {
                        type = scope.get().calculateResolvedType().asReferenceType();
                    } else if (scope.get().calculateResolvedType().isTypeVariable()) {
                        if (scope.get().calculateResolvedType().asTypeVariable().asTypeParameter().getBounds().isEmpty()) {
                            type = null;
                        } else {
                            type = scope.get().calculateResolvedType().asTypeVariable().asTypeParameter().getBounds().get(0).getType().asReferenceType();
                        }
                    } else {
                        type = null;
                    }
                    dynamicTypes = classGraph.subclassesOf(type);
                }
                dynamicTypes.stream()
                        .map(t -> classGraph.findMethodByTypeAndSignature(t, decl))
                        .collect(Collectors.toSet())
                        .forEach(methodDeclaration -> createNormalEdge(methodDeclaration, call));
            }

            protected Vertex findVertexByDeclaration(BodyDeclaration<?> declaration) {
                assert declaration.getParentNode().isPresent();
                assert declaration.getParentNode().get() instanceof TypeDeclaration<?>;
                if (declaration.isCallableDeclaration()) {
                    return vertexMap.getMethodDeclaration(mapKey(declaration.asCallableDeclaration(), (TypeDeclaration<?>) declaration.getParentNode().get()));
                } else if (declaration.isAnnotationMemberDeclaration()) {
                    return vertexMap.getAnnotationMemberDeclaration(mapKey(declaration.asAnnotationMemberDeclaration(), (TypeDeclaration<?>) declaration.getParentNode().get()));
                } else if (declaration.isInitializerDeclaration()) {
                    return vertexMap.getInitializerDeclaration(mapKey(declaration.asInitializerDeclaration(), (TypeDeclaration<?>) declaration.getParentNode().get()));
                } else if (declaration.isFieldDeclaration()) {
                    return vertexMap.getFieldDeclaration(mapKey(declaration.asFieldDeclaration(), (TypeDeclaration<?>) declaration.getParentNode().get()));
                } else {
                    throw new IllegalStateException("预期之外的类型");
                }
            }


        }, null);
    }
}
