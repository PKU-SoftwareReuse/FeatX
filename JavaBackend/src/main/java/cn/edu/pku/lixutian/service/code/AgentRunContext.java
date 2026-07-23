package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.dto.result.FocusGraphContextResult;

import java.util.List;

public record AgentRunContext(
        String runId,
        String mode,
        String newRequest,
        String oldRequest,
        String relatedCodes,
        String allFiles,
        AgentLanguage language,
        String sourceRoot,
        String projectRoot,
        Integer repositoryId,
        Integer featureId,
        Integer moduleId,
        List<FocusGraphContextResult.GraphStage> graphStages
) {
    public AgentRunContext {
        graphStages = graphStages == null ? List.of() : List.copyOf(graphStages);
    }
}
