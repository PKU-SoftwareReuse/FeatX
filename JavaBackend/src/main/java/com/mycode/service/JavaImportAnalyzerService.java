package com.mycode.service;

import com.mycode.config.LtmConfig;
import com.mycode.helper.ListFileHelper;
import com.mycode.helper.ProjectFilePath;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java import analysis used by RepoSummary.
 *
 * <p>The analyzer intentionally emits the same class-name adjacency matrix as
 * the former standalone ImportAnalyzer JAR. All state is local to one call so
 * concurrent RepoSummary runs cannot contaminate each other.</p>
 */
@Service
public class JavaImportAnalyzerService {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    private final LtmConfig ltmConfig;

    public JavaImportAnalyzerService(LtmConfig ltmConfig) {
        this.ltmConfig = ltmConfig;
    }

    public record ImportEdge(String from, String to) {
    }

    /**
     * Builds import-rule edges for the files shown by the Java candidate graph.
     * Candidate contents override files on disk, which lets unsaved Agent output
     * participate in the graph as soon as the run completes.
     *
     * <p>Only edges touching an extra candidate file are returned. Existing
     * files are connected by the persisted RepoSummary matrix; this method
     * supplies the missing relationships for files absent from the reasoning
     * graph.</p>
     */
    public Set<ImportEdge> candidateEdges(
            Path projectRoot,
            Path sourceRoot,
            Collection<String> selectedProjectFiles,
            Collection<String> extraCandidateFiles,
            Map<String, String> candidateContents
    ) throws IOException {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedSourceRoot = sourceRoot.toAbsolutePath().normalize();
        Set<String> selectedFiles = normalizeProjectFiles(selectedProjectFiles);
        Set<String> extraFiles = normalizeProjectFiles(extraCandidateFiles);
        if (selectedFiles.isEmpty() || extraFiles.isEmpty()) {
            return Set.of();
        }

        Map<String, String> overrides = normalizeCandidateContents(candidateContents);
        Map<String, SourceUnit> units = loadSourceUnits(
                normalizedProjectRoot,
                normalizedSourceRoot,
                overrides
        );
        Map<String, String> classToFile = new LinkedHashMap<>();
        Map<String, Set<String>> classesByPackage = new LinkedHashMap<>();
        for (SourceUnit unit : units.values()) {
            for (String className : unit.classNames()) {
                classToFile.put(className, unit.projectPath());
                classesByPackage
                        .computeIfAbsent(unit.packageName(), ignored -> new LinkedHashSet<>())
                        .add(className);
            }
        }

        Set<ImportEdge> result = new LinkedHashSet<>();
        for (SourceUnit source : units.values()) {
            if (!selectedFiles.contains(source.projectPath())) {
                continue;
            }
            Set<String> dependencies = dependencies(source, classToFile, classesByPackage);
            for (String dependency : dependencies) {
                String targetFile = classToFile.get(dependency);
                if (targetFile == null
                        || !selectedFiles.contains(targetFile)
                        || source.projectPath().equals(targetFile)
                        || (!extraFiles.contains(source.projectPath()) && !extraFiles.contains(targetFile))) {
                    continue;
                }
                result.add(new ImportEdge(source.projectPath(), targetFile));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(ImportEdge::from).thenComparing(ImportEdge::to))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> normalizeProjectFiles(Collection<String> files) {
        Set<String> result = new LinkedHashSet<>();
        if (files == null) {
            return result;
        }
        for (String file : files) {
            if (file == null || file.isBlank()) {
                continue;
            }
            try {
                result.add(ProjectFilePath.normalize(file));
            } catch (IllegalArgumentException ignored) {
                // Non-file Agent keys cannot participate in Java import analysis.
            }
        }
        return result;
    }

    private Map<String, String> normalizeCandidateContents(Map<String, String> contents) {
        Map<String, String> result = new LinkedHashMap<>();
        if (contents == null) {
            return result;
        }
        contents.forEach((file, content) -> {
            if (file == null || file.isBlank()) {
                return;
            }
            try {
                result.put(ProjectFilePath.normalize(file), content == null ? "" : content);
            } catch (IllegalArgumentException ignored) {
                // Non-file Agent keys cannot participate in Java import analysis.
            }
        });
        return result;
    }

    private Map<String, SourceUnit> loadSourceUnits(
            Path projectRoot,
            Path sourceRoot,
            Map<String, String> overrides
    ) throws IOException {
        Map<String, SourceUnit> result = new LinkedHashMap<>();
        for (String sourceRelative : ListFileHelper.findJavaFiles(sourceRoot.toString())) {
            Path sourceFile = sourceRoot.resolve(sourceRelative).toAbsolutePath().normalize();
            if (!sourceFile.startsWith(sourceRoot)) {
                continue;
            }
            String projectPath = projectPath(projectRoot, sourceFile);
            String content = overrides.containsKey(projectPath)
                    ? overrides.get(projectPath)
                    : Files.readString(sourceFile, StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                continue;
            }
            SourceUnit unit = parseSourceUnit(projectPath, content);
            if (unit != null) {
                result.put(projectPath, unit);
            }
        }

        // New Agent files are not present in the source tree yet.
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            if (result.containsKey(entry.getKey()) || entry.getValue().isBlank()) {
                continue;
            }
            Path sourceFile = projectRoot.resolve(entry.getKey()).toAbsolutePath().normalize();
            if (!sourceFile.startsWith(sourceRoot)) {
                continue;
            }
            SourceUnit unit = parseSourceUnit(entry.getKey(), entry.getValue());
            if (unit != null) {
                result.put(entry.getKey(), unit);
            }
        }
        return result;
    }

    private SourceUnit parseSourceUnit(String projectPath, String content) {
        try {
            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(content);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return null;
            }
            CompilationUnit compilationUnit = parseResult.getResult().get();
            String packageName = packageName(compilationUnit);
            Set<String> classNames = new LinkedHashSet<>();
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                classNames.add(qualify(packageName, type.getNameAsString()));
            }
            return new SourceUnit(projectPath, packageName, classNames, compilationUnit);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Set<String> dependencies(
            SourceUnit source,
            Map<String, String> classToFile,
            Map<String, Set<String>> classesByPackage
    ) {
        Set<String> dependencies = new LinkedHashSet<>();
        for (ImportDeclaration importDeclaration : source.compilationUnit().getImports()) {
            String imported = importDeclaration.getNameAsString();
            if (importDeclaration.isAsterisk()) {
                dependencies.addAll(classesByPackage.getOrDefault(imported, Collections.emptySet()));
            } else if (classToFile.containsKey(imported)) {
                dependencies.add(imported);
            }
        }

        // Keep the same-package behavior as the generated Java import matrix.
        if (!source.packageName().isEmpty()) {
            for (String samePackageClass : classesByPackage.getOrDefault(
                    source.packageName(),
                    Collections.emptySet()
            )) {
                if (!source.classNames().contains(samePackageClass)) {
                    dependencies.add(samePackageClass);
                }
            }
        }
        return dependencies;
    }

    private String projectPath(Path projectRoot, Path file) {
        return ProjectFilePath.normalize(
                projectRoot.relativize(file).toString().replace('\\', '/')
        );
    }

    private record SourceUnit(
            String projectPath,
            String packageName,
            Set<String> classNames,
            CompilationUnit compilationUnit
    ) {
    }

    /**
     * Analyze the Java source root for a repository managed by FeatX.
     */
    public String analyzeRepository(Integer repoId) throws IOException {
        if (repoId == null || repoId <= 0) {
            throw new IllegalArgumentException("Repository id must be a positive integer.");
        }
        String configuredRepoPath = ltmConfig.getConfiguredRepoPath();
        if (configuredRepoPath == null || configuredRepoPath.isBlank()) {
            throw new IOException("The repository root is not configured.");
        }

        Path repositoriesRoot = Path.of(configuredRepoPath).toAbsolutePath().normalize();
        Path repository = repositoriesRoot.resolve(repoId.toString()).normalize();
        if (!repository.startsWith(repositoriesRoot)) {
            throw new IOException("Invalid repository path.");
        }
        if (!Files.isDirectory(repository)) {
            throw new IOException("Repository directory does not exist: " + repository);
        }

        Path conventionalSourceRoot = repository.resolve(Path.of("src", "main", "java"));
        Path analysisRoot = containsJavaFiles(conventionalSourceRoot)
                ? conventionalSourceRoot
                : repository;
        return analyze(analysisRoot);
    }

    /**
     * Analyze an explicit root. Kept public to make the parser independently
     * testable without requiring a running Spring application.
     */
    public String analyze(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            throw new IOException("Invalid project directory: " + projectRoot);
        }

        List<Path> javaFiles = collectJavaFiles(projectRoot);
        Map<String, String> classToFileMap = new HashMap<>();
        Map<String, Set<String>> packageClassesMap = new HashMap<>();
        Set<String> allClasses = new LinkedHashSet<>();
        Map<String, Set<String>> dependencyMap = new HashMap<>();
        JavaParser parser = new JavaParser();

        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit compilationUnit = parseResult.getResult().get();
            String packageName = packageName(compilationUnit);
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration)) {
                    continue;
                }
                String fullClassName = qualify(packageName, type.getNameAsString());
                allClasses.add(fullClassName);
                classToFileMap.put(fullClassName, javaFile.toString());
                packageClassesMap
                        .computeIfAbsent(packageName, ignored -> new HashSet<>())
                        .add(fullClassName);
            }
        }

        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit compilationUnit = parseResult.getResult().get();
            String packageName = packageName(compilationUnit);
            TypeDeclaration<?> primaryType = compilationUnit.getPrimaryType().orElse(null);
            if (!(primaryType instanceof ClassOrInterfaceDeclaration)) {
                continue;
            }

            String sourceClass = qualify(packageName, primaryType.getNameAsString());
            Set<String> dependencies = new HashSet<>();
            dependencyMap.put(sourceClass, dependencies);

            for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
                String imported = importDeclaration.getNameAsString();
                if (importDeclaration.isAsterisk()) {
                    dependencies.addAll(packageClassesMap.getOrDefault(imported, Collections.emptySet()));
                } else if (classToFileMap.containsKey(imported)) {
                    dependencies.add(imported);
                }
            }

            if (!packageName.isEmpty()) {
                for (String samePackageClass : packageClassesMap.getOrDefault(packageName, Collections.emptySet())) {
                    if (!samePackageClass.equals(sourceClass)) {
                        dependencies.add(samePackageClass);
                    }
                }
            }
        }

        return writeAdjacencyMatrix(allClasses, dependencyMap);
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !hasIgnoredAncestor(root, path))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private boolean containsJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !hasIgnoredAncestor(root, path))
                    .anyMatch(path -> path.getFileName().toString().endsWith(".java"));
        }
    }

    private boolean hasIgnoredAncestor(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String packageName(CompilationUnit compilationUnit) {
        return compilationUnit.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");
    }

    private String qualify(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    private String writeAdjacencyMatrix(Set<String> classes, Map<String, Set<String>> dependencyMap) {
        List<String> allClasses = new ArrayList<>(classes);
        Collections.sort(allClasses);

        Map<String, Integer> classIndexMap = new HashMap<>();
        for (int i = 0; i < allClasses.size(); i++) {
            classIndexMap.put(allClasses.get(i), i);
        }

        int[][] adjacency = new int[allClasses.size()][allClasses.size()];
        for (Map.Entry<String, Set<String>> entry : dependencyMap.entrySet()) {
            Integer sourceIndex = classIndexMap.get(entry.getKey());
            if (sourceIndex == null) {
                continue;
            }
            for (String target : entry.getValue()) {
                Integer targetIndex = classIndexMap.get(target);
                if (targetIndex != null) {
                    adjacency[sourceIndex][targetIndex] = 1;
                }
            }
        }

        StringBuilder csv = new StringBuilder();
        csv.append(',').append(String.join(",", allClasses)).append('\n');
        for (int row = 0; row < allClasses.size(); row++) {
            csv.append(allClasses.get(row)).append(',');
            for (int column = 0; column < allClasses.size(); column++) {
                if (column > 0) {
                    csv.append(',');
                }
                csv.append(adjacency[row][column]);
            }
            csv.append('\n');
        }
        return csv.toString();
    }
}
