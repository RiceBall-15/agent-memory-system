package com.memoryplatform.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.*;
import com.memoryplatform.websocket.WebSocketMessage;
import com.memoryplatform.websocket.WebSocketServer;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.service.MemoryDeduplicationService;
import com.memoryplatform.service.MemoryTtlService;
import com.memoryplatform.service.MemoryDecayService;
import com.memoryplatform.service.MemorySharingService;
import com.memoryplatform.service.MemoryCompressionService;
import com.memoryplatform.service.MemoryIndexService;
import com.memoryplatform.service.MemorySemanticService;
import com.memoryplatform.service.MemoryContextService;
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
    /** WebSocket服务器（可为null，用于事件广播） */
    private volatile WebSocketServer webSocketServer;
    /** 记忆去重服务 */
    private MemoryDeduplicationService deduplicationService;
    /** TTL过期服务 */
    private MemoryTtlService ttlService;
    /** 记忆衰减服务 */
    private MemoryDecayService decayService;
    /** 记忆共享服务 */
    private MemorySharingService sharingService;
    /** 记忆语义服务 */
    private MemorySemanticService semanticService;
    /** 记忆上下文服务 */
    private MemoryContextService contextService;
    /** 记忆压缩服务 */
    private MemoryCompressionService compressionService;
    /** 索引优化服务 */
    private MemoryIndexService indexService;

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
     * 设置WebSocket服务器（用于事件广播）
     *
     * @param webSocketServer WebSocket服务器实例
     */
    public void setWebSocketServer(WebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
        log("[MemoryHandler] WebSocket服务器已绑定");
    }

    /**
     * 设置去重服务（可选依赖）
     */
    public void setDeduplicationService(MemoryDeduplicationService deduplicationService) {
        this.deduplicationService = deduplicationService;
    }

    /**
     * 设置TTL服务（可选依赖）
     */
    public void setTtlService(MemoryTtlService ttlService) {
        this.ttlService = ttlService;
    }

    /**
     * 设置衰减服务（可选依赖）
     */
    public void setDecayService(MemoryDecayService decayService) {
        this.decayService = decayService;
    }

    /**
     * 设置共享服务（可选依赖）
     */
    public void setSharingService(MemorySharingService sharingService) {
        this.sharingService = sharingService;
    }

    /**
     * 设置语义服务（可选依赖）
     */
    public void setSemanticService(MemorySemanticService semanticService) {
        this.semanticService = semanticService;
    }

    /**
     * 设置上下文服务（可选依赖）
     */
    public void setContextService(MemoryContextService contextService) {
        this.contextService = contextService;
    }

    /**
     * 设置压缩服务（可选依赖）
     */
    public void setCompressionService(MemoryCompressionService compressionService) {
        this.compressionService = compressionService;
    }

    /**
     * 设置索引优化服务（可选依赖）
     */
    public void setIndexService(MemoryIndexService indexService) {
        this.indexService = indexService;
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
                    if (path.endsWith("/context")) {
                        handleContext(exchange);
                    } else if (path.contains("/share")) {
                        handleShare(exchange, pathParams.get("id"));
                    } else if (path.endsWith("/compress")) {
                        handleCompress(exchange);
                    } else if (path.endsWith("/reindex")) {
                        handleReindex(exchange);
                    } else {
                        handleCreate(exchange);
                    }
                    break;
                case "GET":
                    if (pathParams.containsKey("id")) {
                        handleGetById(exchange, pathParams.get("id"));
                    } else if (path.endsWith("/shared")) {
                        handleGetSharedMemories(exchange);
                    } else if (path.endsWith("/shared-by-me")) {
                        handleGetSharedByMe(exchange);
                    } else if (path.endsWith("/archived")) {
                        handleGetArchived(exchange);
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
                        if (path.contains("/share")) {
                            handleUnshare(exchange, pathParams.get("id"));
                        } else {
                            handleDelete(exchange, pathParams.get("id"));
                        }
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

        // 5. 去重检查 + TTL设置
        List<Memory> finalMemories = new ArrayList<>();
        for (Memory memory : memories) {
            // 5.1 去重检查
            if (deduplicationService != null) {
                String duplicateId = deduplicationService.checkAndMerge(memory);
                if (duplicateId != null) {
                    log("[MemoryHandler] 记忆去重: " + memory.getId() + " → " + duplicateId);
                    continue; // 跳过重复记忆
                }
            }

            // 5.2 自动设置TTL
            if (ttlService != null) {
                ttlService.autoSetTtl(memory.getId(), userId, agentId);
            }

            finalMemories.add(memory);
        }

        // 6. 异步写入存储（通过ConcurrentWriteService）
        if (!memories.isEmpty() && writeService != null) {
            List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
            for (Memory memory : finalMemories) {
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
        responseData.put("memories", finalMemories);
        responseData.put("count", finalMemories.size());
        responseData.put("userId", userId);
        responseData.put("agentId", agentId);
        if (deduplicationService != null) {
            responseData.put("deduplication", deduplicationService.getStats());
        }
        if (ttlService != null) {
            responseData.put("ttl", ttlService.getStats());
        }

        jsonResponse(exchange, 201, responseData);
        log("[MemoryHandler] 创建记忆完成, count=" + finalMemories.size());

        // 广播记忆创建事件
        if (webSocketServer != null && webSocketServer.isRunning()) {
            try {
                for (Memory memory : finalMemories) {
                    WebSocketMessage wsMsg = WebSocketMessage.memoryCreated(
                            memory.getId(), memory.getUserId(), memory.getAgentId());
                    webSocketServer.broadcast(wsMsg);
                }
            } catch (Exception e) {
                logError("[MemoryHandler] WebSocket广播失败: " + e.getMessage());
            }
        }
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

            // 应用衰减权重
            if (decayService != null) {
                double decayWeight = decayService.getDecayWeight(id);
                memoryData.put("decayWeight", decayWeight);
                // 重置访问时间（检索即访问）
                decayService.resetAccessTime(id);
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

        // 检查共享权限：读写共享才允许更新
        if (sharingService != null && sharingService.isSharedMemory(id)) {
            String agentId = requestJson.has("agentId") ? requestJson.get("agentId").getAsString() : null;
            if (agentId != null && !sharingService.checkPermission(id, agentId, "write")) {
                errorResponse(exchange, 403, "无权更新共享记忆: " + id);
                return;
            }
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

            // 广播记忆更新事件
            if (webSocketServer != null && webSocketServer.isRunning()) {
                try {
                    WebSocketMessage wsMsg = WebSocketMessage.memoryUpdated(id, updates.keySet());
                    webSocketServer.broadcast(wsMsg);
                } catch (Exception e) {
                    logError("[MemoryHandler] WebSocket广播失败: " + e.getMessage());
                }
            }

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

        // 检查共享权限：如果记忆已共享，只有源Agent可以删除
        if (sharingService != null && sharingService.isSharedMemory(id)) {
            String agentId = getQueryParams(exchange).get("agentId");
            if (agentId != null && !sharingService.checkPermission(id, agentId, "delete")) {
                errorResponse(exchange, 403, "无权删除共享记忆: " + id);
                return;
            }
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

            // 广播记忆删除事件
            if (webSocketServer != null && webSocketServer.isRunning()) {
                try {
                    WebSocketMessage wsMsg = WebSocketMessage.memoryDeleted(id);
                    webSocketServer.broadcast(wsMsg);
                } catch (Exception e) {
                    logError("[MemoryHandler] WebSocket广播失败: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            logError("[MemoryHandler] 删除记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "删除记忆失败: " + e.getMessage());
        }
    }

    // ==================== GET /api/memories ====================
    // ==================== 共享API ====================

    /**
     * 共享记忆 - POST /api/memories/{id}/share
     * <p>
     * 请求体:
     * <pre>{@code
     * {
     *   "targetAgentId": "agent_b",
     *   "mode": "READ_ONLY"
     * }
     * }</pre>
     *
     * @param exchange HTTP交换对象
     * @param memoryId 记忆ID
     * @throws IOException 如果IO操作失败
     */
    private void handleShare(HttpExchange exchange, String memoryId) throws IOException {
        log("[MemoryHandler] 共享记忆: memoryId=" + memoryId);

        if (sharingService == null) {
            errorResponse(exchange, 503, "共享服务未配置");
            return;
        }

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 读取请求体
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

        String targetAgentId = requestJson.has("targetAgentId") ? requestJson.get("targetAgentId").getAsString() : null;
        String mode = requestJson.has("mode") ? requestJson.get("mode").getAsString() : "READ_ONLY";
        String agentId = requestJson.has("agentId") ? requestJson.get("agentId").getAsString() : null;

        if (targetAgentId == null || targetAgentId.isBlank()) {
            errorResponse(exchange, 400, "缺少必需字段: targetAgentId");
            return;
        }

        // 验证记忆存在
        Map<String, Object> metadata = metadataStore.get(METADATA_TABLE, memoryId);
        if (metadata == null) {
            errorResponse(exchange, 404, "记忆不存在: " + memoryId);
            return;
        }

        // 验证共享权限：只有记忆的所有者可以共享
        String memoryAgentId = (String) metadata.get("agentId");
        if (agentId != null && !agentId.equals(memoryAgentId)) {
            errorResponse(exchange, 403, "无权共享此记忆");
            return;
        }

        try {
            boolean success = sharingService.shareMemory(memoryId, targetAgentId, mode, memoryAgentId);
            if (success) {
                Map<String, Object> responseData = new HashMap<>();
                responseData.put("memoryId", memoryId);
                responseData.put("targetAgentId", targetAgentId);
                responseData.put("mode", mode);
                responseData.put("shared", true);

                jsonResponse(exchange, 200, responseData);
                log("[MemoryHandler] 共享记忆成功: memoryId=" + memoryId + ", targetAgentId=" + targetAgentId);
            } else {
                errorResponse(exchange, 500, "共享记忆失败");
            }
        } catch (Exception e) {
            logError("[MemoryHandler] 共享记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "共享记忆失败: " + e.getMessage());
        }
    }

    /**
     * 取消共享 - DELETE /api/memories/{id}/share?targetAgentId=xxx&agentId=yyy
     *
     * @param exchange HTTP交换对象
     * @param memoryId 记忆ID
     * @throws IOException 如果IO操作失败
     */
    private void handleUnshare(HttpExchange exchange, String memoryId) throws IOException {
        log("[MemoryHandler] 取消共享: memoryId=" + memoryId);

        if (sharingService == null) {
            errorResponse(exchange, 503, "共享服务未配置");
            return;
        }

        Map<String, String> queryParams = getQueryParams(exchange);
        String targetAgentId = queryParams.get("targetAgentId");
        String agentId = queryParams.get("agentId");

        if (targetAgentId == null || targetAgentId.isBlank()) {
            errorResponse(exchange, 400, "缺少必需参数: targetAgentId");
            return;
        }

        try {
            boolean success = sharingService.unshareMemory(memoryId, targetAgentId, agentId);
            if (success) {
                Map<String, Object> responseData = new HashMap<>();
                responseData.put("memoryId", memoryId);
                responseData.put("targetAgentId", targetAgentId);
                responseData.put("unshared", true);

                jsonResponse(exchange, 200, responseData);
                log("[MemoryHandler] 取消共享成功: memoryId=" + memoryId + ", targetAgentId=" + targetAgentId);
            } else {
                errorResponse(exchange, 404, "共享关系不存在或无权操作");
            }
        } catch (Exception e) {
            logError("[MemoryHandler] 取消共享失败: " + e.getMessage());
            errorResponse(exchange, 500, "取消共享失败: " + e.getMessage());
        }
    }

    /**
     * 获取共享给我的记忆 - GET /api/memories/shared?agentId=xxx
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleGetSharedMemories(HttpExchange exchange) throws IOException {
        log("[MemoryHandler] 获取共享记忆");

        if (sharingService == null) {
            errorResponse(exchange, 503, "共享服务未配置");
            return;
        }

        Map<String, String> queryParams = getQueryParams(exchange);
        String agentId = queryParams.get("agentId");

        if (agentId == null || agentId.isBlank()) {
            errorResponse(exchange, 400, "缺少必需参数: agentId");
            return;
        }

        try {
            List<Map<String, Object>> sharedMemories = sharingService.getSharedMemories(agentId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("memories", sharedMemories);
            responseData.put("total", sharedMemories.size());

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 获取共享记忆完成, agentId=" + agentId + ", count=" + sharedMemories.size());
        } catch (Exception e) {
            logError("[MemoryHandler] 获取共享记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "获取共享记忆失败: " + e.getMessage());
        }
    }

    /**
     * 获取我共享出去的记忆 - GET /api/memories/shared-by-me?agentId=xxx
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleGetSharedByMe(HttpExchange exchange) throws IOException {
        log("[MemoryHandler] 获取我共享的记忆");

        if (sharingService == null) {
            errorResponse(exchange, 503, "共享服务未配置");
            return;
        }

        Map<String, String> queryParams = getQueryParams(exchange);
        String agentId = queryParams.get("agentId");

        if (agentId == null || agentId.isBlank()) {
            errorResponse(exchange, 400, "缺少必需参数: agentId");
            return;
        }

        try {
            List<Map<String, Object>> sharedByMe = sharingService.getSharedByMe(agentId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("memories", sharedByMe);
            responseData.put("total", sharedByMe.size());

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 获取我共享的记忆完成, agentId=" + agentId + ", count=" + sharedByMe.size());
        } catch (Exception e) {
            logError("[MemoryHandler] 获取我共享的记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "获取我共享的记忆失败: " + e.getMessage());
        }
    }


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
        // 检查是否包含共享记忆
        boolean includeShared = "true".equalsIgnoreCase(queryParams.get("includeShared"));
        String agentIdForShared = queryParams.get("agentId");
        log("[MemoryHandler] 查询参数: filters=" + filters + ", limit=" + limit + ", offset=" + offset + ", includeShared=" + includeShared);
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

                // 添加衰减权重
                if (decayService != null) {
                    memoryData.put("decayWeight", decayService.getDecayWeight(record.getId()));
                }

                memories.add(memoryData);
            }
            // 如果启用共享记忆，合并共享的记忆
            if (includeShared && sharingService != null && agentIdForShared != null && !agentIdForShared.isBlank()) {
                try {
                    List<Map<String, Object>> sharedMemories = sharingService.getSharedMemories(agentIdForShared);
                    for (Map<String, Object> sharedMem : sharedMemories) {
                        Map<String, Object> memoryData = new HashMap<>();
                        memoryData.put("id", sharedMem.get("memoryId"));
                        memoryData.put("text", sharedMem.get("content"));
                        memoryData.put("userId", sharedMem.get("userId"));
                        memoryData.put("agentId", sharedMem.get("agentId"));
                        memoryData.put("sharedFrom", sharedMem.get("sharedByAgentId"));
                        memoryData.put("sharedMode", sharedMem.get("mode"));
                        memoryData.put("createdAt", sharedMem.get("sharedAt"));
                        memories.add(memoryData);
                    }
                } catch (Exception e) {
                    logError("[MemoryHandler] 获取共享记忆失败: " + e.getMessage());
                }
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("memories", memories);
            responseData.put("total", totalCount);
            responseData.put("limit", limit);
            responseData.put("offset", offset);

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 列表查询完成, total=" + totalCount + ", returned=" + memories.size());

            // 广播搜索事件
            if (webSocketServer != null && webSocketServer.isRunning()) {
                try {
                    WebSocketMessage wsMsg = WebSocketMessage.memorySearched(
                            filters.isEmpty() ? "*" : filters.toString(), memories.size());
                    webSocketServer.broadcast(wsMsg);
                } catch (Exception e) {
                    logError("[MemoryHandler] WebSocket广播失败: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            logError("[MemoryHandler] 列表查询失败: " + e.getMessage());
            errorResponse(exchange, 500, "查询记忆列表失败: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories/context ====================

    /**
     * 获取记忆上下文 - POST /api/memories/context
     * <p>
     * 请求体:
     * <pre>{@code
     * {
     *   "query": "当前对话内容",
     *   "user_id": "用户ID",
     *   "agent_id": "Agent ID",
     *   "window_size": 10
     * }
     * }</pre>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleContext(HttpExchange exchange) throws IOException {
        log("[MemoryHandler] 获取记忆上下文");

        if (contextService == null) {
            errorResponse(exchange, 503, "上下文服务未配置");
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

        // 2. 验证必需字段
        if (!requestJson.has("query") || !requestJson.has("user_id")) {
            errorResponse(exchange, 400, "缺少必需字段: query, user_id");
            return;
        }

        String query = requestJson.get("query").getAsString();
        String userId = requestJson.get("user_id").getAsString();
        String agentId = requestJson.has("agent_id") ? requestJson.get("agent_id").getAsString() : null;
        int windowSize = 10;
        if (requestJson.has("window_size")) {
            windowSize = requestJson.get("window_size").getAsInt();
        }
        windowSize = Math.max(1, Math.min(windowSize, 50)); // 限制范围

        log("[MemoryHandler] 上下文请求: query='" + query + "' userId=" + userId +
            " agentId=" + agentId + " windowSize=" + windowSize);

        // 3. 构建上下文
        try {
            com.memoryplatform.model.MemoryContext context =
                contextService.buildContext(query, userId, agentId, windowSize);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("context", context);
            responseData.put("memoryCount", context.getMemories() != null ? context.getMemories().size() : 0);
            responseData.put("totalRelevance", context.getTotalRelevance());

            jsonResponse(exchange, 200, responseData);
            log("[MemoryHandler] 上下文构建完成: " +
                (context.getMemories() != null ? context.getMemories().size() : 0) + " memories");
        } catch (Exception e) {
            logError("[MemoryHandler] 上下文构建失败: " + e.getMessage());
            errorResponse(exchange, 500, "上下文构建失败: " + e.getMessage());
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

    // ==================== POST /api/memories/compress ====================

    /**
     * 手动触发记忆压缩
     */
    private void handleCompress(HttpExchange exchange) throws IOException {
        if (compressionService == null) {
            errorResponse(exchange, 503, "压缩服务未初始化");
            return;
        }
        try {
            MemoryCompressionService.CompressResult result = compressionService.compressNow();
            JsonObject res = new JsonObject();
            res.addProperty("scanned", result.scanned);
            res.addProperty("merged", result.merged);
            res.addProperty("archived", result.archived);
            okResponse(exchange, res.toString());
        } catch (Exception e) {
            logError("[MemoryHandler] 压缩失败: " + e.getMessage());
            errorResponse(exchange, 500, "压缩失败: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories/reindex ====================

    /**
     * 手动触发索引重建
     */
    private void handleReindex(HttpExchange exchange) throws IOException {
        if (indexService == null) {
            errorResponse(exchange, 503, "索引优化服务未初始化");
            return;
        }
        try {
            MemoryIndexService.IndexStats result = indexService.rebuildIndex();
            JsonObject res = new JsonObject();
            res.addProperty("rebuiltVectorEntries", result.rebuiltVectorEntries);
            res.addProperty("rebuiltBm25Entries", result.rebuiltBm25Entries);
            res.addProperty("durationMs", result.durationMs);
            okResponse(exchange, res.toString());
        } catch (Exception e) {
            logError("[MemoryHandler] 索引重建失败: " + e.getMessage());
            errorResponse(exchange, 500, "索引重建失败: " + e.getMessage());
        }
    }

    // ==================== GET /api/memories/archived ====================

    /**
     * 获取归档记忆
     */
    private void handleGetArchived(HttpExchange exchange) throws IOException {
        String query = getQueryString(exchange);
        Map<String, String> params = parseQuery(query);
        String userId = params.get("userId");
        int limit = parseLimit(params.get("limit"));
        int offset = parseOffset(params.get("offset"));

        try {
            List<Map<String, Object>> allRecords = metadataStore.list(METADATA_TABLE, limit + offset + 100, 0);
            List<Map<String, Object>> archived = new ArrayList<>();
            for (Map<String, Object> record : allRecords) {
                String status = (String) record.get("status");
                if ("ARCHIVED".equals(status)) {
                    if (userId != null && !userId.isBlank()) {
                        Object uid = record.get("userId");
                        if (uid == null || !userId.equals(uid.toString())) {
                            continue;
                        }
                    }
                    archived.add(record);
                }
            }

            // 分页
            int end = Math.min(offset + limit, archived.size());
            List<Map<String, Object>> page = archived.subList(offset, end);

            JsonArray arr = new JsonArray();
            for (Map<String, Object> record : page) {
                arr.add(convertToJson(record));
            }

            JsonObject result = new JsonObject();
            result.add("memories", arr);
            result.addProperty("total", archived.size());
            result.addProperty("offset", offset);
            result.addProperty("limit", limit);

            okResponse(exchange, result.toString());
        } catch (Exception e) {
            logError("[MemoryHandler] 获取归档记忆失败: " + e.getMessage());
            errorResponse(exchange, 500, "获取归档记忆失败: " + e.getMessage());
        }
    }
}
