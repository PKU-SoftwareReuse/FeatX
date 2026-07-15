package cn.edu.pku.lixutian.service.llm;

import cn.edu.pku.lixutian.config.LlmApiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class LlmClient {
    public static final String PREFERRED_DEFAULT_MODEL = "deepseek-v4-flash";

    private static final double TEMPERATURE = 0.0;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient MODEL_HTTP_CLIENT = HTTP_CLIENT.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private final String baseUrl;
    private final String apiKey;

    @Autowired
    public LlmClient(LlmApiConfig config) {
        this(config.getUrl(), config.getKey());
    }

    LlmClient(String baseUrl, String apiKey) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public record ModelCatalog(List<String> models, String defaultModel) {
    }

    @FunctionalInterface
    public interface ResponseCallback {
        void onResponse(String contentChunk);
    }

    public ModelCatalog getModelCatalog() throws IOException {
        Request request = authorizedRequest(endpoint("models"))
                .get()
                .build();

        try (Response response = MODEL_HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Model list request failed with status " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("Model list response body is empty");
            }

            JsonNode data = OBJECT_MAPPER.readTree(response.body().string()).path("data");
            Set<String> uniqueModels = new LinkedHashSet<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText("").trim();
                    if (!id.isEmpty()) {
                        uniqueModels.add(id);
                    }
                }
            }

            if (uniqueModels.isEmpty()) {
                throw new IOException("Model list response does not contain any model IDs");
            }

            List<String> models = List.copyOf(new ArrayList<>(uniqueModels));
            String defaultModel = models.contains(PREFERRED_DEFAULT_MODEL)
                    ? PREFERRED_DEFAULT_MODEL
                    : models.get(0);
            return new ModelCatalog(models, defaultModel);
        }
    }

    public String resolveModel(String requestedModel) throws IOException {
        ModelCatalog catalog = getModelCatalog();
        if (requestedModel == null || requestedModel.isBlank()) {
            return catalog.defaultModel();
        }

        String normalizedModel = requestedModel.trim();
        if (!catalog.models().contains(normalizedModel)) {
            throw new IllegalArgumentException("Unavailable model: " + normalizedModel);
        }
        return normalizedModel;
    }

    public String generateWithSinglePrompt(String prompt) {
        try {
            return generateWithSinglePrompt(prompt, resolveModel(null));
        } catch (IOException e) {
            return "调用失败：" + e.getMessage();
        }
    }

    public String generateWithSinglePrompt(String prompt, String model) {
        ArrayNode messages = createUserMessages(prompt);
        return generateWithMsg(messages, model);
    }

    private String generateWithMsg(ArrayNode messages, String model) {
        try {
            ObjectNode root = createRequestBody(messages, model, false);
            Request request = authorizedRequest(endpoint("chat/completions"))
                    .post(RequestBody.create(OBJECT_MAPPER.writeValueAsString(root), JSON))
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("OpenAI request failed with status " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("OpenAI response body is empty");
                }

                String responseBody = response.body().string();
                JsonNode choices = OBJECT_MAPPER.readTree(responseBody).path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    return choices.get(0).path("message").path("content").asText();
                }
                throw new IOException("OpenAI response does not contain choices");
            }
        } catch (Exception e) {
            return "调用失败：" + e.getMessage();
        }
    }

    public String streamGenerateWithPrompt(String prompt, SseEmitter emitter) throws IOException {
        return streamGenerateWithPrompt(prompt, emitter, resolveModel(null));
    }

    public String streamGenerateWithPrompt(String prompt, SseEmitter emitter, String model) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuffer buffer = new StringBuffer();
        AtomicReference<Exception> failure = new AtomicReference<>();

        ResponseCallback onChunk = chunk -> {
            try {
                String encoded = Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8));
                emitter.send(SseEmitter.event().data(encoded));
                buffer.append(chunk);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        Consumer<String> onComplete = result -> latch.countDown();
        Consumer<Exception> onError = error -> {
            failure.set(error);
            latch.countDown();
        };

        streamGenerateWithMsg(createUserMessages(prompt), model, onChunk, onComplete, onError);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the LLM response", e);
        }

        if (failure.get() != null) {
            throw new IOException("LLM streaming request failed", failure.get());
        }
        return buffer.toString();
    }

    private void streamGenerateWithMsg(
            ArrayNode messages,
            String model,
            ResponseCallback onChunk,
            Consumer<String> onComplete,
            Consumer<Exception> onError
    ) {
        EXECUTOR.submit(() -> {
            try {
                ObjectNode root = createRequestBody(messages, model, true);
                Request request = authorizedRequest(endpoint("chat/completions"))
                        .post(RequestBody.create(OBJECT_MAPPER.writeValueAsString(root), JSON))
                        .build();

                Call call = HTTP_CLIENT.newCall(request);
                try (Response response = call.execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("OpenAI streaming request failed with status " + response.code());
                    }
                    if (response.body() == null) {
                        throw new IOException("OpenAI streaming response body is empty");
                    }

                    StringBuffer result = new StringBuffer();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) {
                                continue;
                            }

                            String jsonPart = line.substring(6);
                            if ("[DONE]".equals(jsonPart)) {
                                break;
                            }

                            String contentChunk = extractContentFromChunk(jsonPart);
                            if (contentChunk != null && !contentChunk.isEmpty()) {
                                onChunk.onResponse(contentChunk);
                                result.append(contentChunk);
                            }
                        }
                    }
                    onComplete.accept(result.toString());
                }
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    private ArrayNode createUserMessages(String prompt) {
        ArrayNode messages = OBJECT_MAPPER.createArrayNode();
        ObjectNode userMessage = OBJECT_MAPPER.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        return messages;
    }

    private ObjectNode createRequestBody(ArrayNode messages, String model, boolean stream) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model must not be blank");
        }

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", TEMPERATURE);
        root.put("stream", stream);
        root.set("messages", messages);
        return root;
    }

    private Request.Builder authorizedRequest(String url) {
        Request.Builder request = new Request.Builder().url(url);
        if (!apiKey.isEmpty()) {
            request.addHeader("Authorization", "Bearer " + apiKey);
        }
        return request;
    }

    private String endpoint(String path) {
        return baseUrl + "/" + path;
    }

    static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("LLM API base URL must not be blank");
        }

        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String lowerCaseUrl = normalized.toLowerCase();
        if (lowerCaseUrl.endsWith("/chat/completion")
                || lowerCaseUrl.endsWith("/chat/completions")) {
            throw new IllegalArgumentException(
                    "LLM API URL must be a base URL without /chat/completions"
            );
        }
        return normalized;
    }

    private static String extractContentFromChunk(String jsonChunk) {
        try {
            JsonNode choices = OBJECT_MAPPER.readTree(jsonChunk).path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }

            JsonNode content = choices.get(0).path("delta").path("content");
            if (content.isMissingNode() || content.isNull()) {
                return null;
            }
            return content.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
