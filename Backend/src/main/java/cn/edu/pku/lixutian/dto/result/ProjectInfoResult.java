package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.dao.ProjectInfo;
import lombok.Getter;
import lombok.Setter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
public class ProjectInfoResult {
    private Integer id;

    private String projectName;

    private String description;

    private String projectType;

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
        this.projectType = extractProjectType(this.description);
        this.githubLink = projectInfo.getGitLink();
        this.githubName = extractGitName(this.githubLink);
        this.loc = projectInfo.getLoc();
        this.noc = projectInfo.getNoc();
        this.nom = projectInfo.getNom();
        this.nof = projectInfo.getNof();
        this.summaryFlag = projectInfo.getSummaryFlag();
    }

    private static String extractGitName(String url) {
        if (url == null || url.isEmpty()) return "Blank Git Link";

        // 支持 SSH 和 HTTPS 格式，提取 用户名/仓库名
        Pattern pattern = Pattern.compile(
                "(?:https://|git@)[\\w\\.-]+[/:]([^/]+/[^/]+)(?:\\.git)?"
        );
        Matcher matcher = pattern.matcher(url.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Error in Extract Git Name";
    }

    private static String extractProjectType(String description) {
        if (description == null) return "JAVA";
        String normalized = description.toLowerCase();
        if (normalized.contains("[python]") || normalized.contains("python repo")) return "PYTHON";
        return "JAVA";
    }
}
