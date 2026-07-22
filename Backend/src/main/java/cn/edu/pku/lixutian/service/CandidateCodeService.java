package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.CodeFileDiffResult;
import cn.edu.pku.lixutian.helper.JavaFilePath;
import cn.edu.pku.lixutian.helper.RewriteFileHelper;
import cn.edu.pku.lixutian.service.code.AgentService;
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

    public void registerExpectedCandidateKeys(Collection<String> candidateKeys) {
        if (candidateKeys == null) {
            return;
        }
        candidateKeys.stream()
                .filter(Objects::nonNull)
                .filter(key -> !key.isBlank())
                .forEach(state().expectedCandidateKeys::add);
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

    public Map<String, String> modificationsForKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> normalizedKeys = keys.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeCandidateKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> selected = new LinkedHashMap<>();
        ProjectState.getInstance().getModifications().forEach((key, value) -> {
            String normalizedKey = normalizeCandidateKey(key);
            if (normalizedKeys.contains(normalizedKey)) {
                selected.put(normalizedKey, value);
            }
        });
        return selected;
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
            String modification = ProjectState.getInstance().isPython() && stagedContent == null
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

    public void discardCandidate(String key) {
        String normalizedKey = normalizeCandidateKey(key);
        CandidateState state = state();
        state.candidateDocuments.remove(normalizedKey);
        state.expectedCandidateKeys.remove(normalizedKey);
        state.committedCandidateKeys.remove(normalizedKey);
        state.stagedCandidateKeys.remove(normalizedKey);
        state.editedAfterStageCandidateKeys.remove(normalizedKey);
        state.stagedCandidateContents.remove(normalizedKey);

        Map<String, String> modifications = mutableModifications();
        modifications.entrySet().removeIf(entry -> normalizeCandidateKey(entry.getKey()).equals(normalizedKey));
        ProjectState.getInstance().setModifications(modifications);
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

    public CodeFileDiffResult prepareJavaCandidate(
            String classId,
            String operation,
            String candidateBody
    ) throws IOException, InterruptedException {
        String filePath = JavaFilePath.normalize(classId);
        CandidateDocument cached = state().candidateDocuments.get(filePath);
        if (cached != null) {
            return toResult(cached);
        }

        Path sourceFile = RewriteFileHelper.resolveJavaFilePath(filePath).toAbsolutePath().normalize();
        boolean originalExists = Files.isRegularFile(sourceFile);
        String originalContent = originalExists ? readEditableFile(sourceFile) : "";
        boolean deletion = "delete".equalsIgnoreCase(operation);
        if (!deletion && (candidateBody == null || candidateBody.isBlank())) {
            throw new IllegalArgumentException("Generated Java candidate content cannot be empty.");
        }
        String modifiedContent = deletion
                ? RewriteFileHelper.buildJavaFileContent(filePath, candidateBody, null)
                : candidateBody;
        if (!modifiedContent.isBlank()) {
            validateJavaCandidate(filePath, modifiedContent);
        }
        CandidateDocument document = new CandidateDocument(
                filePath,
                projectRelativePath(sourceFile),
                "java",
                originalContent,
                modifiedContent,
                originalExists,
                true,
                null,
                true
        );
        state().candidateDocuments.put(filePath, document);
        return toResult(document);
    }

    public CodeFileDiffResult preparePythonCandidate(String key, String relativePath, String candidateContent)
            throws IOException, InterruptedException {
        Path sourceRoot = Path.of(ProjectState.getInstance().getSrcPath()).toAbsolutePath().normalize();
        Path sourceFile = safeResolve(sourceRoot, relativePath);
        CandidateDocument cached = state().candidateDocuments.get(key);
        boolean originalExists = cached == null ? Files.isRegularFile(sourceFile) : cached.originalExists;
        String originalContent = cached == null
                ? originalExists ? readEditableFile(sourceFile) : ""
                : cached.originalContent;
        String modifiedContent = AgentService.DELETE_FILE_SENTINEL.equals(candidateContent) ? "" : candidateContent;
        boolean pending = !Objects.equals(originalContent, modifiedContent == null ? originalContent : modifiedContent);
        CandidateDocument document = new CandidateDocument(
                key,
                projectRelativePath(sourceFile),
                "python",
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

    public CodeFileDiffResult prepareManualJavaCandidate(String classId)
            throws IOException, InterruptedException {
        String filePath = JavaFilePath.normalize(classId);
        CandidateDocument cached = state().candidateDocuments.get(filePath);
        if (cached != null) {
            return toResult(cached);
        }
        Path sourceFile = RewriteFileHelper.resolveJavaFilePath(filePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sourceFile)) {
            throw new IllegalStateException("Source file does not exist for " + classId + ".");
        }
        String originalContent = readEditableFile(sourceFile);
        CandidateDocument document = new CandidateDocument(
                filePath,
                projectRelativePath(sourceFile),
                "java",
                originalContent,
                originalContent,
                true,
                true,
                null,
                false
        );
        state().candidateDocuments.put(filePath, document);
        return toResult(document);
    }

    public CodeFileDiffResult prepareManualPythonCandidate(String relativePath)
            throws IOException, InterruptedException {
        String normalizedPath = normalizeProjectPath(relativePath);
        CandidateDocument cached = state().candidateDocuments.get(normalizedPath);
        if (cached != null) {
            return toResult(cached);
        }
        Path sourceRoot = Path.of(ProjectState.getInstance().getSrcPath()).toAbsolutePath().normalize();
        Path sourceFile = safeResolve(sourceRoot, normalizedPath);
        if (!Files.isRegularFile(sourceFile)) {
            throw new IllegalStateException("Source file does not exist for " + relativePath + ".");
        }
        String originalContent = readEditableFile(sourceFile);
        CandidateDocument document = new CandidateDocument(
                normalizedPath,
                projectRelativePath(sourceFile),
                "python",
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
        String normalizedInputKey = normalizeCandidateKey(key);
        if (ProjectState.getInstance().isPython()) {
            CandidateDocument document = state().candidateDocuments.get(normalizedInputKey);
            if (document == null) {
                throw new IllegalStateException("Open the candidate diff before saving it.");
            }
            Map<String, String> modifications = mutableModifications();
            if (Objects.equals(document.originalContent, updatedContent)) {
                modifications.entrySet().removeIf(entry ->
                        normalizeCandidateKey(entry.getKey()).equals(normalizedInputKey));
                state().expectedCandidateKeys.remove(normalizedInputKey);
            } else {
                modifications.put(normalizedInputKey, updatedContent.isEmpty() && "delete".equalsIgnoreCase(operation)
                        ? AgentService.DELETE_FILE_SENTINEL
                        : updatedContent);
            }
            ProjectState.getInstance().setModifications(modifications);
            preparePythonCandidate(
                    normalizedInputKey,
                    normalizedInputKey,
                    modifications.getOrDefault(normalizedInputKey, updatedContent)
            );
            syncSavedCandidateWithWorktree(normalizedInputKey);
            return toResult(state().candidateDocuments.get(normalizedInputKey));
        }

        String normalizedKey = JavaFilePath.normalize(key);
        CandidateDocument document = state().candidateDocuments.get(normalizedKey);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before saving it.");
        }
        if (updatedContent.isBlank() && !"delete".equalsIgnoreCase(operation)) {
            throw new IllegalArgumentException("Java candidate content cannot be empty.");
        }
        document.modifiedContent = updatedContent;
        document.pending = !Objects.equals(document.originalContent, updatedContent);
        document.authoritative = true;
        document.warning = null;

        Map<String, String> modifications = mutableModifications();
        modifications.entrySet().removeIf(entry ->
                normalizeCandidateKey(entry.getKey()).equals(normalizedKey));
        if (document.pending) {
            modifications.put(normalizedKey, updatedContent);
        } else {
            state().expectedCandidateKeys.remove(normalizedKey);
        }
        ProjectState.getInstance().setModifications(modifications);
        syncSavedCandidateWithWorktree(normalizedKey);
        return toResult(document);
    }

    public Optional<String> authoritativeJavaContent(String classId) {
        CandidateDocument document = state().candidateDocuments.get(JavaFilePath.normalize(classId));
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
                validateJavaCandidate(key, document.modifiedContent);
            }
        }
    }

    private void validateJavaCandidate(String key, String content) {
        String normalizedKey = JavaFilePath.normalize(key);
        String expectedTypeName = JavaFilePath.simpleTypeName(normalizedKey);
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

        String expectedPackage = JavaFilePath.packageName(normalizedKey);
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
        return ProjectState.getInstance().isPython()
                ? normalizeProjectPath(key)
                : JavaFilePath.normalize(key);
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
            Path sourceFile;
            if (ProjectState.getInstance().isPython()) {
                Path sourceRoot = Path.of(ProjectState.getInstance().getSrcPath()).toAbsolutePath().normalize();
                sourceFile = safeResolve(sourceRoot, key);
            } else {
                sourceFile = RewriteFileHelper.resolveJavaFilePath(JavaFilePath.normalize(key)).toAbsolutePath().normalize();
            }
            return Optional.of(projectRelativePath(sourceFile));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
