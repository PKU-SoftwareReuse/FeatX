package com.mycode.dto.result;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Aggregated LLM usage for every model call made by one Agent run. */
public record AgentTokenUsageResult(
        int calls,
        int reportedCalls,
        long inputTokens,
        long cachedInputTokens,
        long uncachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        long totalTokens
) {
    public static AgentTokenUsageResult empty() {
        return new AgentTokenUsageResult(0, 0, 0, 0, 0, 0, 0, 0);
    }

    @JsonProperty("complete")
    public boolean complete() {
        return calls == reportedCalls;
    }
}
