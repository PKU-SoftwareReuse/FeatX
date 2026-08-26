package com.mycode.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PythonDeleteAgentService extends ThreeStageAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, AgentRunMode.PYTHON_DELETE);
    }
}
