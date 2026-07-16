package cn.edu.pku.lixutian.config;

import lombok.Getter;
import lombok.Setter;

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
        this.srcPath = projectPath + "/" + (isPython() ? PYTHON_SRC_PREFIX : SRC_PREFIX);
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


}
