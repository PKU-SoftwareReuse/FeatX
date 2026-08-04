package com.mycode.graph;

import com.mycode.config.ProjectState;
import com.mycode.graph.softwareGraph.SoftwareGraph;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.graph.softwareGraph.arc.ClassArc;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedClassDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mycode.graph.softwareGraph.vertex.MapKeyUtils.mapKey;

public class ClassGraph extends SoftwareGraph<ClassArc> {
    private static final Map<Integer, ClassGraph> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    public static ClassGraph getNewInstance() {
        ClassGraph instance = new ClassGraph();
        INSTANCES.put(ProjectState.currentRepositoryKey(), instance);
        return instance;
    }

    public static ClassGraph getInstance() {
        ClassGraph instance = INSTANCES.get(ProjectState.currentRepositoryKey());
        assert instance != null;
        return instance;
    }

    public static void clearRepository(Integer repositoryId) {
        if (repositoryId != null) {
            INSTANCES.remove(repositoryId);
        }
    }

    private final VertexMap vertexMap;

    protected ClassGraph() {
        this.vertexMap = VertexMap.getInstance();
    }

    /**
     * Find the class declarations, the field declaration, and method and constructor declarations (vertices)
     * in the given list of compilation units.
     */
    @Override
    protected void buildVertices() {
        vertexMap.getAllVertexMap().forEach((t1, map) -> {
            map.forEach((t2, vertex) -> {
                addVertex(vertex);
            });
        });
    }

    /**
     * Find the class declarations, field declarations, and method declarations and build the corresponding
     * member/extends/implements relationships in the given list of compilation units.
     */
    @Override
    protected void buildEdges(NodeList<CompilationUnit> arg, Set<String> unsolvedSymbols) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            private final Deque<TypeDeclaration<?>> typeStack = new LinkedList<>();

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                if (typeStack.peek() != null) {
                    TypeDeclaration<?> type = typeStack.peek();
                    Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                    Vertex<TypeDeclaration<?>> v = vertexMap.getClassDeclaration(mapKey(n));
                    addEdge(c, v, new ClassArc.InnerArc.InnerClass());
                }
                typeStack.push(n);
                Vertex<TypeDeclaration<?>> v = vertexMap.getClassDeclaration(mapKey(n));
                addClassEdge(v, unsolvedSymbols);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                if (typeStack.peek() != null) {
                    TypeDeclaration<?> type = typeStack.peek();
                    Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                    Vertex<TypeDeclaration<?>> v = vertexMap.getClassDeclaration(mapKey(n));
                    addEdge(c, v, new ClassArc.InnerArc.InnerEnum());
                }
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(AnnotationDeclaration n, Void arg) {
                if (typeStack.peek() != null) {
                    TypeDeclaration<?> type = typeStack.peek();
                    Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                    Vertex<TypeDeclaration<?>> v = vertexMap.getClassDeclaration(mapKey(n));
                    addEdge(c, v, new ClassArc.InnerArc.InnerAnnotation());
                }
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(InitializerDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> type = typeStack.peek();
                Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                Vertex<InitializerDeclaration> v = vertexMap.getInitializerDeclaration(mapKey(n, type));
                if (n.isStatic()) {
                    addEdge(c, v, new ClassArc.MemberArc.InitializerMember.StaticInitializer());
                } else {
                    addEdge(c, v, new ClassArc.MemberArc.InitializerMember.NormalInitializer());
                }

            }

            @Override
            public void visit(FieldDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> type = typeStack.peek();
                Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                Vertex<FieldDeclaration> v = vertexMap.getFieldDeclaration(mapKey(n, type));
                addEdge(c, v, new ClassArc.MemberArc.FieldMember());
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if( typeStack.peek() != null){
                    TypeDeclaration<?> type = typeStack.peek();
                    Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                    Vertex<CallableDeclaration<?>> v = vertexMap.getMethodDeclaration(mapKey(n, type));
                    addEdge(c, v, new ClassArc.MemberArc.MethodMember());
                }else{
                    // TODO 忽略record
                }

            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> type = typeStack.peek();
                Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                Vertex<CallableDeclaration<?>> v = vertexMap.getMethodDeclaration(mapKey(n, type));
                addEdge(c, v, new ClassArc.MemberArc.ConstructorMember());
            }

            @Override
            public void visit(AnnotationMemberDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                TypeDeclaration<?> type = typeStack.peek();
                Vertex<TypeDeclaration<?>> c = vertexMap.getClassDeclaration(mapKey(type));
                Vertex<AnnotationMemberDeclaration> v = vertexMap.getAnnotationMemberDeclaration(mapKey(n, type));
                addEdge(c, v, new ClassArc.MemberArc.AnnotationMember());
            }
        }, null);
    }

    protected void addClassEdge(Vertex<TypeDeclaration<?>> v, Set<String> unsolvedSymbols) {
        if (v.getDeclaration() instanceof EnumDeclaration)
            return; // nothing to do, it is final and cannot extend nor implement user-defined types
        ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) v.getDeclaration();
        c.getExtendedTypes().forEach(p -> {
            try {
                Vertex<TypeDeclaration<?>> source = vertexMap.getClassDeclaration(mapKey(p.resolve()));
                if (source != null && containsVertex(source)) {
                    addEdge(source, v, new ClassArc.ParentArc.Extends());
                }
            } catch (UnsolvedSymbolException e) {
                unsolvedSymbols.add(e.getName());
            }

        });
        c.getImplementedTypes().forEach(p -> {
            try {
                Vertex<TypeDeclaration<?>> source = vertexMap.getClassDeclaration(mapKey(p.resolve()));
                if (source != null && containsVertex(source)) {
                    addEdge(source, v, new ClassArc.ParentArc.Implements());
                }
            } catch (UnsolvedSymbolException e) {
                unsolvedSymbols.add(e.getName());
            }

        });
    }

    public Set<MethodDeclaration> overriddenSetOf(MethodDeclaration method) {


        return subclassesStreamOf(findClassVertex(method.findAncestor(TypeDeclaration.class).orElseThrow()))
                .flatMap(vertex -> outgoingEdgesOf(vertex).stream()
                        .filter(ClassArc.MemberArc.MethodMember.class::isInstance)
                        .map(this::getEdgeTarget)
                        .filter(v -> v.getDeclaration().isMethodDeclaration())
                        .filter(v -> v.getDeclaration().asMethodDeclaration().getSignature().equals(method.getSignature()))
                        .map(v -> v.getDeclaration().asMethodDeclaration())
                ).collect(Collectors.toSet());
    }

    protected Stream<Vertex<? extends TypeDeclaration<?>>> subclassesStreamOf(Vertex<? extends TypeDeclaration<?>> classVertex) {
        return Stream.concat(Stream.of(classVertex),
                outgoingEdgesOf(classVertex).stream()
                        .filter(ClassArc.ParentArc.class::isInstance)
                        .map(this::getEdgeTarget)
                        .map(vertex -> (Vertex<? extends TypeDeclaration<?>>) vertex)
                        .flatMap(this::subclassesStreamOf)
        );
    }

    public Set<? extends TypeDeclaration<?>> subclassesOf(TypeDeclaration<?> clazz) {
        return subclassesOf(findClassVertex(clazz));
    }

    public Set<? extends TypeDeclaration<?>> subclassesOf(ResolvedClassDeclaration clazz) {
        return subclassesOf(vertexMap.getClassDeclaration(mapKey(clazz)));
    }

    public Set<? extends TypeDeclaration<?>> subclassesOf(ResolvedReferenceType type) {
        return subclassesOf(vertexMap.getClassDeclaration(mapKey(type)));
    }

    public Set<? extends TypeDeclaration<?>> subclassesOf(Vertex<? extends TypeDeclaration<?>> v) {
        if (v == null) {
            return Collections.emptySet();
        }
        if (v.getDeclaration() instanceof EnumDeclaration) {
            return Set.of(v.getDeclaration());
        } else if (v.getDeclaration() instanceof AnnotationDeclaration) {
            return Set.of(v.getDeclaration());
        }
        return subclassesStreamOf(v)
                .map(Vertex::getDeclaration)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .collect(Collectors.toSet());
    }

    protected Vertex<? extends TypeDeclaration<?>> findClassVertex(TypeDeclaration<?> declaration) {
        return vertexMap.getClassDeclaration(mapKey(declaration));
    }

    public List<? extends TypeDeclaration<?>> parentOf(TypeDeclaration<?> declaration) {
        Stream<? extends TypeDeclaration<?>> extendsStream = incomingEdgesOf(findClassVertex(declaration)).stream()
                .filter(ClassArc.ParentArc.Extends.class::isInstance)
                .map(this::getEdgeSource)
                .map(Vertex::getDeclaration)
                .filter(BodyDeclaration::isTypeDeclaration)
                .map(bd -> (TypeDeclaration<?>) bd);
        Stream<? extends TypeDeclaration<?>> implementsStream = incomingEdgesOf(findClassVertex(declaration)).stream()
                .filter(ClassArc.ParentArc.Implements.class::isInstance)
                .map(this::getEdgeSource)
                .map(Vertex::getDeclaration)
                .filter(BodyDeclaration::isTypeDeclaration)
                .map(bd -> (TypeDeclaration<?>) bd);
        return Stream.concat(extendsStream,implementsStream).collect(Collectors.toList());
    }

    public MethodDeclaration findMethodByTypeAndSignature(TypeDeclaration<?> type, CallableDeclaration<?> declaration) {
        Vertex<CallableDeclaration<?>> v = vertexMap.getMethodDeclaration(mapKey(declaration, type));
        if (v != null && v.getDeclaration().isMethodDeclaration()) {
            return v.getDeclaration().asMethodDeclaration();
        }
        if (type.isClassOrInterfaceDeclaration()) {
            List<ClassOrInterfaceDeclaration> parentTypes = (List<ClassOrInterfaceDeclaration>) parentOf(type);
            for (ClassOrInterfaceDeclaration parentType : parentTypes) {
                return findMethodByTypeAndSignature(parentType, declaration);
            }
        }
        throw new IllegalArgumentException("Cannot find the given declaration: " + declaration);
    }
}
