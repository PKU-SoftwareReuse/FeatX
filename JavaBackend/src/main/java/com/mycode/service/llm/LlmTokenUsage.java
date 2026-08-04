package com.mycode.service.llm;

/** Token usage reported by one upstream LLM request. */
public record LlmTokenUsage(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        long totalTokens
) {
    public LlmTokenUsage {
        if (inputTokens < 0
                || cachedInputTokens < 0
                || outputTokens < 0
                || reasoningOutputTokens < 0
                || totalTokens < 0) {
            throw new IllegalArgumentException("LLM token usage values must not be negative.");
        }
        cachedInputTokens = Math.min(cachedInputTokens, inputTokens);
        if (totalTokens == 0 && inputTokens + outputTokens > 0) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public long uncachedInputTokens() {
        return Math.max(0, inputTokens - cachedInputTokens);
    }
}
