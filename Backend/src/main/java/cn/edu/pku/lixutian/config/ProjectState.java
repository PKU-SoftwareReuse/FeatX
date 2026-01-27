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
        this.projectPath = projectPath;
        this.srcPath = projectPath + "/" + SRC_PREFIX;
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


}
