package com.mycode.service.llm;

/** Full text and optional provider-reported usage for one LLM request. */
public record LlmGenerationResult(String content, LlmTokenUsage usage) {
    public LlmGenerationResult {
        content = content == null ? "" : content;
    }
}
