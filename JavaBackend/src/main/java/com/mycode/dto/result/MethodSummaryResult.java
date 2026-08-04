package com.mycode.dto.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MethodSummaryResult extends LLMSummaryResult {
    public MethodSummaryResult(String title, String description) {
        super(title, description);
    }
}
