package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;

public record AgentRunSnapshotResult(
        String runId,
        AgentRunRegistry.Status status,
        String mode,
        String request,
        AgentLanguage language,
        Integer featureId,
        Integer moduleId,
        String model,
        String failureMessage
) {
}
