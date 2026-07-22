package cn.edu.pku.lixutian.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PythonModifyAgentService extends ThreeStageAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, "Python", AgentOperation.MODIFY);
    }

    public SseEmitter runAddPipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, "Python", AgentOperation.ADD);
    }

    public SseEmitter runDeletePipeline(String runId, String model) {
        return runThreeStagePipeline(runId, model, "Python", AgentOperation.DELETE);
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
