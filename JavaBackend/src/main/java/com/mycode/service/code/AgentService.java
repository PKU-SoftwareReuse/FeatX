package com.mycode.service.code;

import com.mycode.dto.result.AgentTokenUsageResult;
import com.mycode.service.llm.LlmClient;
import com.mycode.service.llm.LlmGenerationResult;
import com.mycode.service.RepoSummaryHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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

    @Autowired(required = false)
    protected AgentRunLogService agentRunLogService;

    @Autowired
    @Qualifier("agentPipelineExecutor")
    protected ExecutorService agentPipelineExecutor;

    @Autowired
    protected RepoSummaryHttpClient repoSummaryHttpClient;

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

    protected String generateAgentResponse(
            AgentRunContext context,
            String stage,
            String prompt,
            AgentEventSink eventSink,
            String model
    ) throws IOException {
        int callNumber = agentRunRegistry.beginLlmCall(context.runId());
        Instant startedAt = Instant.now();
        if (agentRunLogService != null) {
            Path runDirectory = agentRunLogService.initializeRun(context, model);
            if (runDirectory != null) {
                agentRunRegistry.setAgentLogPath(context.runId(), runDirectory.toString());
            }
            agentRunLogService.startCall(
                    context.runId(),
                    callNumber,
                    stage,
                    model,
                    prompt,
                    startedAt
            );
        }

        String response = "";
        StringBuilder streamedResponse = new StringBuilder();
        AgentEventSink recordingSink = (eventName, content) -> {
            if ("delta".equals(eventName) && content != null) {
                streamedResponse.append(content);
            }
            eventSink.send(eventName, content);
        };
        LlmGenerationResult generation = null;
        Throwable failure = null;
        try {
            generation = llmClient.streamGenerateWithPromptResult(prompt, recordingSink, model);
            if (generation == null) {
                throw new IOException("LLM client returned no generation result.");
            }
            response = generation.content();
            agentRunRegistry.completeLlmCall(context.runId(), generation.usage());
            return response;
        } catch (IOException | RuntimeException exception) {
            response = streamedResponse.toString();
            failure = exception;
            throw exception;
        } finally {
            if (agentRunLogService != null) {
                AgentTokenUsageResult aggregateUsage;
                try {
                    aggregateUsage = agentRunRegistry.tokenUsage(context.runId());
                } catch (IllegalArgumentException | IllegalStateException clearedRun) {
                    aggregateUsage = AgentTokenUsageResult.empty();
                }
                agentRunLogService.finishCall(
                        context.runId(),
                        callNumber,
                        stage,
                        model,
                        startedAt,
                        response,
                        generation == null ? null : generation.usage(),
                        failure,
                        aggregateUsage
                );
            }
        }
    }
}
