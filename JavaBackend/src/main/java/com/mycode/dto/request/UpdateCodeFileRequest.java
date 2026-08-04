package com.mycode.dto.request;

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
