package com.mycode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

@Service
public class RepoSummaryHttpClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofMinutes(15))
            .writeTimeout(Duration.ofMinutes(5))
            .callTimeout(Duration.ofMinutes(20))
            .build();

    private final String baseUrl;

    public RepoSummaryHttpClient() {
        this(System.getenv().getOrDefault("REPOSUMMARY_HTTP_URL", "http://127.0.0.1:8091"));
    }

    RepoSummaryHttpClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public JsonNode postJson(String path, JsonNode payload, int timeoutSeconds) throws IOException {
        Request request = requestBuilder(path)
                .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(payload), JSON))
                .build();
        try (Response response = client(timeoutSeconds).newCall(request).execute()) {
            String body = responseBody(response).string();
            if (!response.isSuccessful()) {
                throw new IOException("RepoSummary HTTP " + response.code() + " for " + path + ": " + body);
            }
            return OBJECT_MAPPER.readTree(body);
        }
    }

    public JsonNode getJson(String path, int timeoutSeconds) throws IOException {
        Request request = requestBuilder(path).get().build();
        try (Response response = client(timeoutSeconds).newCall(request).execute()) {
            String body = responseBody(response).string();
            if (!response.isSuccessful()) {
                throw new IOException("RepoSummary HTTP " + response.code() + " for " + path + ": " + body);
            }
            return OBJECT_MAPPER.readTree(body);
        }
    }

    public JsonNode postStreaming(
            String path,
            JsonNode payload,
            Consumer<JsonNode> progressConsumer,
            int timeoutSeconds
    ) throws IOException {
        Request request = requestBuilder(path)
                .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(payload), JSON))
                .build();
        try (Response response = client(timeoutSeconds).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() == null ? "" : response.body().string();
                throw new IOException("RepoSummary HTTP " + response.code() + " for " + path + ": " + body);
            }
            BufferedSource source = responseBody(response).source();
            JsonNode result = null;
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = OBJECT_MAPPER.readTree(line);
                String type = event.path("type").asText();
                if ("progress".equals(type)) {
                    if (progressConsumer != null) {
                        progressConsumer.accept(event.path("progress"));
                    }
                } else if ("result".equals(type)) {
                    result = event.path("result");
                } else if ("error".equals(type)) {
                    throw new IOException("RepoSummary operation failed: " + event.path("error").asText());
                }
            }
            if (result == null || result.isMissingNode()) {
                throw new IOException("RepoSummary HTTP stream ended without a result for " + path);
            }
            return result;
        }
    }

    private Request.Builder requestBuilder(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return new Request.Builder()
                .url(baseUrl + normalizedPath)
                .header("Accept", "application/json, application/x-ndjson")
                .header("Connection", "close");
    }

    private OkHttpClient client(int timeoutSeconds) {
        int safeTimeout = Math.max(timeoutSeconds, 1);
        return BASE_CLIENT.newBuilder()
                .readTimeout(Duration.ofSeconds(safeTimeout))
                .writeTimeout(Duration.ofSeconds(safeTimeout))
                .callTimeout(Duration.ofSeconds(safeTimeout))
                .build();
    }

    private ResponseBody responseBody(Response response) throws IOException {
        return Objects.requireNonNull(response.body(), "RepoSummary HTTP response had no body");
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            normalized = "http://127.0.0.1:8091";
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
