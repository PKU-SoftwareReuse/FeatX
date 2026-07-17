package cn.edu.pku.zhuyifeng;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.UnexpectedException;
import java.util.*;

public class ImportAnalyzer {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    private static final Map<String, String> classToFileMap = new HashMap<>();
    private static final Map<String, Set<String>> packageClassesMap = new HashMap<>();
    private static final List<String> allClasses = new ArrayList<>();
    private static final Map<String, Set<String>> dependencyMap = new HashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new UnexpectedException("need 2 arguments");
        }

        Path projectRoot = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);

        analyzeImports(projectRoot, outputDir);
    }

    private static void analyzeImports(Path projectRoot, Path outputDir) throws IOException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("Invalid project directory: " + projectRoot);
        }
        if (Files.notExists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        collectJavaFiles(projectRoot);

        analyzeDependencies(projectRoot);

        writeAdjacencyMatrix(outputDir.toString() + "/file_adj_matrix.csv");
    }

    private static void collectJavaFiles(Path root) throws IOException {
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> !hasIgnoredAncestor(root, p))
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(p);
                        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                            CompilationUnit cu = parseResult.getResult().get();
                            String packageName = cu.getPackageDeclaration()
                                    .map(PackageDeclaration::getNameAsString)
                                    .orElse("");

                            // 获取文件中定义的所有顶级类
                            for (TypeDeclaration<?> type : cu.getTypes()) {
                                if (type instanceof ClassOrInterfaceDeclaration) {
                                    String className = type.getNameAsString();
                                    String fullClassName = packageName.isEmpty() ?
                                            className : packageName + "." + className;

                                    // 记录类信息
                                    allClasses.add(fullClassName);
                                    classToFileMap.put(fullClassName, p.toString());

                                    // 添加到包-类映射
                                    packageClassesMap
                                            .computeIfAbsent(packageName, k -> new HashSet<>())
                                            .add(fullClassName);
                                }
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("Error parsing file: " + p);
                        e.printStackTrace();
                    }
                });
    }

    private static void analyzeDependencies(Path root) throws IOException {
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> !hasIgnoredAncestor(root, p))
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(p);
                        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                            CompilationUnit cu = parseResult.getResult().get();
                            String packageName = cu.getPackageDeclaration()
                                    .map(PackageDeclaration::getNameAsString)
                                    .orElse("");

                            Optional<TypeDeclaration<?>> mainType = cu.getPrimaryType();
                            if (mainType.isPresent()) {
                                String sourceClass = mainType.get().getNameAsString();
                                String fullSourceClass = packageName.isEmpty() ?
                                        sourceClass : packageName + "." + sourceClass;

                                Set<String> dependencies = new HashSet<>();
                                dependencyMap.put(fullSourceClass, dependencies);

                                for (ImportDeclaration imp : cu.getImports()) {
                                    String imported = imp.getNameAsString();

                                    if (imp.isAsterisk()) {
                                        String importedPackage = imported;
                                        Set<String> packageClasses = packageClassesMap
                                                .getOrDefault(importedPackage, Collections.emptySet());
                                        dependencies.addAll(packageClasses);
                                    } else {
                                        if (allClasses.contains(imported)) {
                                            dependencies.add(imported);
                                        }
                                    }
                                }

                                if (!packageName.isEmpty()) {
                                    Set<String> samePackageClasses = packageClassesMap
                                            .getOrDefault(packageName, Collections.emptySet());

                                    samePackageClasses.stream()
                                            .filter(cls -> !cls.equals(fullSourceClass))
                                            .forEach(dependencies::add);
                                }
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("Error analyzing file: " + p);
                        e.printStackTrace();
                    }
                });
    }

    private static boolean hasIgnoredAncestor(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void writeAdjacencyMatrix(String outputFile) throws IOException {
        Collections.sort(allClasses);
        int size = allClasses.size();

        Map<String, Integer> classIndexMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            classIndexMap.put(allClasses.get(i), i);
        }

        int[][] adjMatrix = new int[size][size];

        for (String sourceClass : dependencyMap.keySet()) {
            Integer sourceIndex = classIndexMap.get(sourceClass);
            if (sourceIndex != null) {
                for (String targetClass : dependencyMap.get(sourceClass)) {
                    Integer targetIndex = classIndexMap.get(targetClass);
                    if (targetIndex != null) {
                        adjMatrix[sourceIndex][targetIndex] = 1;
                    }
                }
            }
        }

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(",");
            writer.write(String.join(",", allClasses));
            writer.write("\n");

            for (int i = 0; i < size; i++) {
                writer.write(allClasses.get(i) + ",");
                for (int j = 0; j < size; j++) {
                    writer.write(String.valueOf(adjMatrix[i][j]));
                    if (j < size - 1) writer.write(",");
                }
                writer.write("\n");
            }
        }

        System.out.println("Adjacency matrix written to: " + outputFile);
    }
}
