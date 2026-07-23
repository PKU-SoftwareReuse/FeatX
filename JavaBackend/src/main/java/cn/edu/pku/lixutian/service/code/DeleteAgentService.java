package cn.edu.pku.lixutian.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class DeleteAgentService extends ThreeStageAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, "Java", AgentOperation.DELETE);
    }
}
