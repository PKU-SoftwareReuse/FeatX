package com.mycode.dto.request;

import com.mycode.service.code.AgentLanguage;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddOrModifyRequest {
    private String featureDescription;
    private Integer moduleId;   // 新增
    private AgentLanguage language = AgentLanguage.EN;
    private Integer featureId;
}
