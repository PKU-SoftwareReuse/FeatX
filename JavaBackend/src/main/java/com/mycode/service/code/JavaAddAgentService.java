package com.mycode.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class JavaAddAgentService extends ThreeStageAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, AgentRunMode.JAVA_ADD);
    }

    public String applyAgent3Result(
            String agent3Result,
            String filename,
            String originalContent,
            boolean createMode
    ) {
        return super.applyAgent3Result(agent3Result, filename, originalContent, createMode);
    }
}
