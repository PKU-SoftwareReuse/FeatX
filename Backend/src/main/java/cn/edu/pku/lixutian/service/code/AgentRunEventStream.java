package cn.edu.pku.lixutian.service.code;

import java.io.IOException;

/**
 * A durable-in-process event target for one Agent run. Browser disconnects do
 * not close this stream; they only detach an SSE subscriber from the registry.
 */
public final class AgentRunEventStream implements AgentEventSink {
    private final AgentRunRegistry registry;
    private final String runId;

    AgentRunEventStream(AgentRunRegistry registry, String runId) {
        this.registry = registry;
        this.runId = runId;
    }

    @Override
    public void send(String eventName, String content) throws IOException {
        try {
            registry.publish(runId, eventName, content);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Agent run is no longer active.", exception);
        }
    }
}
