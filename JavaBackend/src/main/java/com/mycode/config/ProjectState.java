package com.mycode.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Project-scoped runtime state bound to the current workspace request.
 *
 * <p>Legacy callers still use {@link #getInstance()}, but the returned object is
 * selected from the current request binding instead of being a process-wide
 * singleton. Repository state is shared by workspaces viewing the same project;
 * the workspace binding itself remains tab-local.</p>
 */
public final class ProjectState {
    public static final String WORKSPACE_HEADER = "X-FeatX-Workspace-Id";
    public static final String REPOSITORY_HEADER = "X-FeatX-Repo-Id";

    private static final String LEGACY_WORKSPACE = "__legacy__";
    private static final int LEGACY_REPOSITORY_KEY = Integer.MIN_VALUE;
    private static final String SRC_PREFIX = "src/main/java";
    private static final String PYTHON_SRC_PREFIX = "src/main/python";
    private static final String PREPROCESS_1_PREFIX = "preprocess1/main/java";
    private static final String DELOMBOK_PREFIX = "delombok/main/java";
    private static final String PREPROCESS_2_PREFIX = "preprocess2/main/java";
    private static final Set<String> IGNORED_SOURCE_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    private static final ConcurrentHashMap<Integer, ProjectState> REPOSITORIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> WORKSPACES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ProjectState> UNBOUND = ThreadLocal.withInitial(ProjectState::new);

    /** Original imported Git workspace. Agent file paths are relative to this root. */
    private volatile String projectPath;
    /** Original language-analysis root. Java preprocessing reads files only from this tree. */
    private volatile String srcPath;
    /** Derived Java-only trees; every file keeps its path relative to {@link #srcPath}. */
    private volatile String preprocess1Path;
    private volatile String delombokPath;
    private volatile String preprocess2Path;
    private volatile Integer repoId;
    private volatile boolean forcePreprocessOption;
    private volatile String projectType = "JAVA";
    private volatile Map<String, String> modifications = Map.of();
    private volatile Set<String> pythonModifiedMethods = Set.of();

    private ProjectState() {
    }

    public static ProjectState getInstance() {
        Binding binding = CURRENT.get();
        if (binding == null) {
            return projectForWorkspace(LEGACY_WORKSPACE).orElseGet(UNBOUND::get);
        }
        if (binding.state == null) {
            throw new IllegalStateException("No project is selected for this workspace.");
        }
        return binding.state;
    }

    public static Optional<ProjectState> currentProject() {
        Binding binding = CURRENT.get();
        if (binding == null) {
            Optional<ProjectState> selected = projectForWorkspace(LEGACY_WORKSPACE);
            return selected.isPresent()
                    ? selected
                    : Optional.of(UNBOUND.get()).filter(state -> state.repoId != null);
        }
        return Optional.ofNullable(binding.state);
    }

    public static String currentWorkspaceId() {
        Binding binding = CURRENT.get();
        return binding == null ? LEGACY_WORKSPACE : binding.workspaceId;
    }

    public static int currentRepositoryKey() {
        Integer repositoryId = getInstance().repoId;
        return repositoryId == null ? LEGACY_REPOSITORY_KEY : repositoryId;
    }

    public static ProjectState selectWorkspace(
            String workspaceId,
            Integer repositoryId,
            String projectPath,
            String projectType
    ) {
        ProjectState state = loadRepository(repositoryId, projectPath, projectType);
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("Workspace id is required.");
        }
        WORKSPACES.put(workspaceId, repositoryId);
        return state;
    }

    public static ProjectState loadRepository(
            Integer repositoryId,
            String projectPath,
            String projectType
    ) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("Project id is required.");
        }
        ProjectState state = REPOSITORIES.computeIfAbsent(repositoryId, ignored -> new ProjectState());
        synchronized (state) {
            state.repoId = repositoryId;
            state.configureProjectPath(projectPath, projectType);
        }
        REPOSITORIES.put(repositoryId, state);
        return state;
    }

    public static void assignWorkspace(String workspaceId, Integer repositoryId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("Workspace id is required.");
        }
        if (repositoryId == null || !REPOSITORIES.containsKey(repositoryId)) {
            throw new IllegalStateException("Project runtime is not loaded: " + repositoryId);
        }
        WORKSPACES.put(workspaceId, repositoryId);
    }

    public static Optional<ProjectState> projectForWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        Integer repositoryId = WORKSPACES.get(workspaceId);
        return Optional.ofNullable(repositoryId).map(REPOSITORIES::get);
    }

    public static Optional<ProjectState> projectForRepository(Integer repositoryId) {
        return Optional.ofNullable(repositoryId).map(REPOSITORIES::get);
    }

    public static Scope bindRequest(String workspaceId, Integer repositoryHint) {
        String normalizedWorkspace = workspaceId == null || workspaceId.isBlank()
                ? LEGACY_WORKSPACE
                : workspaceId.trim();
        Integer selectedRepository = WORKSPACES.get(normalizedWorkspace);
        if (selectedRepository == null && repositoryHint != null && REPOSITORIES.containsKey(repositoryHint)) {
            WORKSPACES.put(normalizedWorkspace, repositoryHint);
            selectedRepository = repositoryHint;
        }
        ProjectState state = selectedRepository == null ? null : REPOSITORIES.get(selectedRepository);
        return bind(normalizedWorkspace, state);
    }

    public static Scope bindWorkspace(String workspaceId) {
        return bindRequest(workspaceId, null);
    }

    public static Scope bindProject(String workspaceId, ProjectState state) {
        if (state == null) {
            throw new IllegalArgumentException("Project state is required.");
        }
        return bind(workspaceId == null || workspaceId.isBlank() ? LEGACY_WORKSPACE : workspaceId, state);
    }

    public static Scope bindRepository(Integer repositoryId) {
        ProjectState state = projectForRepository(repositoryId)
                .orElseThrow(() -> new IllegalStateException("Project runtime is not loaded: " + repositoryId));
        return bind(LEGACY_WORKSPACE, state);
    }

    public static CapturedContext capture() {
        Binding binding = CURRENT.get();
        if (binding == null) {
            return new CapturedContext(
                    LEGACY_WORKSPACE,
                    projectForWorkspace(LEGACY_WORKSPACE).orElseGet(UNBOUND::get)
            );
        }
        return new CapturedContext(binding.workspaceId, binding.state);
    }

    public static void clearWorkspace(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            WORKSPACES.remove(workspaceId);
        }
    }

    public static void removeRepository(Integer repositoryId) {
        if (repositoryId == null) {
            return;
        }
        REPOSITORIES.remove(repositoryId);
        WORKSPACES.entrySet().removeIf(entry -> repositoryId.equals(entry.getValue()));
    }

    private static Scope bind(String workspaceId, ProjectState state) {
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(workspaceId, state));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public synchronized void setProjectPath(String projectPath) {
        configureProjectPath(projectPath, "JAVA");
    }

    public synchronized void setProjectPath(String projectPath, String projectType) {
        configureProjectPath(projectPath, projectType);
    }

    private void configureProjectPath(String nextProjectPath, String nextProjectType) {
        if (nextProjectPath == null || nextProjectPath.isBlank()) {
            throw new IllegalArgumentException("Project path is required.");
        }
        String normalizedType = normalizeProjectType(nextProjectType);
        boolean changed = !nextProjectPath.equals(this.projectPath) || !normalizedType.equals(this.projectType);
        this.projectPath = nextProjectPath;
        this.projectType = normalizedType;
        this.srcPath = resolveSourcePath(nextProjectPath);
        this.preprocess1Path = nextProjectPath + "/" + PREPROCESS_1_PREFIX;
        this.delombokPath = nextProjectPath + "/" + DELOMBOK_PREFIX;
        this.preprocess2Path = nextProjectPath + "/" + PREPROCESS_2_PREFIX;
        if (changed) {
            this.forcePreprocessOption = false;
        }
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getSrcPath() {
        return srcPath;
    }

    public String getPreprocess1Path() {
        return preprocess1Path;
    }

    public String getDelombokPath() {
        return delombokPath;
    }

    public String getPreprocess2Path() {
        return preprocess2Path;
    }

    public Integer getRepoId() {
        return repoId;
    }

    public void setRepoId(Integer repoId) {
        this.repoId = repoId;
    }

    public boolean isForcePreprocessOption() {
        return forcePreprocessOption;
    }

    public void setForcePreprocessOption(boolean forcePreprocessOption) {
        this.forcePreprocessOption = forcePreprocessOption;
    }

    public String getProjectType() {
        return projectType;
    }

    public boolean isPython() {
        return "PYTHON".equals(projectType);
    }

    public boolean isJava() {
        return "JAVA".equals(projectType);
    }

    public Map<String, String> getModifications() {
        return modifications;
    }

    public void setModifications(Map<String, String> values) {
        modifications = values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Set<String> getPythonModifiedMethods() {
        return pythonModifiedMethods;
    }

    public void setPythonModifiedMethods(Set<String> values) {
        pythonModifiedMethods = values == null || values.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private String normalizeProjectType(String value) {
        if (value == null || value.isBlank()) {
            return "JAVA";
        }
        return "PYTHON".equals(value.trim().toUpperCase()) ? "PYTHON" : "JAVA";
    }

    private String resolveSourcePath(String root) {
        Path projectRoot = Path.of(root).normalize();
        Path conventional = projectRoot.resolve(isPython() ? PYTHON_SRC_PREFIX : SRC_PREFIX);
        if (containsSourceFiles(conventional)) {
            return conventional.toString();
        }
        return projectRoot.toString();
    }

    private boolean containsSourceFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        String extension = isPython() ? ".py" : ".java";
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root.relativize(path)))
                    .anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(extension));
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isIgnoredPath(Path path) {
        for (Path part : path) {
            if (IGNORED_SOURCE_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private record Binding(String workspaceId, ProjectState state) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record CapturedContext(String workspaceId, ProjectState state) {
        public void run(Runnable runnable) {
            try (Scope ignored = bindProject(workspaceId, state)) {
                runnable.run();
            }
        }

        public <T> T call(Supplier<T> supplier) {
            try (Scope ignored = bindProject(workspaceId, state)) {
                return supplier.get();
            }
        }
    }
}
