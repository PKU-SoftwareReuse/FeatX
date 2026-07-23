package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.helper.GitRemoteHelper;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectInfoResult {
    private Integer id;

    private String projectName;

    private String description;

    private String descriptionCn;

    private String projectType;

    private String gitLink;

    private String gitName;

    private String gitProvider;

    // Kept for clients built against the previous response shape.
    private String githubLink;

    private String githubName;

    private Integer loc;

    private Integer noc;

    private Integer nom;

    private Integer nof;

    private Boolean summaryFlag;


    public ProjectInfoResult(ProjectInfo projectInfo) {
        this.id = projectInfo.getId();
        this.projectName = projectInfo.getRepoName();
        this.description = projectInfo.getDescription();
        this.descriptionCn = projectInfo.getDescriptionCn();
        this.projectType = hasText(projectInfo.getProjectType())
                ? projectInfo.getProjectType()
                : extractProjectType(this.description);
        this.gitLink = projectInfo.getGitLink();
        this.gitName = GitRemoteHelper.displayName(this.gitLink);
        this.gitProvider = GitRemoteHelper.provider(this.gitLink);
        this.githubLink = projectInfo.getGitLink();
        this.githubName = this.gitName;
        this.loc = projectInfo.getLoc();
        this.noc = projectInfo.getNoc();
        this.nom = projectInfo.getNom();
        this.nof = projectInfo.getNof();
        this.summaryFlag = projectInfo.getSummaryFlag();
    }

    private static String extractProjectType(String description) {
        if (description == null) return "JAVA";
        String normalized = description.toLowerCase();
        if (normalized.contains("[python]") || normalized.contains("python repo")) return "PYTHON";
        return "JAVA";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
