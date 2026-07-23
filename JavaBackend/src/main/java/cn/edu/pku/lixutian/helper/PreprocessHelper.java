package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.config.ProjectState;
import com.github.javaparser.ParseException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PreprocessHelper {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    public static NodeList<CompilationUnit> parseAllFiles() throws ParseException, IOException, InterruptedException {
        ProjectState project = ProjectState.getInstance();
        if (!project.isJava()) {
            throw new IllegalStateException("Java preprocessing can only run for a Java project.");
        }
        if (project.isForcePreprocessOption() || !containsJavaFile(Path.of(project.getPreprocess2Path()))) {
            rebuildPreprocessedSources(project);
        }
        ParserConfiguration configuration = parserConfiguration(project.getPreprocess2Path());
        return finallyParseAllFiles(project.getPreprocess2Path(), configuration);
    }

    public static void deleteDir(String dirPath) throws IOException {
        Path directory = Paths.get(dirPath);
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void rebuildPreprocessedSources(ProjectState project)
            throws ParseException, IOException, InterruptedException {
        deleteDir(project.getPreprocess1Path());
        deleteDir(project.getDelombokPath());
        deleteDir(project.getPreprocess2Path());

        NodeList<CompilationUnit> units1 = onlyParseAllFiles(project.getSrcPath());
        if (units1.isEmpty()) {
            throw new ParseException("No Java source files were found under " + project.getSrcPath());
        }
        preprocess1(units1);
        writeBack(units1, project.getSrcPath(), project.getPreprocess1Path());

        runDelombok(project.getPreprocess1Path(), project.getDelombokPath());

        NodeList<CompilationUnit> units2 = onlyParseAllFiles(project.getDelombokPath());
        preprocess2(units2);
        writeBack(units2, project.getDelombokPath(), project.getPreprocess2Path());
        if (!containsJavaFile(Path.of(project.getPreprocess2Path()))) {
            throw new ParseException("Java preprocessing produced no files under " + project.getPreprocess2Path());
        }
    }

    private static boolean containsJavaFile(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".java"));
        }
    }

    private static NodeList<CompilationUnit> onlyParseAllFiles(String srcPath) throws ParseException, IOException {
        // Use the same filtered file walk as the final parser so retained
        // build/dependency/cache directories never enter preprocessing.
        return finallyParseAllFiles(srcPath, baseParserConfiguration());
    }

    private static void preprocess1(NodeList<CompilationUnit> nodeList) {
        nodeList.accept(new ModifierVisitor<Void>() {
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
                if (isLombokAnnotation(annotationName)) {
                    // 添加一个新的 ltm.lombok.xxx 注解
                    AnnotationExpr ltmAnnotation;

                    if (n instanceof MarkerAnnotationExpr) {
                        ltmAnnotation = new MarkerAnnotationExpr("ltm.lombok." + annotationName);
                    } else if (n instanceof SingleMemberAnnotationExpr) {
                        Expression value = ((SingleMemberAnnotationExpr) n).getMemberValue();
                        ltmAnnotation = new SingleMemberAnnotationExpr(new Name("ltm.lombok." + annotationName), value);
                    } else if (n instanceof NormalAnnotationExpr) {
                        NodeList<MemberValuePair> pairs = ((NormalAnnotationExpr) n).getPairs();
                        ltmAnnotation = new NormalAnnotationExpr(new Name("ltm.lombok." + annotationName), pairs);
                    } else {
                        return;
                    }

                    // 添加新注解
                    n.getParentNode().ifPresent(parent -> {
                        if (parent instanceof NodeWithAnnotations) {
                            NodeWithAnnotations<?> annotatedParent = (NodeWithAnnotations<?>) parent;
                            annotatedParent.addAnnotation(ltmAnnotation);
                        }
                    });
                }
            }

            private boolean isLombokAnnotation(String name) {
                // 简单判断名字是否是 Lombok 的
                return name.equals("Getter") || name.equals("Setter") || name.equals("Data")
                        || name.equals("Builder") || name.equals("NoArgsConstructor")
                        || name.equals("AllArgsConstructor") || name.equals("RequiredArgsConstructor")
                        || name.equals("ToString") || name.equals("EqualsAndHashCode")
                        || name.startsWith("lombok.");
            }
        }, null);
    }

    private static void preprocess2(NodeList<CompilationUnit> nodeList) {
        nodeList.accept(new ModifierVisitor<Void>() {
            private final Deque<TypeDeclaration<?>> typeStack = new LinkedList<>();

            @Override
            public Visitable visit(ClassOrInterfaceDeclaration n, Void arg) {
                typeStack.push(n);
                if (!n.isInterface() && !n.isAbstract() && n.getConstructors().isEmpty()) {
                    ConstructorDeclaration constructor = n.addConstructor(Modifier.Keyword.PUBLIC);
                    constructor.addAnnotation("ltm.Generated");
                }
                Visitable res = super.visit(n, arg);
                typeStack.pop();
                return res;
            }

            @Override
            public Visitable visit(EnumDeclaration n, Void arg) {
                typeStack.push(n);
                if (n.getConstructors().isEmpty()) {
                    ConstructorDeclaration constructor = n.addConstructor(Modifier.Keyword.PRIVATE);
                    constructor.addAnnotation("ltm.Generated");
                }
                Visitable res = super.visit(n, arg);
                typeStack.pop();
                return res;
            }

            @Override
            public Visitable visit(FieldDeclaration n, Void arg) {
                if (n.getVariables().size() > 1) {
                    TypeDeclaration<?> parent = typeStack.peek();

                    // 找出当前 FieldDeclaration 在父类中的位置
                    NodeList<BodyDeclaration<?>> members = parent.getMembers();
                    int index = members.indexOf(n);

                    // 创建多个新的 FieldDeclaration
                    List<FieldDeclaration> newDeclarations = new ArrayList<>();
                    for (VariableDeclarator v : n.getVariables()) {
                        FieldDeclaration newDeclaration = new FieldDeclaration(n.getModifiers(), v);
                        n.getAnnotations().forEach(newDeclaration::addAnnotation);
                        newDeclarations.add(newDeclaration);
                    }

                    // 替换原来的 FieldDeclaration
                    members.remove(index);
                    members.addAll(index, newDeclarations);

                    // 不再继续访问原来的 n
                    return null;
                }
                return super.visit(n, arg);
            }


        }, null);
    }

    private static void writeBack(
            NodeList<CompilationUnit> units,
            String inputRootPath,
            String preprocessPath
    ) throws IOException {
        Path inputRoot = Paths.get(inputRootPath).toAbsolutePath().normalize();
        for (CompilationUnit unit : units) {
            if (unit.getStorage().isEmpty()) {
                throw new IOException("Parsed Java file has no source path.");
            }
            Path sourcePath = unit.getStorage().orElseThrow().getPath().toAbsolutePath().normalize();
            if (!sourcePath.startsWith(inputRoot)) {
                throw new IOException("Parsed Java file is outside the input root: " + sourcePath);
            }
            Path relativePath = inputRoot.relativize(sourcePath);
            Path newPath = Paths.get(preprocessPath, relativePath.toString());
            Files.createDirectories(newPath.getParent());
            Files.writeString(newPath, unit.toString(), StandardCharsets.UTF_8);
        }
    }

    private static ParserConfiguration parserConfiguration(String preprocessPath) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        TypeSolver javaParserTypeSolver = new JavaParserTypeSolver(preprocessPath);
        combinedTypeSolver.add(javaParserTypeSolver);
        combinedTypeSolver.add(new ReflectionTypeSolver());

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        return baseParserConfiguration().setSymbolResolver(symbolSolver);
    }

    private static ParserConfiguration baseParserConfiguration() {
        return new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private static NodeList<CompilationUnit> finallyParseAllFiles(
            String srcPath,
            ParserConfiguration configuration
    ) throws ParseException, IOException {
        NodeList<CompilationUnit> units = new NodeList<>();
        List<Problem> problems = new LinkedList<>();
        JavaParser parser = new JavaParser(configuration);
        for (File file : (Iterable<File>) findAllJavaFiles(new File(srcPath))::iterator)
            parse(parser, file, units, problems);
        if (!problems.isEmpty()) {
            for (Problem p : problems)
                System.out.println(" * " + p.getVerboseMessage());
            throw new ParseException("Some problems were found while parsing files or folders");
        }
        return units;
    }

    private static void parse(
            JavaParser parser,
            File file,
            NodeList<CompilationUnit> units,
            List<Problem> problems
    ) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            problems.addAll(result.getProblems());
            result.getResult().ifPresent(units::add);
        } catch (FileNotFoundException e) {
            problems.add(new Problem(e.getLocalizedMessage(), null, e));
        }
    }

    protected static Stream<File> findAllJavaFiles(File directory) {
        Stream.Builder<File> builder = Stream.builder();
        findAllJavaFiles(directory, builder);
        return builder.build();
    }

    protected static void findAllJavaFiles(File directory, Stream.Builder<File> builder) {
        File[] files = directory.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory() && !IGNORED_DIRECTORIES.contains(f.getName()))
                findAllJavaFiles(f, builder);
            else if (f.getName().endsWith(".java"))
                builder.accept(f);
        }
    }

    protected static void runDelombok(String srcPath, String delombokPath) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "java", "-jar", getLombokJarPath(),
                "delombok", srcPath,
                "--encoding", "UTF-8",
                "-d", delombokPath
        );
        processBuilder.directory(new File("."));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                output.append(e.getMessage()).append('\n');
            }
        }, "featx-delombok-output");
        outputReader.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            throw new IOException("Delombok timed out.\n" + output);
        }
        outputReader.join(TimeUnit.SECONDS.toMillis(5));
        if (process.exitValue() != 0) {
            throw new IOException("Delombok failed with exit code " + process.exitValue() + ".\n" + output);
        }
    }

    private static String getLombokJarPath() {
        String configuredPath = System.getenv("LOMBOK_JAR");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }

        List<String> candidates = List.of(
                "tools/lombok-1.18.36.jar",
                "JavaBackend/tools/lombok-1.18.36.jar",
                "./LoCoTeM/tools/lombok-1.18.36.jar"
        );
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
