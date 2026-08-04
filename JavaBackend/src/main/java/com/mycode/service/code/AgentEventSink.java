package com.mycode.service.code;

import java.io.IOException;

/**
 * Receives decoded Agent events independently from any particular browser
 * connection. Implementations may buffer, replay, or forward these events.
 */
@FunctionalInterface
public interface AgentEventSink {
    void send(String eventName, String content) throws IOException;
}
