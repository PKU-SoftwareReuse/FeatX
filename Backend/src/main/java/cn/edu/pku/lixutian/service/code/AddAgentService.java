package cn.edu.pku.lixutian.service.code;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AddAgentService extends JavaAgentPipelineSupport {
    public SseEmitter runPipeline(String runId, String model) {
        return runJavaPipeline(runId, model, true);
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
