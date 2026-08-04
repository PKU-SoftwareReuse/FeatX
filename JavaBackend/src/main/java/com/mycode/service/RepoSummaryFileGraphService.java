package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dao.CodeMap;
import com.mycode.dao.repository.CodeMapRepository;
import com.mycode.dto.result.FeatureGraphResult;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectFilePath;
import com.mycode.helper.ProjectPathMapping;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the read-only CodeMap file graph from RepoSummary artifacts.
 *
 * <p>This adapter is intentionally independent of the legacy SKG/container graph
 * builders. RepoSummary uses class names for Java file matrices and source-root
 * relative paths for Python, so both are normalized to project-relative source
 * file ids at this API boundary.</p>
 */
@Service
public class RepoSummaryFileGraphService {
    private static final List<String> METHOD_INDEX_FILES = List.of(
            "method_file_map.csv",
            "method.csv",
            "methods.csv",
            "features.csv"
    );

    private final CodeMapRepository codeMapRepository;

    public RepoSummaryFileGraphService(CodeMapRepository codeMapRepository) {
        this.codeMapRepository = codeMapRepository;
    }

    public FeatureGraphResult getFeatureFileGraph(Integer featureId) {
        if (featureId == null) {
            throw new IllegalArgumentException("Feature id is required for the RepoSummary file graph.");
        }
        Integer repositoryId = ProjectState.getInstance().getRepoId();
        if (repositoryId == null) {
            throw new IllegalStateException("No project is currently selected.");
        }
        String repoSummaryDir = environmentOrDefault("REPOSUMMARY_DIR", "./PyBackend");
        Path outputDirectory = Path.of(repoSummaryDir, "output", repositoryId.toString());
        return getFeatureFileGraph(featureId, outputDirectory);
    }

    FeatureGraphResult getFeatureFileGraph(Integer featureId, Path outputDirectory) {
        ProjectState project = ProjectState.getInstance();
        List<CodeMap> codeMaps = codeMapRepository.findByFeature_Id(featureId);
        if (codeMaps.isEmpty()) {
            return emptyGraph();
        }

        MethodLocationIndex methodIndex = loadMethodLocationIndex(outputDirectory);
        Map<String, List<String>> methodsByFile = new LinkedHashMap<>();
        Map<String, String> matrixAliases = new LinkedHashMap<>();
        List<String> unmappedMethods = new ArrayList<>();

        for (CodeMap codeMap : codeMaps) {
            String methodName = codeMap.getMethodName() == null ? "" : codeMap.getMethodName().trim();
            if (methodName.isBlank()) {
                continue;
            }

            MethodLocation location = methodIndex.find(methodName)
                    .orElseGet(() -> inferMethodLocation(methodName, project));
            try {
                String fileId = toProjectFileId(location.fileIdentifier(), location.ownerIdentifier(), project);
                methodsByFile.computeIfAbsent(fileId, ignored -> new ArrayList<>());
                List<String> methods = methodsByFile.get(fileId);
                if (!methods.contains(methodName)) {
                    methods.add(methodName);
                }
                registerAliases(matrixAliases, location, fileId);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                unmappedMethods.add(methodName);
            }
        }

        if (!unmappedMethods.isEmpty()) {
            throw new IllegalStateException(
                    "RepoSummary has no file mapping for feature " + featureId + " methods: "
                            + summarize(unmappedMethods)
            );
        }

        Set<FeatureGraphResult.Node> nodes = new LinkedHashSet<>();
        methodsByFile.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> nodes.add(new FeatureGraphResult.Node(
                        entry.getKey(),
                        new ArrayList<>(entry.getValue())
                )));

        Set<String> selectedFiles = new LinkedHashSet<>(methodsByFile.keySet());
        Set<FeatureGraphResult.Edge> edges = loadSelectedFileEdges(
                outputDirectory.resolve("file_adj_matrix.csv"),
                selectedFiles,
                matrixAliases,
                project
        );
        return new FeatureGraphResult(nodes, edges);
    }

    private MethodLocationIndex loadMethodLocationIndex(Path outputDirectory) {
        MethodLocationIndex result = new MethodLocationIndex();
        boolean foundIndex = false;
        for (String fileName : METHOD_INDEX_FILES) {
            Path csvPath = outputDirectory.resolve(fileName);
            if (!Files.isRegularFile(csvPath)) {
                continue;
            }
            foundIndex = true;
            readMethodLocations(csvPath, result);
        }
        if (!foundIndex) {
            throw new IllegalStateException(
                    "RepoSummary method index was not found under " + outputDirectory
            );
        }
        return result;
    }

    private void readMethodLocations(Path csvPath, MethodLocationIndex index) {
        try (PushbackReader reader = csvReader(csvPath)) {
            List<String> header = readCsvRecord(reader);
            if (header == null) {
                return;
            }
            int methodIndex = firstColumn(header, "method_name", "method_signature");
            int fileIndex = firstColumn(header, "func_file", "file_path");
            int ownerIndex = firstColumn(header, "class_name");
            if (methodIndex < 0 || (fileIndex < 0 && ownerIndex < 0)) {
                return;
            }

            List<String> row;
            while ((row = readCsvRecord(reader)) != null) {
                String methodName = column(row, methodIndex);
                String fileIdentifier = column(row, fileIndex);
                String ownerIdentifier = column(row, ownerIndex);
                if (methodName.isBlank() || (fileIdentifier.isBlank() && ownerIdentifier.isBlank())) {
                    continue;
                }
                index.add(new MethodLocation(methodName, fileIdentifier, ownerIdentifier));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read RepoSummary method index " + csvPath, exception);
        }
    }

    private Set<FeatureGraphResult.Edge> loadSelectedFileEdges(
            Path matrixPath,
            Set<String> selectedFiles,
            Map<String, String> matrixAliases,
            ProjectState project
    ) {
        if (!Files.isRegularFile(matrixPath)) {
            throw new IllegalStateException("RepoSummary file adjacency matrix was not found: " + matrixPath);
        }

        Set<FeatureGraphResult.Edge> edges = new LinkedHashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        try (PushbackReader reader = csvReader(matrixPath)) {
            List<String> header = readCsvRecord(reader);
            if (header == null || header.size() < 2) {
                return edges;
            }

            List<String> row;
            while ((row = readCsvRecord(reader)) != null) {
                if (row.isEmpty() || row.get(0).isBlank()) {
                    continue;
                }
                String source = resolveMatrixFileId(row.get(0), matrixAliases, project);
                if (!selectedFiles.contains(source)) {
                    continue;
                }
                int valueCount = Math.min(row.size() - 1, header.size() - 1);
                for (int index = 0; index < valueCount; index++) {
                    if (!isEdgeValue(row.get(index + 1))) {
                        continue;
                    }
                    String target = resolveMatrixFileId(header.get(index + 1), matrixAliases, project);
                    if (!selectedFiles.contains(target) || source.equals(target)) {
                        continue;
                    }
                    String edgeKey = source + "\u0000" + target;
                    if (edgeKeys.add(edgeKey)) {
                        edges.add(new FeatureGraphResult.Edge(source, target));
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read RepoSummary file matrix " + matrixPath, exception);
        }
        return edges;
    }

    private String resolveMatrixFileId(
            String matrixIdentifier,
            Map<String, String> aliases,
            ProjectState project
    ) {
        String normalized = normalizeIdentifier(matrixIdentifier);
        String direct = aliases.get(normalized);
        if (direct != null) {
            return direct;
        }

        if (project.isJava()) {
            Optional<Map.Entry<String, String>> nestedOwner = aliases.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(normalized + ".")
                            || normalized.startsWith(entry.getKey() + "."))
                    .max((left, right) -> Integer.compare(left.getKey().length(), right.getKey().length()));
            if (nestedOwner.isPresent()) {
                return nestedOwner.get().getValue();
            }
        }
        return toProjectFileId(matrixIdentifier, project.isJava() ? matrixIdentifier : "", project);
    }

    private void registerAliases(Map<String, String> aliases, MethodLocation location, String fileId) {
        addAlias(aliases, location.fileIdentifier(), fileId);
        addAlias(aliases, location.ownerIdentifier(), fileId);
        addAlias(aliases, fileId, fileId);
    }

    private void addAlias(Map<String, String> aliases, String value, String fileId) {
        String normalized = normalizeIdentifier(value);
        if (!normalized.isBlank()) {
            aliases.putIfAbsent(normalized, fileId);
        }
    }

    private MethodLocation inferMethodLocation(String methodName, ProjectState project) {
        if (project.isJava()) {
            String owner = ownerFromMethodSignature(methodName);
            return new MethodLocation(methodName, owner, owner);
        }

        String owner = ownerFromMethodSignature(methodName);
        Path sourceRoot = ProjectPathMapping.sourceRoot(project);
        String current = owner;
        while (!current.isBlank()) {
            String modulePath = current.replace('.', '/');
            for (String candidate : List.of(modulePath + ".py", modulePath + "/__init__.py")) {
                if (Files.isRegularFile(sourceRoot.resolve(candidate).normalize())) {
                    return new MethodLocation(methodName, candidate, current);
                }
            }
            int separator = current.lastIndexOf('.');
            if (separator < 0) {
                break;
            }
            current = current.substring(0, separator);
        }
        return new MethodLocation(methodName, "", owner);
    }

    private String toProjectFileId(
            String fileIdentifier,
            String ownerIdentifier,
            ProjectState project
    ) {
        String value = normalizeIdentifier(fileIdentifier);
        if (value.isBlank() && project.isJava()) {
            value = normalizeIdentifier(ownerIdentifier);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("RepoSummary file identifier is empty.");
        }

        Path projectRoot = ProjectPathMapping.projectRoot(project);
        Path sourceRoot = ProjectPathMapping.sourceRoot(project);
        try {
            Path candidate = Path.of(value);
            if (candidate.isAbsolute()) {
                Path absolute = candidate.toAbsolutePath().normalize();
                if (absolute.startsWith(projectRoot)) {
                    return ProjectPathMapping.absoluteToProject(project, absolute);
                }
                if (project.isJava() && !normalizeIdentifier(ownerIdentifier).isBlank()) {
                    value = JavaFilePath.fromClassName(ownerIdentifier);
                } else {
                    throw new IllegalArgumentException("RepoSummary file is outside the selected project: " + value);
                }
            }
        } catch (RuntimeException exception) {
            if (project.isJava() && !normalizeIdentifier(ownerIdentifier).isBlank()) {
                value = JavaFilePath.fromClassName(ownerIdentifier);
            } else {
                throw exception;
            }
        }

        if (project.isJava() && !value.toLowerCase(Locale.ROOT).endsWith(".java")) {
            value = JavaFilePath.fromClassName(value);
        }
        String relativePath = ProjectFilePath.normalize(value);
        if (ProjectPathMapping.projectRelativeToSource(project, relativePath).isPresent()) {
            return relativePath;
        }

        Path sourceCandidate = sourceRoot.resolve(relativePath).normalize();
        if (!sourceCandidate.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("RepoSummary file escapes the source root: " + value);
        }
        return ProjectPathMapping.sourceRelativeToProject(project, relativePath);
    }

    private static String ownerFromMethodSignature(String methodName) {
        String base = methodName == null ? "" : methodName.split("\\(", 2)[0].trim();
        int separator = base.lastIndexOf('.');
        return separator < 0 ? "" : base.substring(0, separator);
    }

    private static boolean isEdgeValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Double.parseDouble(value.trim()) != 0.0d;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int firstColumn(List<String> header, String... candidates) {
        for (String candidate : candidates) {
            for (int index = 0; index < header.size(); index++) {
                if (candidate.equalsIgnoreCase(header.get(index).trim())) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String column(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index).trim();
    }

    private static PushbackReader csvReader(Path path) throws IOException {
        Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        return new PushbackReader(reader, 1);
    }

    /** Reads one RFC-4180-style CSV record, including quoted multiline fields. */
    private static List<String> readCsvRecord(PushbackReader reader) throws IOException {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean sawCharacter = false;

        int codePoint;
        while ((codePoint = reader.read()) >= 0) {
            sawCharacter = true;
            char character = (char) codePoint;
            if (character == '"') {
                if (inQuotes) {
                    int next = reader.read();
                    if (next == '"') {
                        current.append('"');
                    } else {
                        inQuotes = false;
                        if (next >= 0) {
                            reader.unread(next);
                        }
                    }
                } else if (current.isEmpty()) {
                    inQuotes = true;
                } else {
                    current.append(character);
                }
                continue;
            }
            if (!inQuotes && character == ',') {
                values.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (!inQuotes && character == '\n') {
                values.add(current.toString());
                return values;
            }
            if (!inQuotes && character == '\r') {
                continue;
            }
            current.append(character);
        }

        if (!sawCharacter && values.isEmpty() && current.isEmpty()) {
            return null;
        }
        values.add(current.toString());
        return values;
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String canonicalMethodName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static String summarize(Collection<String> values) {
        List<String> sample = values.stream().limit(5).toList();
        return String.join(", ", sample) + (values.size() > sample.size() ? " ..." : "");
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static FeatureGraphResult emptyGraph() {
        return new FeatureGraphResult(new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    private record MethodLocation(String methodName, String fileIdentifier, String ownerIdentifier) {
    }

    private static final class MethodLocationIndex {
        private final Map<String, MethodLocation> exact = new LinkedHashMap<>();
        private final Map<String, MethodLocation> canonical = new LinkedHashMap<>();

        private void add(MethodLocation location) {
            exact.putIfAbsent(location.methodName(), location);
            canonical.putIfAbsent(canonicalMethodName(location.methodName()), location);
        }

        private Optional<MethodLocation> find(String methodName) {
            MethodLocation location = exact.get(methodName);
            if (location == null) {
                location = canonical.get(canonicalMethodName(methodName));
            }
            return Optional.ofNullable(location);
        }
    }
}
