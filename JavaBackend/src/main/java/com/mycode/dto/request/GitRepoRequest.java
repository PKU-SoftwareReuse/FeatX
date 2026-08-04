package com.mycode.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitRepoRequest {
    private String gitRepoName;

    private String commitId;

    private String repoName;
}
