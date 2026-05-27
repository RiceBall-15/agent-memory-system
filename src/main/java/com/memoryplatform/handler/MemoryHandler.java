package com.memoryplatform.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.*;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.storage.MetadataStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 记忆处理器 - 处理记忆的CRUD操作和对话文本提取
 * <p>
 * 提供以下API端点:
 * <ul>
 *   <li>{@code POST /api/memories} - 从对话文本中提取记忆</li>
 *   <li>{@code GET /api/memories/{id}} - 获取单条记忆</li>
 *   <li>{@code PUT /api/memories/{id}} - 更新记忆</li>
 *   <li>{@code DELETE /api/memories/{id}} - 删除记忆</li>
 *   <li>{@code GET /api/memories} - 列表查询</li>
 * </ul>
 * </p>
 *
 * <h3>依赖服务</h3>
 * <ul>
 *   <li>{@link MemoryExtractionService} - 对话文本记忆提取</li>
 *   <li>{@link ConcurrentWriteService} - 高并发异步写入</li>
 *   <li>{@link MetadataStore} - 元数据CRUD查询</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class MemoryHandler implements HttpHandler {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 默认查询限制 */
    private static final int DEFAULT_LIMIT = 20;

    /** 最大查询限制 */
    private static final int MAX_LIMIT = 100;

    /** 写入超时时间（秒） */
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    /** 记忆提取服务 */
    private final MemoryExtractionService extractionService;

    /** 高并发写入服务 */
    private final ConcurrentWriteService writeService;

    /** 元数据存储（用于CRUD查询） */
    private final MetadataStore metadataStore;

    /**
     * 构造记忆处理器
     *
     * @param extractionService 记忆提取服务
     * @param writeService      高并发写入服务
     * @param metadataStore     元数据存储
     */
    public MemoryHandler(MemoryExtractionService extractionService,
                         ConcurrentWriteService writeService,
                         MetadataStore metadataStore) {
        this.extractionService = extractionService;
        this.writeService = writeService;
        this.metadataStore = metadataStore;
        log("[MemoryHandler] 初始化完成");
    }

    /**
     * 处理HTTP请求，根据请求方法分发到对应处理逻辑
     *
     * @param exchange   HTTP交换对象
     * @param pathParams 路径参数映射（如 {id}）
     * @throws IOException 如果IO操作失败
     */
    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[MemoryHandler] 请求: " + method + " " + path);

        try {
            switch (method) {
                case "POST":
                    handleCreate(exchange);
                    break;
                case "GET":
                    if (pathParams.containsKey("id")) {
                        handleGetById(exchange, pathParams.get("id"));
                    } else {
                        handleList(exchange);
                    }
                    break;
                case "PUT":
                    if (pathParams.containsKey("id")) {
                        handleUpdate(exchange, pathParams.get("id"));
                    } else {
                        errorResponse(exchange, 400, "缺少记忆ID参数");
                    }
                    break;
                case "DELETE":
                    if (pathParams.containsKey("id")) {
                        handleDelete(exchange, pathParams.get("id"));
                    } else {
                        errorResponse(exchange, 400, "缺少记忆ID参数");
                    }
                    break;
                default:
                    errorResponse(exchange, 405, "不支持的HTTP方法: " + method);
            }
        } catch (Exception e) {
            logError("[MemoryHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories ====================

    /**
     * 创建记忆 - 从对话文本中提取记忆
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "messages": [{"role":"user","content":"..."}],
     *   "userId": "xxx",
     *   "agentId": "yyy"
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleCreate(HttpExchange exchange) throws IOException {
        log("[MemoryHandler] 创建记忆 - 开始");

        // 1. 读取并验证请求体
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

        // 2. 验证必需字段
        if (!requestJson.has("messages") || !requestJson.has("userId")) {
            errorResponse(exchange, 400, "缺少必需字段: messages, userId");
            return;
        }

        String userId = requestJson.get("userId").getAsString();
        String agentId = requestJson.has("agentId") ? requestJson.get("agentId").getAsString() : null;

        // 3. 解析消息列表
        List<Message> messages;
        try {
            messages = parseMessages(requestJson.getAsJsonArray("messages"));
        } catch (Exception e) {
            errorResponse(exchange, 400, "消息格式错误: " + e.getMessage());
            return;
        }

        if (messages.isEmpty()) {
            errorResponse(exchange, 400, "消息列表不能为空");
            return;
        }

        log("[MemoryHandler] 提取消息数=" + messages.size() + ", userId=" + userId);

        // 4. 调用记忆提取服务
        List<Memory> memories = extractionService.extractFromConversation(messages, userId, agentId);

        log("[MemoryHandler] 提取到 " + memories.size() + " 条记忆");

        // 5. 异步写入存储（通过ConcurrentWriteService）
        if (!memories.isEmpty() && writeService != null) {
            List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
            for (Memory memory : memories) {
                futures.add(writeService.write(memory));
            }

            // Fire-and-forget: 异步写入，不阻塞请求返回
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        if (ex != null) {
                            logError("[MemoryHandler] 异步写入失败: " + ex.getMessage());
                        } else {
                            log("[MemoryHandler] 所有记忆写入完成");
                        }
                    });
        }

        // 6. 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("memories", memories);
        responseData.put("count", memories.size());
        responseData.put("userId", userId);
        responseData.put("agentId", agentId);

        jsonResponse(exchange, 201, responseData);
        log("[MemoryHandler] 创建记忆完成, count=" + memories.size());
    }

    // ==================== GET /api/memories/{id} ====================

    /**
     * 获取单条记忆
     *
     * @param exchange HTTP交换对象
     * @param id       记忆ID
     * @throws IOException 如果IO操作失败
     */
    private void handleGetById(HttpExchange exchange, String id) throws IOException {
        log("[MemoryHandler] 获取记忆: id=" + id);

        if (id == null || id.isBlank()) {
            errorResponse(exchange, 400, "记忆ID不能为空");
            return;
        }

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        try {
            // 从元数据存储查询
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", id);
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (records.isEmpty()) {
                errorResponse(exchange, 404, "记忆不存在: " + id);
                return;
            }

            MetadataRecord record = records.get(0);

            // 构建Memory对象
            Map<String, Object> memoryData = new HashMap<>();
            memoryData.put("id", record.getId());
            memoryData.put("text", record.getContent());
            memoryData.put("userId", record.getUserId());
            memoryData.put("agentId", record.getAgentId());
            memoryData.put("importance", record.getImportance());
            memoryData.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
            memoryData.put("updatedAt", record.getUpdatedAt() != null ? record.getUpdatedAt().toString() : null);

            if (record.getData() != null) {
                memoryData.put("metadata", record.getData());
            }

            jsonResponse(exchange, 200, memoryData);
            log("[MemoryHandler] 获取记忆完成: id=" + id);

        } catch (Exception e) {
            logError("[MemoryHandler] 查询记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "查询记忆失败: " + e.getMessage());
        }
    }

    // ==================== PUT /api/memories/{id} ====================

    /**
     * 更新记忆
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "text": "更新后的文本",
     *   "importance": 0.8
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @param id       记忆ID
     * @throws IOException 如果IO操作失败
     */
    private void handleUpdate(HttpExchange exchange, String id) throws IOException {
        log("[MemoryHandler] 更新记忆: id=" + id);

        if (id == null || id.isBlank()) {
            errorResponse(exchange, 400, "记忆ID不能为空");
            return;
        }

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 1. 读取请求体
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

        // 2. 构建更新字段
        Map<String, Object> updates = new HashMap<>();
        if (requestJson.has("text")) {
            updates.put("content", requestJson.get("text").getAsString());
        }
        if (requestJson.has("importance")) {
            updates.put("importance", requestJson.get("importance").getAsDouble());
        }
        if (requestJson.has("userId")) {
            updates.put("userId", requestJson.get("userId").getAsString());
        }
        if (requestJson.has("agentId")) {
            updates.put("agentId", requestJson.get("agentId").getAsString());
        }
        updates.put("updatedAt", java.time.Instant.now().toString());

        if (updates.size() <= 1) { // 只有updatedAt
            errorResponse(exchange, 400, "没有可更新的字段");
            return;
        }

        // 3. 执行更新
        try {
            boolean success = metadataStore.update(METADATA_TABLE, id, updates);

            if (!success) {
                errorResponse(exchange, 404, "记忆不存在或更新失败: " + id);
                return;
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("id", id);
            responseData.put("updated", true);
            responseData.put("fields", updates.keySet());

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 更新记忆完成: id=" + id + ", fields=" + updates.keySet());

        } catch (Exception e) {
            logError("[MemoryHandler] 更新记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "更新记忆失败: " + e.getMessage());
        }
    }

    // ==================== DELETE /api/memories/{id} ====================

    /**
     * 删除记忆
     *
     * @param exchange HTTP交换对象
     * @param id       记忆ID
     * @throws IOException 如果IO操作失败
     */
    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        log("[MemoryHandler] 删除记忆: id=" + id);

        if (id == null || id.isBlank()) {
            errorResponse(exchange, 400, "记忆ID不能为空");
            return;
        }

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        try {
            // 1. 删除元数据
            boolean success = metadataStore.delete(METADATA_TABLE, List.of(id));

            if (!success) {
                errorResponse(exchange, 404, "记忆不存在: " + id);
                return;
            }

            // 2. 异步清理向量库和图库
            if (vectorStore != null) {
                vectorStore.delete(VECTOR_COLLECTION, List.of(id));
            }
            if (graphStore != null) {
                graphStore.deleteNode(MEMORY_LABEL, id);
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("id", id);
            responseData.put("deleted", true);

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 删除记忆完成: id=" + id);

        } catch (Exception e) {
            logError("[MemoryHandler] 删除记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "删除记忆失败: " + e.getMessage());
        }
    }

    // ==================== GET /api/memories ====================

    /**
     * 列表查询记忆
     * <p>
     * 查询参数:
     * <ul>
     *   <li>{@code userId} - 用户ID过滤</li>
     *   <li>{@code agentId} - Agent ID过滤</li>
     *   <li>{@code limit} - 返回数量（默认20，最大100）</li>
     *   <li>{@code offset} - 偏移量（默认0）</li>
     * </ul>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleList(HttpExchange exchange) throws IOException {
        log("[MemoryHandler] 列表查询记忆");

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 1. 解析查询参数
        Map<String, String> queryParams = getQueryParams(exchange);

        Map<String, Object> filters = new HashMap<>();
        if (queryParams.containsKey("userId") && !queryParams.get("userId").isBlank()) {
            filters.put("userId", queryParams.get("userId"));
        }
        if (queryParams.containsKey("agentId") && !queryParams.get("agentId").isBlank()) {
            filters.put("agentId", queryParams.get("agentId"));
        }

        int limit = parseLimit(queryParams.get("limit"));
        int offset = parseOffset(queryParams.get("offset"));

        log("[MemoryHandler] 查询参数: filters=" + filters + ", limit=" + limit + ", offset=" + offset);

        // 2. 查询元数据
        try {
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, limit, offset);
            long totalCount = metadataStore.count(METADATA_TABLE, filters);

            // 3. 构建响应
            List<Map<String, Object>> memories = new ArrayList<>();
            for (MetadataRecord record : records) {
                Map<String, Object> memoryData = new HashMap<>();
                memoryData.put("id", record.getId());
                memoryData.put("text", record.getContent());
                memoryData.put("userId", record.getUserId());
                memoryData.put("agentId", record.getAgentId());
                memoryData.put("importance", record.getImportance());
                memoryData.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
                memoryData.put("updatedAt", record.getUpdatedAt() != null ? record.getUpdatedAt().toString() : null);
                memories.add(memoryData);
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("memories", memories);
            responseData.put("total", totalCount);
            responseData.put("limit", limit);
            responseData.put("offset", offset);

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 列表查询完成, total=" + totalCount + ", returned=" + memories.size());

        } catch (Exception e) {
            logError("[MemoryHandler] 列表查询失败: " + e.getMessage());
            errorResponse(exchange, 500, "查询记忆列表失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 解析消息列表JSON数组
     *
     * @param messagesJson JSON数组
     * @return Message对象列表
     */
    private List<Message> parseMessages(JsonArray messagesJson) {
        List<Message> messages = new ArrayList<>();
        for (JsonElement element : messagesJson) {
            JsonObject msgObj = element.getAsJsonObject();
            String role = msgObj.has("role") ? msgObj.get("role").getAsString() : "user";
            String content = msgObj.has("content") ? msgObj.get("content").getAsString() : "";
            if (!content.isBlank()) {
                messages.add(new Message(role, content));
            }
        }
        return messages;
    }

    /**
     * 解析limit参数
     *
     * @param value 参数值
     * @return 解析后的limit值
     */
    private int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            return Math.max(1, Math.min(limit, MAX_LIMIT));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    /**
     * 解析offset参数
     *
     * @param value 参数值
     * @return 解析后的offset值
     */
    private int parseOffset(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
