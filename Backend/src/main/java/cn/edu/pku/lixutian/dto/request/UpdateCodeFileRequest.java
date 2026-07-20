package cn.edu.pku.lixutian.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCodeFileRequest {
    private String key;
    private String operation;
    private String content;
    private String runId;
}
