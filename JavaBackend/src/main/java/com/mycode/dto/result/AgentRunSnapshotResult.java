package com.mycode.dto.result;

import com.mycode.service.code.AgentLanguage;
import com.mycode.service.code.AgentRunRegistry;

public record AgentRunSnapshotResult(
        String runId,
        AgentRunRegistry.Status status,
        String mode,
        String request,
        AgentLanguage language,
        Integer featureId,
        Integer moduleId,
        String model,
        String failureMessage,
        AgentTokenUsageResult tokenUsage,
        String agentLogPath,
        boolean metadataOnlyEligible
) {
}
