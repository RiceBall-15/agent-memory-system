package com.memoryplatform.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.Message;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * LLM调用客户端 - 统一接口，支持Ollama和OpenAI兼容API
 * <p>
 * 功能:
 * <ul>
 *   <li>Ollama本地模型调用</li>
 *   <li>OpenAI兼容API调用(支持各厂商)</li>
 *   <li>同步/异步调用</li>
 *   <li>自动重试机制(最多3次)</li>
 *   <li>超时控制(连接10s, 读取60s)</li>
 * </ul>
 */
public class LlmClient {

    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final Executor asyncExecutor;
    private final LlmConfig config;

    /**
     * LLM客户端配置
     */
    public static class LlmConfig {
        private String provider;  // "ollama" or "openai"
        private String baseUrl;
        private String model;
        private String apiKey;
        private double temperature;
        private int maxTokens;

        public LlmConfig() {
            this.provider = "ollama";
            this.baseUrl = "http://localhost:11434";
            this.model = "qwen2.5:7b";
            this.apiKey = "";
            this.temperature = 0.3;
            this.maxTokens = 2048;
        }

        public LlmConfig provider(String provider) { this.provider = provider; return this; }
        public LlmConfig baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public LlmConfig model(String model) { this.model = model; return this; }
        public LlmConfig apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public LlmConfig temperature(double temperature) { this.temperature = temperature; return this; }
        public LlmConfig maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        public String getProvider() { return provider; }
        public String getBaseUrl() { return baseUrl; }
        public String getModel() { return model; }
        public String getApiKey() { return apiKey; }
        public double getTemperature() { return temperature; }
        public int getMaxTokens() { return maxTokens; }
    }

    /**
     * 创建Ollama客户端
     * @param baseUrl Ollama服务地址, 默认http://localhost:11434
     * @param model 模型名称
     * @return LlmClient实例
     */
    public static LlmClient createOllamaClient(String baseUrl, String model) {
        LlmConfig config = new LlmConfig()
                .provider("ollama")
                .baseUrl(baseUrl)
                .model(model);
        return new LlmClient(config);
    }

    /**
     * 创建OpenAI兼容客户端
     * @param baseUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @return LlmClient实例
     */
    public static LlmClient createOpenAiClient(String baseUrl, String apiKey, String model) {
        LlmConfig config = new LlmConfig()
                .provider("openai")
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model);
        return new LlmClient(config);
    }

    /**
     * 使用默认配置创建客户端(Ollama + qwen2.5:7b)
     * @return LlmClient实例
     */
    public static LlmClient createDefault() {
        return new LlmClient(new LlmConfig());
    }

    /**
     * 构造函数（使用虚拟线程执行器）
     * @param config LLM配置
     */
    public LlmClient(LlmConfig config) {
        this(config, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 构造函数（使用指定的执行器）
     * @param config LLM配置
     * @param asyncExecutor 异步任务执行器
     */
    public LlmClient(LlmConfig config, Executor asyncExecutor) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.asyncExecutor = asyncExecutor;
        System.out.println("[LlmClient] 初始化完成: provider=" + config.getProvider()
                + ", model=" + config.getModel() + ", baseUrl=" + config.getBaseUrl());
    }

    /**
     * 同步聊天调用 - 返回纯文本响应
     * @param messages 消息列表
     * @return LLM生成的文本
     * @throws LlmException 调用异常
     */
    public String chat(List<Message> messages) throws LlmException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                System.out.println("[LlmClient] 第" + attempt + "次调用, provider=" + config.getProvider());
                String requestBody = buildRequestBody(messages, false);
                String url = buildChatUrl();
                HttpRequest request = buildHttpRequest(url, requestBody);

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String errorMsg = "HTTP " + response.statusCode() + ": " + response.body();
                    if (attempt < MAX_RETRIES) {
                        System.out.println("[LlmClient] 请求失败, 准备重试: " + errorMsg);
                        Thread.sleep(1000L * attempt); // 指数退避
                        continue;
                    }
                    throw new LlmException("调用失败: " + errorMsg);
                }

                String content = extractContent(response.body());
                System.out.println("[LlmClient] 调用成功, 响应长度=" + content.length());
                return content;

            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("调用被中断", e);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    System.out.println("[LlmClient] 异常重试: " + e.getMessage());
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw new LlmException("调用异常: " + e.getMessage(), e);
            }
        }
        throw new LlmException("超过最大重试次数: " + MAX_RETRIES);
    }

    /**
     * 同步聊天调用 - 返回JSON对象
     * @param messages 消息列表
     * @return JSON响应对象
     * @throws LlmException 调用异常
     */
    public JsonObject chatJson(List<Message> messages) throws LlmException {
        String text = chat(messages);
        try {
            // 尝试从响应中提取JSON (可能包含markdown代码块)
            String jsonStr = extractJsonFromResponse(text);
            return JsonParser.parseString(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            throw new LlmException("JSON解析失败: " + e.getMessage() + "\n原始响应: " + text, e);
        }
    }

    /**
     * 异步聊天调用
     * @param messages 消息列表
     * @return CompletableFuture<String>
     */
    public CompletableFuture<String> chatAsync(List<Message> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return chat(messages);
            } catch (LlmException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    /**
     * 异步JSON聊天调用
     * @param messages 消息列表
     * @return CompletableFuture<JsonObject>
     */
    public CompletableFuture<JsonObject> chatJsonAsync(List<Message> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return chatJson(messages);
            } catch (LlmException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    /**
     * 获取客户端配置
     * @return 当前配置
     */
    public LlmConfig getConfig() { return config; }

    // ==================== 内部方法 ====================

    /**
     * 构建请求体
     */
    private String buildRequestBody(List<Message> messages, boolean stream) {
        if ("ollama".equals(config.getProvider())) {
            return buildOllamaRequestBody(messages, stream);
        } else {
            return buildOpenAiRequestBody(messages, stream);
        }
    }

    /**
     * 构建Ollama API请求体
     */
    private String buildOllamaRequestBody(List<Message> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("stream", stream);
        body.addProperty("options", "");

        JsonObject options = new JsonObject();
        options.addProperty("temperature", config.getTemperature());
        options.addProperty("num_predict", config.getMaxTokens());
        body.add("options", options);

        JsonArray messagesArr = new JsonArray();
        for (Message msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole());
            msgObj.addProperty("content", msg.getContent());
            messagesArr.add(msgObj);
        }
        body.add("messages", messagesArr);

        return GSON.toJson(body);
    }

    /**
     * 构建OpenAI兼容API请求体
     */
    private String buildOpenAiRequestBody(List<Message> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("stream", stream);
        body.addProperty("temperature", config.getTemperature());
        body.addProperty("max_tokens", config.getMaxTokens());

        JsonArray messagesArr = new JsonArray();
        for (Message msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole());
            msgObj.addProperty("content", msg.getContent());
            messagesArr.add(msgObj);
        }
        body.add("messages", messagesArr);

        return GSON.toJson(body);
    }

    /**
     * 构建API URL
     */
    private String buildChatUrl() {
        String base = config.getBaseUrl().replaceAll("/$", "");
        if ("ollama".equals(config.getProvider())) {
            return base + "/api/chat";
        } else {
            return base + "/v1/chat/completions";
        }
    }

    /**
     * 构建HTTP请求
     */
    private HttpRequest buildHttpRequest(String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(READ_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if ("openai".equals(config.getProvider()) && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.getApiKey());
        }

        return builder.build();
    }

    /**
     * 从API响应中提取文本内容
     */
    private String extractContent(String responseBody) throws LlmException {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if ("ollama".equals(config.getProvider())) {
                // Ollama: {"message":{"role":"assistant","content":"..."},...}
                JsonObject message = json.getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString();
                }
            } else {
                // OpenAI: {"choices":[{"message":{"content":"..."}}]}
                JsonArray choices = json.getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject message = choice.getAsJsonObject("message");
                    if (message != null && message.has("content")) {
                        return message.get("content").getAsString();
                    }
                }
            }

            throw new LlmException("无法从响应中提取内容: " + responseBody);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从LLM响应文本中提取JSON
     * 支持提取被markdown代码块包裹的JSON
     */
    private String extractJsonFromResponse(String text) {
        if (text == null) return "{}";

        // 尝试提取 ```json ... ``` 代码块中的内容
        String jsonPattern = "```(?:json)?\\s*\\n?(\\s*[\\s\\S]*?)\\s*\\n?```";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(jsonPattern).matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 尝试找到第一个 [ 或 { 开始的JSON
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') {
                start = i;
                break;
            }
        }
        if (start >= 0) {
            return text.substring(start).trim();
        }

        return text;
    }

    /**
     * LLM调用异常
     */
    public static class LlmException extends Exception {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
