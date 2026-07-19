package cn.edu.pku.lixutian.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitCommitRequest {
    private String operation;
    private String message;
}
