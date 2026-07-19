package cn.edu.pku.lixutian.dto.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitCommitResult {
    private String commitHash;
    private String commitScope;
    private Integer featureId;
    private GitWorkspaceStatusResult status;
}
