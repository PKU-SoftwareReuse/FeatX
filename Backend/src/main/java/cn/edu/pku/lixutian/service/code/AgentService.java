package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Service
public class AgentService {
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DELETE_FILE_SENTINEL = "__FEATX_DELETE_FILE__";

    @Autowired
    protected LlmClient llmClient;

    @Autowired
    protected AgentRunRegistry agentRunRegistry;

    @Autowired
    @Qualifier("agentPipelineExecutor")
    protected ExecutorService agentPipelineExecutor;

    protected void sendStatus(AgentEventSink eventSink, String content) throws IOException {
        sendEvent(eventSink, "status", content);
    }

    protected void sendCompleted(AgentEventSink eventSink, String content) throws IOException {
        sendEvent(eventSink, "completed", content);
    }

    protected void sendFailed(AgentEventSink eventSink, String content) throws IOException {
        sendEvent(eventSink, "failed", content);
    }

    protected void sendEvent(AgentEventSink eventSink, String eventName, String content) throws IOException {
        eventSink.send(eventName, content);
    }

    protected String boundedPromptSection(String content, int maxCharacters) {
        if (content == null) {
            return "";
        }
        if (maxCharacters == 0 || content.length() <= maxCharacters) {
            return content;
        }
        if (maxCharacters < 0) {
            throw new IllegalArgumentException("Prompt section limit cannot be negative.");
        }
        int head = maxCharacters * 2 / 3;
        int tail = maxCharacters - head;
        return content.substring(0, head)
                + "\n\n... [context truncated by FeatX] ...\n\n"
                + content.substring(content.length() - tail);
    }
}
