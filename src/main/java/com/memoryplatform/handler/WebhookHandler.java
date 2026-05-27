package com.memoryplatform.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.webhook.WebhookConfig;
import com.memoryplatform.webhook.WebhookEvent;
import com.memoryplatform.webhook.WebhookService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook处理器 - 管理Webhook配置和事件
 * <p>
 * 提供以下API端点:
 * <ul>
 *   <li>{@code POST /webhooks} - 创建Webhook配置</li>
 *   <li>{@code GET /webhooks} - 获取所有Webhook配置</li>
 *   <li>{@code PUT /webhooks/{id}} - 更新Webhook配置</li>
 *   <li>{@code DELETE /webhooks/{id}} - 删除Webhook配置</li>
 *   <li>{@code POST /webhooks/{id}/test} - 测试Webhook连接</li>
 *   <li>{@code GET /webhooks/{id}/events} - 获取最近事件</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebhookHandler implements HttpHandler {

    /** Webhook服务 */
    private final WebhookService webhookService;

    /**
     * 构造Webhook处理器
     *
     * @param webhookService Webhook服务
     */
    public WebhookHandler(WebhookService webhookService) {
        this.webhookService = webhookService;
        log("[WebhookHandler] 初始化完成");
    }

    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[WebhookHandler] 请求: " + method + " " + path);

        try {
            switch (method) {
                case "POST":
                    if (pathParams.containsKey("id") && path.endsWith("/test")) {
                        handleTest(exchange, pathParams.get("id"));
                    } else if (!pathParams.containsKey("id")) {
                        handleCreate(exchange);
                    } else {
                        errorResponse(exchange, 405, "不支持的操作");
                    }
                    break;
                case "GET":
                    if (pathParams.containsKey("id") && path.endsWith("/events")) {
                        handleGetEvents(exchange, pathParams.get("id"));
                    } else if (pathParams.containsKey("id")) {
                        handleGetById(exchange, pathParams.get("id"));
                    } else {
                        handleList(exchange);
                    }
                    break;
                case "PUT":
                    if (pathParams.containsKey("id")) {
                        handleUpdate(exchange, pathParams.get("id"));
                    } else {
                        errorResponse(exchange, 400, "缺少Webhook ID参数");
                    }
                    break;
                case "DELETE":
                    if (pathParams.containsKey("id")) {
                        handleDelete(exchange, pathParams.get("id"));
                    } else {
                        errorResponse(exchange, 400, "缺少Webhook ID参数");
                    }
                    break;
                default:
                    errorResponse(exchange, 405, "不支持的HTTP方法: " + method);
            }
        } catch (Exception e) {
            logError("[WebhookHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== POST /webhooks ====================

    /**
     * 创建Webhook配置
     * <p>
     * 请求体:
     * <pre>{@code
     * {
     *   "name": "My Webhook",
     *   "url": "https://example.com/webhook",
     *   "secret": "my-secret-key",
     *   "events": ["MEMORY_CREATED", "MEMORY_UPDATED"],
     *   "enabled": true,
     *   "retryCount": 3,
     *   "timeout": 5000,
     *   "description": "通知我的应用"
     * }
     * }</pre>
     */
    private void handleCreate(HttpExchange exchange) throws IOException {
        log("[WebhookHandler] 创建Webhook配置 - 开始");

        String body = readBody(exchange);
        if (body == null || body.isBlank()) {
            errorResponse(exchange, 400, "请求体不能为空");
            return;
        }

        JsonObject requestJson;
        try {
            requestJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            errorResponse(exchange, 400, "无效的JSON格式: " + e.getMessage());
            return;
        }

        // 验证必需字段
        if (!requestJson.has("url") || requestJson.get("url").getAsString().isBlank()) {
            errorResponse(exchange, 400, "缺少必需字段: url");
            return;
        }

        try {
            WebhookConfig.Builder builder = WebhookConfig.builder()
                    .url(requestJson.get("url").getAsString());

            if (requestJson.has("name")) {
                builder.name(requestJson.get("name").getAsString());
            }
            if (requestJson.has("secret")) {
                builder.secret(requestJson.get("secret").getAsString());
            }
            if (requestJson.has("enabled")) {
                builder.enabled(requestJson.get("enabled").getAsBoolean());
            }
            if (requestJson.has("retryCount")) {
                builder.retryCount(requestJson.get("retryCount").getAsInt());
            }
            if (requestJson.has("timeout")) {
                builder.timeout(requestJson.get("timeout").getAsInt());
            }
            if (requestJson.has("description")) {
                builder.description(requestJson.get("description").getAsString());
            }
            if (requestJson.has("events")) {
                List<String> events = new ArrayList<>();
                for (JsonElement elem : requestJson.getAsJsonArray("events")) {
                    events.add(elem.getAsString());
                }
                builder.events(events);
            }

            WebhookConfig config = builder.build();
            WebhookConfig created = webhookService.createConfig(config);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("data", created.toMap());
            jsonResponse(exchange, 201, responseData);
            log("[WebhookHandler] 创建Webhook配置完成: id=" + created.getId());

        } catch (IllegalStateException e) {
            errorResponse(exchange, 400, "配置验证失败: " + e.getMessage());
        } catch (Exception e) {
            logError("[WebhookHandler] 创建Webhook配置失败: " + e.getMessage());
            errorResponse(exchange, 500, "创建Webhook配置失败: " + e.getMessage());
        }
    }

    // ==================== GET /webhooks ====================

    /**
     * 获取所有Webhook配置
     */
    private void handleList(HttpExchange exchange) throws IOException {
        log("[WebhookHandler] 获取所有Webhook配置");

        List<WebhookConfig> configs = webhookService.getAllConfigs();
        List<Map<String, Object>> configMaps = new ArrayList<>();
        for (WebhookConfig config : configs) {
            configMaps.add(config.toMap());
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("data", configMaps);
        responseData.put("total", configMaps.size());
        jsonResponse(exchange, 200, responseData);
        log("[WebhookHandler] 获取Webhook配置完成, count=" + configMaps.size());
    }

    // ==================== GET /webhooks/{id} ====================

    /**
     * 获取单个Webhook配置
     */
    private void handleGetById(HttpExchange exchange, String id) throws IOException {
        log("[WebhookHandler] 获取Webhook配置: id=" + id);

        WebhookConfig config = webhookService.getConfig(id);
        if (config == null) {
            errorResponse(exchange, 404, "Webhook配置不存在: " + id);
            return;
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("data", config.toMap());
        jsonResponse(exchange, 200, responseData);
    }

    // ==================== PUT /webhooks/{id} ====================

    /**
     * 更新Webhook配置
     */
    private void handleUpdate(HttpExchange exchange, String id) throws IOException {
        log("[WebhookHandler] 更新Webhook配置: id=" + id);

        WebhookConfig existing = webhookService.getConfig(id);
        if (existing == null) {
            errorResponse(exchange, 404, "Webhook配置不存在: " + id);
            return;
        }

        String body = readBody(exchange);
        if (body == null || body.isBlank()) {
            errorResponse(exchange, 400, "请求体不能为空");
            return;
        }

        JsonObject requestJson;
        try {
            requestJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            errorResponse(exchange, 400, "无效的JSON格式: " + e.getMessage());
            return;
        }

        try {
            // 基于已有配置创建Builder
            WebhookConfig.Builder builder = WebhookConfig.builder(existing);

            if (requestJson.has("name")) {
                builder.name(requestJson.get("name").getAsString());
            }
            if (requestJson.has("url")) {
                builder.url(requestJson.get("url").getAsString());
            }
            if (requestJson.has("secret")) {
                builder.secret(requestJson.get("secret").getAsString());
            }
            if (requestJson.has("enabled")) {
                builder.enabled(requestJson.get("enabled").getAsBoolean());
            }
            if (requestJson.has("retryCount")) {
                builder.retryCount(requestJson.get("retryCount").getAsInt());
            }
            if (requestJson.has("timeout")) {
                builder.timeout(requestJson.get("timeout").getAsInt());
            }
            if (requestJson.has("description")) {
                builder.description(requestJson.get("description").getAsString());
            }
            if (requestJson.has("events")) {
                List<String> events = new ArrayList<>();
                for (JsonElement elem : requestJson.getAsJsonArray("events")) {
                    events.add(elem.getAsString());
                }
                builder.events(events);
            }

            WebhookConfig updated = webhookService.updateConfig(id, builder.build());
            if (updated == null) {
                errorResponse(exchange, 404, "Webhook配置不存在: " + id);
                return;
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("data", updated.toMap());
            jsonResponse(exchange, 200, responseData);
            log("[WebhookHandler] 更新Webhook配置完成: id=" + id);

        } catch (IllegalStateException e) {
            errorResponse(exchange, 400, "配置验证失败: " + e.getMessage());
        } catch (Exception e) {
            logError("[WebhookHandler] 更新Webhook配置失败: " + e.getMessage());
            errorResponse(exchange, 500, "更新Webhook配置失败: " + e.getMessage());
        }
    }

    // ==================== DELETE /webhooks/{id} ====================

    /**
     * 删除Webhook配置
     */
    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        log("[WebhookHandler] 删除Webhook配置: id=" + id);

        boolean deleted = webhookService.deleteConfig(id);
        if (!deleted) {
            errorResponse(exchange, 404, "Webhook配置不存在: " + id);
            return;
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("message", "Webhook配置已删除");
        responseData.put("id", id);
        jsonResponse(exchange, 200, responseData);
        log("[WebhookHandler] 删除Webhook配置完成: id=" + id);
    }

    // ==================== POST /webhooks/{id}/test ====================

    /**
     * 测试Webhook连接
     */
    private void handleTest(HttpExchange exchange, String id) throws IOException {
        log("[WebhookHandler] 测试Webhook连接: id=" + id);

        WebhookService.TestResult result = webhookService.testWebhook(id);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("data", result.toMap());
        jsonResponse(exchange, 200, responseData);
        log("[WebhookHandler] 测试Webhook连接完成: success=" + result.isSuccess());
    }

    // ==================== GET /webhooks/{id}/events ====================

    /**
     * 获取指定Webhook的最近事件
     */
    private void handleGetEvents(HttpExchange exchange, String id) throws IOException {
        log("[WebhookHandler] 获取Webhook事件: id=" + id);

        WebhookConfig config = webhookService.getConfig(id);
        if (config == null) {
            errorResponse(exchange, 404, "Webhook配置不存在: " + id);
            return;
        }

        Map<String, String> queryParams = getQueryParams(exchange);
        int limit = 20;
        if (queryParams.containsKey("limit")) {
            try {
                limit = Math.max(1, Math.min(Integer.parseInt(queryParams.get("limit")), 100));
            } catch (NumberFormatException e) {
                // 使用默认值
            }
        }

        List<WebhookEvent> events = webhookService.getEventsForConfig(id, limit);
        List<Map<String, Object>> eventMaps = new ArrayList<>();
        for (WebhookEvent event : events) {
            Map<String, Object> eventMap = event.toMap();
            eventMap.put("status", event.getSendStatus().name());
            eventMap.put("attemptCount", event.getAttemptCount());
            eventMap.put("lastError", event.getLastError());
            eventMaps.add(eventMap);
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("data", eventMaps);
        responseData.put("total", eventMaps.size());
        responseData.put("webhookId", id);
        jsonResponse(exchange, 200, responseData);
    }
}
