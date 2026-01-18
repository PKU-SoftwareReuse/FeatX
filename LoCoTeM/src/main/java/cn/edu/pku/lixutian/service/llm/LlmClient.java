package cn.edu.pku.lixutian.service.llm;

import cn.edu.pku.lixutian.config.LlmApiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class LlmClient {
    private static final String apiUrl = LlmApiConfig.getInstance().getUrl();
    private static final String apiKey = LlmApiConfig.getInstance().getKey();
    private static final String model = LlmApiConfig.getInstance().getModel();

    private static final double temp = 0.0;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // ⚠️ 关键：0 表示永不超时！
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @FunctionalInterface
    public interface ResponseCallback {
        void onResponse(String contentChunk);
    }

    public String generateWithSinglePrompt(String prompt) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        return generateWithMsg(messages);
    }

    private String generateWithMsg(ArrayNode messages) {
        try {
            // 构造 JSON 请求体
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("temperature", temp);
            root.put("stream", false);
            root.set("messages", messages);

            String requestBody = objectMapper.writeValueAsString(root);

            // 构造 HTTP 请求
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            // 同步执行
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("OpenAI请求失败，状态码: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode rootNode = objectMapper.readTree(responseBody);

                JsonNode choices = rootNode.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    return message.path("content").asText();
                } else {
                    throw new RuntimeException("响应结果中无choices内容: " + responseBody);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "调用失败：" + e.getMessage();
        }
    }

    public String streamGenerateWithPrompt(String prompt, SseEmitter emitter) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuffer buffer = new StringBuffer();

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        ResponseCallback onChunk = chunk -> {
            try {
                String encoded = Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8));
                emitter.send(SseEmitter.event().data(encoded));
                buffer.append(chunk);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        Consumer<String> onComplete = result -> {
            latch.countDown();
        };

        streamGenerateWithMsg(messages, onChunk, onComplete);

        try {
            latch.await(); // 等待推流结束
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return buffer.toString();
    }

    private SseEmitter streamGenerateWithMsg(ArrayNode messages, ResponseCallback onChunk, Consumer<String> onComplete) {
        SseEmitter emitter = new SseEmitter(0L); // 0表示无限超时
        StringBuffer stringBuffer = new StringBuffer();

        executor.submit(() -> {
            try {
                ObjectNode root = objectMapper.createObjectNode();
                root.put("model", model);
                root.put("temperature", temp);
                root.put("stream", true);
                root.set("messages", messages);

                // 转为 JSON 字符串
                String requestBody = objectMapper.writeValueAsString(root);

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                Call call = client.newCall(request);
                Response response = call.execute();

                if (!response.isSuccessful()) {
                    emitter.completeWithError(new RuntimeException("API请求失败: " + response.code()));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String jsonPart = line.substring(6);
                            if ("[DONE]".equals(jsonPart)) {
                                break;
                            }

                            // 提取 delta.content
                            String contentChunk = extractContentFromChunk(jsonPart);
                            if (contentChunk != null && !contentChunk.isEmpty()) {
                                onChunk.onResponse(contentChunk);
                                stringBuffer.append(contentChunk);

                                String encoded = Base64.getEncoder().encodeToString(contentChunk.getBytes(StandardCharsets.UTF_8));
                                emitter.send(SseEmitter.event().data(encoded));

                            }
                        }
                    }

                    onComplete.accept(stringBuffer.toString());
                    emitter.complete();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private static String extractContentFromChunk(String jsonChunk) {
        try {
            JsonNode root = objectMapper.readTree(jsonChunk);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }

            JsonNode delta = choices.get(0).path("delta");
            JsonNode content = delta.path("content");

            if (content.isMissingNode() || content.isNull()) {
                return null;
            }

            return content.asText();
        } catch (Exception e) {
            System.err.println("解析chunk失败: " + e.getMessage());
            return null;
        }
    }
}
