package com.mycode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AgentExecutionConfig {
    @Bean(name = "agentPipelineExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentPipelineExecutor() {
        int workers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "featx-agent-pipeline-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(workers, threads);
    }
}
