package com.mycode.service.code;

import com.mycode.dto.result.AgentTokenUsageResult;
import com.mycode.service.llm.LlmTokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists the exact prompt, raw response, and usage of every Agent model call. */
@Service
public class AgentRunLogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunLogService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final boolean enabled;
    private final Path rootDirectory;

    @Autowired
    public AgentRunLogService(
            @Value("${agent.logs.enabled:true}") boolean enabled,
            @Value("${agent.logs.directory:logs/agent-runs}") String directory
    ) {
        this(enabled, Path.of(directory));
    }

    AgentRunLogService(Path rootDirectory) {
        this(true, rootDirectory);
    }

    AgentRunLogService(boolean enabled, Path rootDirectory) {
        this.enabled = enabled;
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
    }

    public Path initializeRun(AgentRunContext context, String model) {
        Path runDirectory = runDirectory(context.runId());
        if (!enabled) {
            return null;
        }
        try {
            Files.createDirectories(runDirectory);
            Path runMetadata = runDirectory.resolve("run.json");
            if (Files.notExists(runMetadata)) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("runId", context.runId());
                metadata.put("mode", context.mode());
                metadata.put("repositoryId", context.repositoryId());
                metadata.put("featureId", context.featureId());
                metadata.put("moduleId", context.moduleId());
                metadata.put("language", context.language());
                metadata.put("model", model);
                metadata.put("request", context.newRequest());
                metadata.put("originalRequest", context.oldRequest());
                metadata.put("sourceRoot", context.sourceRoot());
                metadata.put("projectRoot", context.projectRoot());
                metadata.put("createdAt", Instant.now());
                writeJson(runMetadata, metadata);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not initialize Agent output log for run {}", context.runId(), exception);
        }
        return runDirectory;
    }

    public void startCall(
            String runId,
            int callNumber,
            String stage,
            String model,
            String prompt,
            Instant startedAt
    ) {
        if (!enabled) {
            return;
        }
        try {
            Path callDirectory = callDirectory(runId, callNumber, stage);
            Files.createDirectories(callDirectory);
            writeText(callDirectory.resolve("prompt.txt"), prompt);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", runId);
            metadata.put("callNumber", callNumber);
            metadata.put("stage", stage);
            metadata.put("model", model);
            metadata.put("status", "RUNNING");
            metadata.put("startedAt", startedAt);
            writeJson(callDirectory.resolve("metadata.json"), metadata);
        } catch (IOException exception) {
            LOGGER.warn("Could not write Agent prompt log for run {} call {}", runId, callNumber, exception);
        }
    }

    public void finishCall(
            String runId,
            int callNumber,
            String stage,
            String model,
            Instant startedAt,
            String response,
            LlmTokenUsage usage,
            Throwable failure,
            AgentTokenUsageResult aggregateUsage
    ) {
        if (!enabled) {
            return;
        }
        Instant finishedAt = Instant.now();
        try {
            Path callDirectory = callDirectory(runId, callNumber, stage);
            Files.createDirectories(callDirectory);
            writeText(callDirectory.resolve("response.txt"), response);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", runId);
            metadata.put("callNumber", callNumber);
            metadata.put("stage", stage);
            metadata.put("model", model);
            metadata.put("status", failure == null ? "COMPLETED" : "FAILED");
            metadata.put("startedAt", startedAt);
            metadata.put("finishedAt", finishedAt);
            metadata.put("durationMillis", Duration.between(startedAt, finishedAt).toMillis());
            metadata.put("usageReported", usage != null);
            metadata.put("usage", usage);
            metadata.put("error", failure == null ? null : failure.toString());
            writeJson(callDirectory.resolve("metadata.json"), metadata);
            writeJson(runDirectory(runId).resolve("token-usage.json"), aggregateUsage);
        } catch (IOException exception) {
            LOGGER.warn("Could not write Agent response log for run {} call {}", runId, callNumber, exception);
        }
    }

    private Path runDirectory(String runId) {
        return rootDirectory.resolve(sanitize(runId));
    }

    private Path callDirectory(String runId, int callNumber, String stage) {
        return runDirectory(runId).resolve(String.format("%03d-%s", callNumber, sanitize(stage)));
    }

    private String sanitize(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            return "unknown";
        }
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private void writeText(Path path, String content) throws IOException {
        Files.writeString(
                path,
                content == null ? "" : content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void writeJson(Path path, Object value) throws IOException {
        writeText(path, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
