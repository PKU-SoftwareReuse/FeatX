package cn.edu.pku.lixutian.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ModifyAgentService extends ThreeStageAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, "Java", AgentOperation.MODIFY);
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
