package com.mycode.dto.result;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GitRepoPreviewResult {
    private String repoName;

    private String projectType;

    private List<String> paths;

    public GitRepoPreviewResult(String repoName, String projectType, List<String> paths) {
        this.repoName = repoName;
        this.projectType = projectType;
        this.paths = paths;
    }
}
