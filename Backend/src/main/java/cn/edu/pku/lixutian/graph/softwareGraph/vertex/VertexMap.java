package cn.edu.pku.lixutian.graph.softwareGraph.vertex;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.softwareGraph.Buildable;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.*;

import static cn.edu.pku.lixutian.graph.softwareGraph.vertex.MapKeyUtils.mapKey;

public class VertexMap implements Buildable<NodeList<CompilationUnit>> {
    private static final Map<Integer, VertexMap> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    public static VertexMap getNewInstance() {
        VertexMap instance = new VertexMap();
        INSTANCES.put(ProjectState.currentRepositoryKey(), instance);
        return instance;
    }

    public static VertexMap getInstance() {
        return INSTANCES.get(ProjectState.currentRepositoryKey());
    }

    public static void clearRepository(Integer repositoryId) {
        if (repositoryId != null) {
            INSTANCES.remove(repositoryId);
        }
    }

    private VertexMap() {
    }

    protected boolean built = false;

    @Override
    public boolean isBuilt() {
        return built;
    }

    /**
     * implements Buildable&lt;T&gt;
     */
    @Override
    public void build(NodeList<CompilationUnit> arg) {
        if (isBuilt())
            return;
        buildVertices(arg);
        built = true;
    }

    private final Map<String, Vertex<TypeDeclaration<?>>> classDeclarationMap = new HashMap<>();
    private final Map<String, Vertex<FieldDeclaration>> fieldDeclarationMap = new HashMap<>();
    private final Map<String, Vertex<CallableDeclaration<?>>> methodDeclarationMap = new HashMap<>();
    private final Map<String, Vertex<InitializerDeclaration>> initializerDeclarationMap = new HashMap<>();
    private final Map<String, Vertex<AnnotationMemberDeclaration>> annotationMemberDeclarationMap = new HashMap<>();

    public Map<String, Vertex<CallableDeclaration<?>>> getMethodDeclarationMap() {
        return methodDeclarationMap;
    }

    public Vertex<TypeDeclaration<?>> getClassDeclaration(String key) {
        return classDeclarationMap.get(key);
    }

    public Vertex<FieldDeclaration> getFieldDeclaration(String key) {
        return fieldDeclarationMap.get(key);
    }

    public Vertex<CallableDeclaration<?>> getMethodDeclaration(String key) {
        return methodDeclarationMap.get(key);
    }

    public Vertex<InitializerDeclaration> getInitializerDeclaration(String key) {
        return initializerDeclarationMap.get(key);
    }

    public Vertex<AnnotationMemberDeclaration> getAnnotationMemberDeclaration(String key) {
        return annotationMemberDeclarationMap.get(key);
    }

    public Map<String, Map<String, Vertex<?>>> getAllVertexMap() {
        Map<String, Map<String, Vertex<?>>> map = new HashMap<>();
        map.put("classDeclarationMap", Collections.unmodifiableMap(classDeclarationMap));
        map.put("fieldDeclarationMap", Collections.unmodifiableMap(fieldDeclarationMap));
        map.put("methodDeclarationMap", Collections.unmodifiableMap(methodDeclarationMap));
        map.put("initializerDeclarationMap", Collections.unmodifiableMap(initializerDeclarationMap));
        map.put("annotationMemberDeclarationMap", Collections.unmodifiableMap(annotationMemberDeclarationMap));
        return map;
    }

    private void buildVertices(NodeList<CompilationUnit> arg) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            private final Deque<TypeDeclaration<?>> typeStack = new LinkedList<>();

            /**
             * CompletedTODO QUESTIONS & LACKS:
             *  x) Is it necessary to include something apart from class vertices?      InitializerDeclaration
             *  x) Private classes inside other classes?   InnerClass
             *  x) Static declaration blocks not considered   StaticStatement: InitializerDeclaration
             */

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                typeStack.push(n);
                addTypeDeclaration(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                typeStack.push(n);
                addTypeDeclaration(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(AnnotationDeclaration n, Void arg) {
                typeStack.push(n);
                addTypeDeclaration(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(InitializerDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                addInitializerDeclaration(n, typeStack.peek());
            }

            @Override
            public void visit(FieldDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                addFieldDeclaration(n, typeStack.peek());
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                if( typeStack.peek() != null){
                    addCallableDeclaration(n, typeStack.peek());
                }else{
                    // TODO 忽略record
                }
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                addCallableDeclaration(n, typeStack.peek());
            }

            @Override
            public void visit(AnnotationMemberDeclaration n, Void arg) {
                assert typeStack.peek() != null;
                addAnnotationMemberDeclaration(n, typeStack.peek());
            }
        }, null);
    }

    private void addTypeDeclaration(TypeDeclaration<?> n) {
        Vertex<TypeDeclaration<?>> v = new Vertex<>(n, mapKey(n));
        classDeclarationMap.put(mapKey(n), v);
    }

    private void addInitializerDeclaration(InitializerDeclaration n, TypeDeclaration<?> c) {
        Vertex<InitializerDeclaration> v = new Vertex<>(n, mapKey(n, c));
        initializerDeclarationMap.put(mapKey(n, c), v);
    }

    private void addFieldDeclaration(FieldDeclaration n, TypeDeclaration<?> c) {
        Vertex<FieldDeclaration> v = new Vertex<>(n, mapKey(n, c));
        fieldDeclarationMap.put(mapKey(n, c), v);
    }

    private void addCallableDeclaration(CallableDeclaration<?> n, TypeDeclaration<?> c) {
        assert n instanceof ConstructorDeclaration || n instanceof MethodDeclaration;
        Vertex<CallableDeclaration<?>> v = new Vertex<>(n, mapKey(n, c));
        methodDeclarationMap.put(mapKey(n, c), v);
    }

    private void addAnnotationMemberDeclaration(AnnotationMemberDeclaration n, TypeDeclaration<?> c) {
        Vertex<AnnotationMemberDeclaration> v = new Vertex<>(n, mapKey(n, c));
        annotationMemberDeclarationMap.put(mapKey(n, c), v);
    }
}
