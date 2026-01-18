package cn.edu.pku.lixutian.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddOrModifyRequest {
    private String featureDescription;
    private Integer moduleId;   // 新增
}