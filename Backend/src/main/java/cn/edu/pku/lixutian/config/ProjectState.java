package cn.edu.pku.lixutian.config;

import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class ProjectState {
    private static ProjectState instance = null;

    public static ProjectState getInstance() {
        if (instance == null) {
            instance = new ProjectState();
        }
        return instance;
    }

    private ProjectState() {
    }

    private static final String SRC_PREFIX = "src/main/java";
    private static final String PYTHON_SRC_PREFIX = "src/main/python";
    private static final String PREPROCESS_1_PREFIX = "preprocess1/main/java";
    private static final String DELOMBOK_PREFIX = "delombok/main/java";
    private static final String PREPROCESS_2_PREFIX = "preprocess2/main/java";
    private static final Set<String> IGNORED_SOURCE_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    @Getter
    private String projectPath;

    @Getter
    private String srcPath;

    @Getter
    private String preprocess1Path;

    @Getter
    private String delombokPath;

    @Getter
    private String preprocess2Path;

    public void setProjectPath(String projectPath) {
        setProjectPath(projectPath, "JAVA");
    }

    public void setProjectPath(String projectPath, String projectType) {
        this.projectPath = projectPath;
        this.projectType = normalizeProjectType(projectType);
        this.srcPath = resolveSourcePath(projectPath);
        this.preprocess1Path = projectPath + "/" + PREPROCESS_1_PREFIX;
        this.delombokPath = projectPath + "/" + DELOMBOK_PREFIX;
        this.preprocess2Path = projectPath + "/" + PREPROCESS_2_PREFIX;
        this.forcePreprocessOption = false;
    }

    @Getter
    @Setter
    private Integer repoId;


    @Getter
    @Setter
    private boolean forcePreprocessOption;

    @Getter
    private String projectType = "JAVA";

    public boolean isPython() {
        return "PYTHON".equals(projectType);
    }

    public boolean isJava() {
        return "JAVA".equals(projectType);
    }

    private String normalizeProjectType(String value) {
        if (value == null || value.isBlank()) {
            return "JAVA";
        }
        String normalized = value.trim().toUpperCase();
        return "PYTHON".equals(normalized) ? "PYTHON" : "JAVA";
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


}
