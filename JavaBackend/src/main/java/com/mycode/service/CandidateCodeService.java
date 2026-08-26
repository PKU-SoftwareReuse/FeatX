package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dto.result.CodeFileDiffResult;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectFilePath;
import com.mycode.helper.ProjectPathMapping;
import com.mycode.service.code.AgentService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class CandidateCodeService {
    private static final long MAX_EDITABLE_FILE_BYTES = 2 * 1024 * 1024;
    private final Map<Integer, CandidateState> states = new ConcurrentHashMap<>();

    public void clear() {
        CandidateState state = state();
        state.candidateDocuments.clear();
        state.expectedCandidateKeys.clear();
        state.committedCandidateKeys.clear();
        state.stagedCandidateKeys.clear();
        state.editedAfterStageCandidateKeys.clear();
        state.stagedCandidateContents.clear();
        state.operationId = null;
    }

    public void beginOperation(String nextOperationId, Collection<String> candidateKeys) {
        CandidateState state = state();
        if (!Objects.equals(state.operationId, nextOperationId)) {
            clear();
            state.operationId = nextOperationId;
        }
        state.expectedCandidateKeys.clear();
        if (candidateKeys != null) {
            candidateKeys.stream()
                    .filter(Objects::nonNull)
                    .filter(key -> !key.isBlank())
                    .forEach(state.expectedCandidateKeys::add);
        }
    }

    public Set<String> pendingModificationKeys() {
        Set<String> keys = allCandidateKeys();
        keys.removeAll(state().committedCandidateKeys);
        return keys;
    }

    public Set<String> stagedModificationKeys() {
        Set<String> keys = new LinkedHashSet<>(state().stagedCandidateKeys);
        keys.removeAll(state().editedAfterStageCandidateKeys);
        return keys;
    }

    public Map<String, String> pendingModificationMap() {
        Map<String, String> modifications = ProjectState.getInstance().getModifications();
        if (modifications.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> pending = new LinkedHashMap<>();
        modifications.forEach((key, value) -> {
            if (!state().committedCandidateKeys.contains(key)) {
                pending.put(key, value);
            }
        });
        return pending;
    }

    public List<String> allCandidateProjectPaths() {
        return projectPathsForKeys(allCandidateKeys());
    }

    public List<String> pendingCandidateProjectPaths() {
        return projectPathsForKeys(pendingModificationKeys());
    }

    public List<String> committedCandidateProjectPaths() {
        return projectPathsForKeys(state().committedCandidateKeys);
    }

    public void markCommittedPaths(Collection<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return;
        }
        Set<String> normalizedPaths = projectPaths.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeProjectPath)
                .collect(java.util.stream.Collectors.toSet());
        for (String key : allCandidateKeys()) {
            candidateProjectPath(key)
                    .filter(normalizedPaths::contains)
                    .ifPresent(ignored -> state().committedCandidateKeys.add(key));
        }
    }

    public void markStaged(String key) {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateState state = state();
        CandidateDocument document = state.candidateDocuments.get(normalizedKey);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before staging it.");
        }
        state.stagedCandidateKeys.add(normalizedKey);
        state.editedAfterStageCandidateKeys.remove(normalizedKey);
        state.stagedCandidateContents.put(normalizedKey, document.modifiedContent);
    }

    public void markUnstaged(String key) {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateState state = state();
        state.stagedCandidateKeys.remove(normalizedKey);
        state.editedAfterStageCandidateKeys.remove(normalizedKey);
        state.stagedCandidateContents.remove(normalizedKey);
    }

    public Set<String> candidateKeysForProjectPaths(Collection<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalizedPaths = projectPaths.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeProjectPath)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> keys = new LinkedHashSet<>();
        for (String key : allCandidateKeys()) {
            candidateProjectPath(key)
                    .filter(normalizedPaths::contains)
                    .ifPresent(ignored -> keys.add(normalizeCandidateKey(key)));
        }
        return keys;
    }

    public List<String> projectPathsForCandidateKeys(Collection<String> keys) {
        return projectPathsForKeys(keys);
    }

    public String requireCandidateProjectPath(String key) {
        return candidateProjectPath(normalizeCandidateKey(key))
                .orElseThrow(() -> new IllegalStateException("Candidate file path is unavailable for " + key + "."));
    }

    public Map<String, String> adoptStagedCandidateContents(
            Collection<String> keys,
            Map<String, String> contentsByProjectPath
    ) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (String key : keys) {
            String normalizedKey = normalizeCandidateKey(key);
            CandidateDocument document = state().candidateDocuments.get(normalizedKey);
            if (document == null) {
                throw new IllegalStateException("Candidate " + normalizedKey + " has not been reviewed.");
            }
            String path = normalizeProjectPath(document.path);
            if (!contentsByProjectPath.containsKey(path)) {
                throw new IllegalStateException("The Git index has no staged version for " + path + ".");
            }
            String stagedContent = contentsByProjectPath.get(path);
            document.modifiedContent = stagedContent == null ? "" : stagedContent;
            document.pending = true;
            document.authoritative = true;
            String modification = stagedContent == null
                    ? AgentService.DELETE_FILE_SENTINEL
                    : document.modifiedContent;
            selected.put(normalizedKey, modification);
        }
        ProjectState.getInstance().setModifications(selected);
        return selected;
    }

    public void restoreCandidateModifications(Map<String, String> modifications) {
        Map<String, String> restored = modifications == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(modifications);
        restored.forEach((key, content) -> {
            String normalizedKey = normalizeCandidateKey(key);
            CandidateDocument document = state().candidateDocuments.get(normalizedKey);
            if (document != null) {
                document.modifiedContent = AgentService.DELETE_FILE_SENTINEL.equals(content) ? "" : content;
                document.pending = true;
                document.authoritative = true;
            }
        });
        ProjectState.getInstance().setModifications(restored);
    }

    public void restoreCandidateToOriginal(String key) {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateState state = state();
        CandidateDocument document = state.candidateDocuments.get(normalizedKey);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before reverting it.");
        }

        document.modifiedContent = document.originalContent;
        document.pending = false;
        document.authoritative = true;
        document.warning = null;
        state.expectedCandidateKeys.remove(normalizedKey);
        state.committedCandidateKeys.remove(normalizedKey);
        state.stagedCandidateKeys.remove(normalizedKey);
        state.editedAfterStageCandidateKeys.remove(normalizedKey);
        state.stagedCandidateContents.remove(normalizedKey);

        Map<String, String> modifications = mutableModifications();
        modifications.entrySet().removeIf(entry ->
                normalizeCandidateKey(entry.getKey()).equals(normalizedKey));
        ProjectState.getInstance().setModifications(modifications);
    }

    public void discardCandidateState() {
        clear();
        ProjectState.getInstance().setModifications(Map.of());
        ProjectState.getInstance().setPythonModifiedMethods(Set.of());
    }

    public CodeFileDiffResult prepareProjectCandidate(String relativePath, String candidateContent)
            throws IOException, InterruptedException {
        String key = ProjectFilePath.normalize(relativePath);
        Path sourceFile = ProjectPathMapping.resolveProjectFile(ProjectState.getInstance(), key);
        CandidateDocument cached = state().candidateDocuments.get(key);
        boolean originalExists = cached == null ? Files.isRegularFile(sourceFile) : cached.originalExists;
        String originalContent = cached == null
                ? originalExists ? readEditableFile(sourceFile) : ""
                : cached.originalContent;
        String modifiedContent = AgentService.DELETE_FILE_SENTINEL.equals(candidateContent) ? "" : candidateContent;
        if (key.endsWith(".java") && modifiedContent != null && !modifiedContent.isBlank()) {
            validateJavaCandidate(key, sourceFile, modifiedContent);
        }
        boolean pending = !Objects.equals(originalContent, modifiedContent == null ? originalContent : modifiedContent);
        CandidateDocument document = new CandidateDocument(
                key,
                projectRelativePath(sourceFile),
                languageFor(key),
                originalContent,
                modifiedContent == null ? originalContent : modifiedContent,
                originalExists,
                true,
                null,
                pending
        );
        state().candidateDocuments.put(key, document);
        return toResult(document);
    }

    public CodeFileDiffResult prepareManualProjectCandidate(String relativePath)
            throws IOException, InterruptedException {
        String normalizedPath = ProjectFilePath.normalize(relativePath);
        CandidateDocument cached = state().candidateDocuments.get(normalizedPath);
        if (cached != null) {
            return toResult(cached);
        }
        Path sourceFile = ProjectPathMapping.resolveProjectFile(ProjectState.getInstance(), normalizedPath);
        if (!Files.isRegularFile(sourceFile)) {
            throw new IllegalStateException("Source file does not exist for " + relativePath + ".");
        }
        String originalContent = readEditableFile(sourceFile);
        CandidateDocument document = new CandidateDocument(
                normalizedPath,
                projectRelativePath(sourceFile),
                languageFor(normalizedPath),
                originalContent,
                originalContent,
                true,
                true,
                null,
                false
        );
        state().candidateDocuments.put(normalizedPath, document);
        return toResult(document);
    }

    public CodeFileDiffResult updateCandidate(String key, String operation, String content)
            throws IOException, InterruptedException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Candidate file key is required.");
        }
        String updatedContent = content == null ? "" : content;
        if (updatedContent.getBytes(StandardCharsets.UTF_8).length > MAX_EDITABLE_FILE_BYTES) {
            throw new IllegalArgumentException("Candidate file is too large to edit online.");
        }
        String normalizedKey = normalizeCandidateKey(key);
        CandidateDocument document = state().candidateDocuments.get(normalizedKey);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before saving it.");
        }
        if (updatedContent.isBlank() && !"delete".equalsIgnoreCase(operation)) {
            throw new IllegalArgumentException("Candidate content cannot be empty.");
        }
        document.modifiedContent = updatedContent;
        document.pending = !Objects.equals(document.originalContent, updatedContent);
        document.authoritative = true;
        document.warning = null;

        Map<String, String> modifications = mutableModifications();
        modifications.entrySet().removeIf(entry ->
                normalizeCandidateKey(entry.getKey()).equals(normalizedKey));
        if (document.pending) {
            modifications.put(normalizedKey, updatedContent.isEmpty() && "delete".equalsIgnoreCase(operation)
                    ? AgentService.DELETE_FILE_SENTINEL
                    : updatedContent);
        } else {
            state().expectedCandidateKeys.remove(normalizedKey);
        }
        ProjectState.getInstance().setModifications(modifications);
        syncSavedCandidateWithWorktree(normalizedKey);
        return toResult(document);
    }

    public Optional<String> authoritativeJavaContent(String classId) {
        String normalizedClassPath = JavaFilePath.normalize(classId);
        CandidateDocument document = state().candidateDocuments.get(normalizedClassPath);
        if (document == null) {
            document = state().candidateDocuments.values().stream()
                    .filter(candidate -> "java".equals(candidate.language))
                    .filter(candidate -> ProjectPathMapping.projectRelativeToSource(
                                    ProjectState.getInstance(),
                                    candidate.path
                            )
                            .filter(normalizedClassPath::equals)
                            .isPresent())
                    .findFirst()
                    .orElse(null);
        }
        if (document == null || !document.authoritative) {
            return Optional.empty();
        }
        return Optional.of(document.modifiedContent);
    }

    public Optional<CodeFileDiffResult> existingCandidate(String key)
            throws IOException, InterruptedException {
        CandidateDocument document = state().candidateDocuments.get(normalizeCandidateKey(key));
        return document == null ? Optional.empty() : Optional.of(toResult(document));
    }

    /**
     * Performs an offline consistency check before the final commit.
     *
     * <p>This deliberately does not invoke Maven, Gradle, or a target-project
     * compiler. It only validates the candidate state already held by FeatX
     * and parses Java source with the JavaParser bundled with this service.</p>
     */
    public void validateCompleteCandidateSet() {
        validateCandidateSet(allCandidateKeys());
    }

    public void validateCandidateSet(Collection<String> candidateKeys) {
        Set<String> keys = candidateKeys == null
                ? Collections.emptySet()
                : candidateKeys.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeCandidateKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (keys.isEmpty()) {
            throw new IllegalStateException("There are no generated candidates to commit.");
        }

        for (String key : keys.stream().sorted().toList()) {
            CandidateDocument document = state().candidateDocuments.get(key);
            if (document == null) {
                throw new IllegalStateException(
                        "Candidate " + key + " has not been reviewed in the Diff Panel."
                );
            }
            if (!document.authoritative) {
                throw new IllegalStateException(
                        "Candidate " + key + " is not ready. Review and save it first."
                );
            }
            if ("java".equals(document.language) && !document.modifiedContent.isBlank()) {
                validateJavaCandidate(
                        key,
                        ProjectPathMapping.resolveProjectFile(ProjectState.getInstance(), document.path),
                        document.modifiedContent
                );
            }
        }
    }

    private void validateJavaCandidate(String key, Path sourceFile, String content) {
        String normalizedKey = ProjectFilePath.normalize(key);
        String sourceFileName = sourceFile.getFileName().toString();
        String expectedTypeName = sourceFileName.substring(0, sourceFileName.length() - ".java".length());
        CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(content);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Candidate " + normalizedKey + " is not valid Java source.", exception);
        }

        boolean containsExpectedType = compilationUnit.getTypes().stream()
                .map(TypeDeclaration::getNameAsString)
                .anyMatch(expectedTypeName::equals);
        if (!containsExpectedType) {
            throw new IllegalStateException(
                    "Candidate " + normalizedKey + " must declare the top-level type " + expectedTypeName + "."
            );
        }

        compilationUnit.getTypes().stream()
                .filter(TypeDeclaration::isPublic)
                .map(TypeDeclaration::getNameAsString)
                .filter(typeName -> !expectedTypeName.equals(typeName))
                .findFirst()
                .ifPresent(typeName -> {
                    throw new IllegalStateException(
                            "Candidate " + normalizedKey + " cannot declare public top-level type " + typeName + "."
                    );
                });

        Path analysisRoot = ProjectPathMapping.sourceRoot(ProjectState.getInstance());
        if (sourceFile.startsWith(analysisRoot)) {
            String analysisRelativePath = analysisRoot.relativize(sourceFile)
                    .toString()
                    .replace('\\', '/');
            String expectedPackage = JavaFilePath.packageName(analysisRelativePath);
            String actualPackage = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            if (!expectedPackage.equals(actualPackage)) {
                throw new IllegalStateException(
                        "Candidate " + normalizedKey + " must declare package "
                                + (expectedPackage.isEmpty() ? "<default>" : expectedPackage) + "."
                );
            }
        }
    }

    public MaterializedCandidate materializeCandidate(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Candidate file key is required.");
        }
        String normalizedKey = normalizeCandidateKey(key);
        if (state().committedCandidateKeys.contains(normalizedKey)) {
            throw new IllegalStateException("This candidate file has already been committed.");
        }
        CandidateDocument document = state().candidateDocuments.get(normalizedKey);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before confirming the file.");
        }
        if (!document.authoritative) {
            throw new IllegalStateException(
                    "This candidate is not ready. Review and save it before staging."
            );
        }
        if (!document.pending && !state().stagedCandidateKeys.contains(normalizedKey)) {
            throw new IllegalStateException("Edit and save this file before staging it.");
        }

        writeCandidateToWorktree(document);
        document.authoritative = true;
        document.warning = null;
        return new MaterializedCandidate(document.key, document.path);
    }

    private Map<String, String> mutableModifications() {
        return new LinkedHashMap<>(ProjectState.getInstance().getModifications());
    }

    private Set<String> allCandidateKeys() {
        CandidateState state = state();
        Set<String> keys = new LinkedHashSet<>(state.expectedCandidateKeys);
        keys.addAll(ProjectState.getInstance().getModifications().keySet());
        keys.addAll(state.editedAfterStageCandidateKeys);
        state.candidateDocuments.values().stream()
                .filter(document -> document.pending)
                .map(document -> document.key)
                .forEach(keys::add);
        keys.removeIf(key -> key == null || key.isBlank());
        return keys;
    }

    private String normalizeCandidateKey(String key) {
        if (key == null) {
            return "";
        }
        return ProjectFilePath.normalize(key);
    }

    private List<String> projectPathsForKeys(Collection<String> keys) {
        Set<String> paths = new LinkedHashSet<>();
        if (keys != null) {
            keys.forEach(key -> candidateProjectPath(key).ifPresent(paths::add));
        }
        return paths.stream().sorted().toList();
    }

    private Optional<String> candidateProjectPath(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        CandidateDocument document = state().candidateDocuments.get(key);
        if (document != null) {
            return Optional.of(normalizeProjectPath(document.path));
        }
        try {
            ProjectState project = ProjectState.getInstance();
            Path projectFile = ProjectPathMapping.resolveProjectFile(project, key);
            if (project.isJava()) {
                Path sourceRoot = ProjectPathMapping.sourceRoot(project);
                if (!projectFile.startsWith(sourceRoot)) {
                    // Java graph candidates are keyed relative to srcPath, while Git
                    // status is reported relative to the repository root.
                    Path sourceFile = ProjectFilePath.resolve(sourceRoot, key);
                    if (sourceFile.startsWith(ProjectPathMapping.projectRoot(project))) {
                        projectFile = sourceFile;
                    }
                }
            }
            return Optional.of(projectRelativePath(projectFile));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> javaSourceRelativePath(String key) {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateDocument document = state().candidateDocuments.get(normalizedKey);
        String projectPath = document == null ? normalizedKey : document.path;
        return ProjectPathMapping.projectRelativeToSource(ProjectState.getInstance(), projectPath)
                .filter(path -> path.endsWith(".java"));
    }

    private Path projectRoot() {
        String projectPath = ProjectState.getInstance().getProjectPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalStateException("No project is currently selected.");
        }
        return Path.of(projectPath).toAbsolutePath().normalize();
    }

    public void clearRepository(Integer repositoryId) {
        if (repositoryId != null) {
            states.remove(repositoryId);
        }
    }

    private CandidateState state() {
        return states.computeIfAbsent(ProjectState.currentRepositoryKey(), ignored -> new CandidateState());
    }

    private String normalizeProjectPath(String path) {
        return path.replace('\\', '/');
    }

    private String languageFor(String path) {
        if (path.endsWith(".java")) {
            return "java";
        }
        if (path.endsWith(".py")) {
            return "python";
        }
        return "text";
    }

    private CodeFileDiffResult toResult(CandidateDocument document) throws IOException, InterruptedException {
        CodeFileDiffResult result = new CodeFileDiffResult();
        result.setKey(document.key);
        result.setPath(document.path);
        result.setLanguage(document.language);
        result.setOriginalContent(document.originalContent);
        result.setModifiedContent(document.modifiedContent);
        result.setDiff(generateGitDiff(
                document.path,
                document.originalContent,
                document.modifiedContent,
                document.originalExists
        ));
        result.setNewFile(!document.originalExists);
        result.setDeleted(document.originalExists && document.modifiedContent.isEmpty());
        result.setEditable(true);
        String normalizedKey = normalizeCandidateKey(document.key);
        result.setStaged(state().stagedCandidateKeys.contains(normalizedKey));
        result.setStagedContent(state().stagedCandidateContents.get(normalizedKey));
        result.setWarning(document.warning);
        return result;
    }

    private void syncSavedCandidateWithWorktree(String key) throws IOException {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateState state = state();
        if (!state.stagedCandidateKeys.contains(normalizedKey)) {
            return;
        }
        CandidateDocument document = state.candidateDocuments.get(normalizedKey);
        if (document == null) {
            return;
        }
        writeCandidateToWorktree(document);
        if (Objects.equals(state.stagedCandidateContents.get(normalizedKey), document.modifiedContent)) {
            state.editedAfterStageCandidateKeys.remove(normalizedKey);
        } else {
            state.editedAfterStageCandidateKeys.add(normalizedKey);
        }
    }

    private void writeCandidateToWorktree(CandidateDocument document) throws IOException {
        Path target = safeResolve(projectRoot(), document.path);
        if (document.modifiedContent.isEmpty()) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, document.modifiedContent, StandardCharsets.UTF_8);
    }

    private String generateGitDiff(
            String relativePath,
            String originalContent,
            String modifiedContent,
            boolean originalExists
    ) throws IOException, InterruptedException {
        Path tempRoot = Files.createTempDirectory("featx-git-diff-");
        try {
            Path before = safeResolve(tempRoot.resolve("before"), relativePath);
            Path after = safeResolve(tempRoot.resolve("after"), relativePath);
            if (originalExists) {
                Files.createDirectories(before.getParent());
                Files.writeString(before, originalContent, StandardCharsets.UTF_8);
            }
            boolean modifiedExists = !originalExists || !modifiedContent.isEmpty();
            if (modifiedExists) {
                Files.createDirectories(after.getParent());
                Files.writeString(after, modifiedContent, StandardCharsets.UTF_8);
            }

            String nullDevice = System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";
            String beforeArg = originalExists ? tempRoot.relativize(before).toString() : nullDevice;
            String afterArg = modifiedExists ? tempRoot.relativize(after).toString() : nullDevice;
            Process process = new ProcessBuilder(
                    "git", "diff", "--no-ext-diff", "--no-index", "--unified=3", "--", beforeArg, afterArg
            )
                    .directory(tempRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("Git candidate diff timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && process.exitValue() != 1) {
                throw new IOException("Git candidate diff failed with exit code " + process.exitValue() + "\n" + output);
            }
            return output
                    .replace("a/before/", "a/")
                    .replace("b/before/", "b/")
                    .replace("a/after/", "a/")
                    .replace("b/after/", "b/")
                    .replace("a" + nullDevice, "/dev/null")
                    .replace("b" + nullDevice, "/dev/null");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private String readEditableFile(Path path) throws IOException {
        if (Files.size(path) > MAX_EDITABLE_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large to edit online: " + path.getFileName());
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String projectRelativePath(Path sourceFile) {
        Path projectRoot = projectRoot();
        if (!sourceFile.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Candidate file is outside the selected project.");
        }
        return projectRoot.relativize(sourceFile).toString().replace('\\', '/');
    }

    public record MaterializedCandidate(String key, String path) {
    }

    private Path safeResolve(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path is required.");
        }
        String normalizedPath = relativePath.replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.contains("../")) {
            throw new IllegalArgumentException("Invalid file path: " + relativePath);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(normalizedPath).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Invalid file path: " + relativePath);
        }
        return result;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class CandidateDocument {
        private final String key;
        private final String path;
        private final String language;
        private final String originalContent;
        private String modifiedContent;
        private final boolean originalExists;
        private boolean authoritative;
        private String warning;
        private boolean pending;

        private CandidateDocument(
                String key,
                String path,
                String language,
                String originalContent,
                String modifiedContent,
                boolean originalExists,
                boolean authoritative,
                String warning,
                boolean pending
        ) {
            this.key = key;
            this.path = path;
            this.language = language;
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.originalExists = originalExists;
            this.authoritative = authoritative;
            this.warning = warning;
            this.pending = pending;
        }
    }

    private static final class CandidateState {
        private final Map<String, CandidateDocument> candidateDocuments = new ConcurrentHashMap<>();
        private final Set<String> expectedCandidateKeys = ConcurrentHashMap.newKeySet();
        private final Set<String> committedCandidateKeys = ConcurrentHashMap.newKeySet();
        private final Set<String> stagedCandidateKeys = ConcurrentHashMap.newKeySet();
        private final Set<String> editedAfterStageCandidateKeys = ConcurrentHashMap.newKeySet();
        private final Map<String, String> stagedCandidateContents = new ConcurrentHashMap<>();
        private volatile String operationId;
    }
}
