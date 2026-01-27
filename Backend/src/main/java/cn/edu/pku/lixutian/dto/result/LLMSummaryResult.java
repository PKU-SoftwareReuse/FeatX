package cn.edu.pku.lixutian.dto.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LLMSummaryResult {
    private String title;
    private String description;

    public LLMSummaryResult(String title, String description) {
        this.title = title;
        this.description = description;
    }
}
