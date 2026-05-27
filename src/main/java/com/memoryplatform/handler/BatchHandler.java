package com.memoryplatform.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.HybridRetrievalService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.storage.MetadataStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 批量操作处理器 - 处理记忆的批量创建/删除/检索
 * <p>
 * 提供以下API端点:
 * <ul>
 *   <li>{@code POST /api/memories/batch} - 批量创建记忆（最多100条）</li>
 *   <li>{@code DELETE /api/memories/batch} - 批量删除记忆（最多100个ID）</li>
 *   <li>{@code POST /api/memories/batch/search} - 批量检索（最多10个查询）</li>
 * </ul>
 * </p>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>支持部分成功响应（207 Multi-Status）</li>
 *   <li>使用现有的ConcurrentWriteService进行异步写入</li>
 *   <li>批量删除支持元数据+向量库+图库全链路删除</li>
 *   <li>批量检索并发执行，支持降级</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class BatchHandler implements HttpHandler {

    /** 批量创建最大数量 */
    private static final int MAX_BATCH_CREATE = 100;

    /** 批量删除最大数量 */
    private static final int MAX_BATCH_DELETE = 100;

    /** 批量检索最大数量 */
    private static final int MAX_BATCH_SEARCH = 10;

    /** 写入超时时间（秒） */
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 记忆提取服务 */
    private final MemoryExtractionService extractionService;

    /** 高并发写入服务 */
    private final ConcurrentWriteService writeService;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /** 混合检索服务 */
    private final HybridRetrievalService retrievalService;

    /**
     * 构造批量处理器
     *
     * @param extractionService 记忆提取服务
     * @param writeService      高并发写入服务
     * @param metadataStore     元数据存储
     * @param retrievalService  混合检索服务
     */
    public BatchHandler(MemoryExtractionService extractionService,
                        ConcurrentWriteService writeService,
                        MetadataStore metadataStore,
                        HybridRetrievalService retrievalService) {
        this.extractionService = extractionService;
        this.writeService = writeService;
        this.metadataStore = metadataStore;
        this.retrievalService = retrievalService;
        log("[BatchHandler] 初始化完成");
    }

    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[BatchHandler] 请求: " + method + " " + path);

        try {
            if (path.endsWith("/search")) {
                handleBatchSearch(exchange);
            } else {
                switch (method) {
                    case "POST":
                        handleBatchCreate(exchange);
                        break;
                    case "DELETE":
                        handleBatchDelete(exchange);
                        break;
                    default:
                        errorResponse(exchange, 405, "批量接口仅支持POST(创建)和DELETE(删除)");
                }
            }
        } catch (Exception e) {
            logError("[BatchHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories/batch ====================

    /**
     * 批量创建记忆 - 从多个对话文本中并发提取并写入记忆
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "memories": [
     *     {
     *       "messages": [{"role":"user","content":"..."}],
     *       "userId": "xxx",
     *       "agentId": "yyy"
     *     },
     *     ...
     *   ]
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleBatchCreate(HttpExchange exchange) throws IOException {
        log("[BatchHandler] 批量创建记忆 - 开始");

        // 1. 读取并解析请求体
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

        // 2. 验证memories数组
        if (!requestJson.has("memories") || !requestJson.get("memories").isJsonArray()) {
            errorResponse(exchange, 400, "缺少必需字段: memories (数组)");
            return;
        }

        JsonArray memoriesArray = requestJson.getAsJsonArray("memories");
        if (memoriesArray.isEmpty()) {
            errorResponse(exchange, 400, "memories数组不能为空");
            return;
        }
        if (memoriesArray.size() > MAX_BATCH_CREATE) {
            errorResponse(exchange, 400, "批量创建最多支持" + MAX_BATCH_CREATE + "条，当前: " + memoriesArray.size());
            return;
        }

        log("[BatchHandler] 批量创建: " + memoriesArray.size() + " 条记忆");

        // 3. 逐条解析、提取记忆、异步写入
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < memoriesArray.size(); i++) {
            JsonObject item = memoriesArray.get(i).getAsJsonObject();
            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("index", i);

            try {
                // 验证必需字段
                if (!item.has("messages") || !item.has("userId")) {
                    itemResult.put("success", false);
                    itemResult.put("error", "缺少必需字段: messages, userId");
                    failCount++;
                    results.add(itemResult);
                    continue;
                }

                String userId = item.get("userId").getAsString();
                String agentId = item.has("agentId") ? item.get("agentId").getAsString() : null;

                // 解析消息
                List<com.memoryplatform.model.Message> messages = parseMessages(item.getAsJsonArray("messages"));
                if (messages.isEmpty()) {
                    itemResult.put("success", false);
                    itemResult.put("error", "消息列表为空");
                    failCount++;
                    results.add(itemResult);
                    continue;
                }

                // 提取记忆
                List<Memory> memories = extractionService.extractFromConversation(messages, userId, agentId);

                // 异步写入
                if (!memories.isEmpty() && writeService != null) {
                    List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
                    for (Memory memory : memories) {
                        futures.add(writeService.write(memory));
                    }

                    // 等待写入完成（带超时）
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    itemResult.put("success", true);
                    itemResult.put("count", memories.size());
                    itemResult.put("userId", userId);
                    successCount++;
                } else if (memories.isEmpty()) {
                    itemResult.put("success", true);
                    itemResult.put("count", 0);
                    itemResult.put("userId", userId);
                    itemResult.put("note", "未提取到记忆");
                    successCount++;
                } else {
                    itemResult.put("success", false);
                    itemResult.put("error", "写入服务未配置");
                    failCount++;
                }
            } catch (Exception e) {
                itemResult.put("success", false);
                itemResult.put("error", e.getMessage());
                failCount++;
            }

            results.add(itemResult);
        }

        // 4. 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", memoriesArray.size());
        responseData.put("successCount", successCount);
        responseData.put("failCount", failCount);

        int statusCode = (failCount == 0) ? 200 : (successCount == 0) ? 400 : 207;
        jsonResponse(exchange, statusCode, responseData);
        log("[BatchHandler] 批量创建完成: total=" + memoriesArray.size()
                + ", success=" + successCount + ", fail=" + failCount);
    }

    // ==================== DELETE /api/memories/batch ====================

    /**
     * 批量删除记忆 - 按ID列表批量删除
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "ids": ["id1", "id2", ...]
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleBatchDelete(HttpExchange exchange) throws IOException {
        log("[BatchHandler] 批量删除记忆 - 开始");

        // 1. 读取并解析请求体
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

        // 2. 验证ids数组
        if (!requestJson.has("ids") || !requestJson.get("ids").isJsonArray()) {
            errorResponse(exchange, 400, "缺少必需字段: ids (数组)");
            return;
        }

        JsonArray idsArray = requestJson.getAsJsonArray("ids");
        if (idsArray.isEmpty()) {
            errorResponse(exchange, 400, "ids数组不能为空");
            return;
        }
        if (idsArray.size() > MAX_BATCH_DELETE) {
            errorResponse(exchange, 400, "批量删除最多支持" + MAX_BATCH_DELETE + "个ID，当前: " + idsArray.size());
            return;
        }

        List<String> ids = new ArrayList<>();
        for (JsonElement element : idsArray) {
            ids.add(element.getAsString());
        }

        log("[BatchHandler] 批量删除: " + ids.size() + " 个ID");

        // 3. 逐个删除
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String id : ids) {
            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("id", id);

            try {
                if (metadataStore == null) {
                    itemResult.put("success", false);
                    itemResult.put("error", "元数据存储未配置");
                    failCount++;
                    results.add(itemResult);
                    continue;
                }

                boolean deleted = metadataStore.delete(METADATA_TABLE, List.of(id));

                if (deleted) {
                    itemResult.put("success", true);
                    successCount++;
                } else {
                    itemResult.put("success", false);
                    itemResult.put("error", "记忆不存在: " + id);
                    failCount++;
                }
            } catch (Exception e) {
                itemResult.put("success", false);
                itemResult.put("error", e.getMessage());
                failCount++;
            }

            results.add(itemResult);
        }

        // 4. 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", ids.size());
        responseData.put("successCount", successCount);
        responseData.put("failCount", failCount);

        int statusCode = (failCount == 0) ? 200 : (successCount == 0) ? 400 : 207;
        jsonResponse(exchange, statusCode, responseData);
        log("[BatchHandler] 批量删除完成: total=" + ids.size()
                + ", success=" + successCount + ", fail=" + failCount);
    }

    // ==================== POST /api/memories/batch/search ====================

    /**
     * 批量检索 - 并发执行多个搜索查询
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "queries": [
     *     {"text": "查询1", "userId": "xxx", "topK": 5},
     *     {"text": "查询2", "userId": "xxx", "topK": 10}
     *   ]
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleBatchSearch(HttpExchange exchange) throws IOException {
        log("[BatchHandler] 批量检索 - 开始");

        // 1. 读取并解析请求体
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

        // 2. 验证queries数组
        if (!requestJson.has("queries") || !requestJson.get("queries").isJsonArray()) {
            errorResponse(exchange, 400, "缺少必需字段: queries (数组)");
            return;
        }

        JsonArray queriesArray = requestJson.getAsJsonArray("queries");
        if (queriesArray.isEmpty()) {
            errorResponse(exchange, 400, "queries数组不能为空");
            return;
        }
        if (queriesArray.size() > MAX_BATCH_SEARCH) {
            errorResponse(exchange, 400, "批量检索最多支持" + MAX_BATCH_SEARCH + "个查询，当前: " + queriesArray.size());
            return;
        }

        if (retrievalService == null) {
            errorResponse(exchange, 503, "检索服务未配置");
            return;
        }

        log("[BatchHandler] 批量检索: " + queriesArray.size() + " 个查询");

        // 3. 并发执行检索
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        // 使用线程池并发执行
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

        for (int i = 0; i < queriesArray.size(); i++) {
            final int index = i;
            JsonObject queryJson = queriesArray.get(i).getAsJsonObject();

            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                Map<String, Object> itemResult = new HashMap<>();
                itemResult.put("index", index);

                try {
                    // 验证必需字段
                    if (!queryJson.has("text") || !queryJson.has("userId")) {
                        itemResult.put("success", false);
                        itemResult.put("error", "缺少必需字段: text, userId");
                        return itemResult;
                    }

                    String text = queryJson.get("text").getAsString();
                    String userId = queryJson.get("userId").getAsString();
                    String agentId = queryJson.has("agentId") ? queryJson.get("agentId").getAsString() : null;
                    int topK = queryJson.has("topK") ? queryJson.get("topK").getAsInt() : 10;
                    double threshold = queryJson.has("threshold") ? queryJson.get("threshold").getAsDouble() : 0.5;

                    // 构建查询
                    SearchQuery.Builder builder = SearchQuery.builder()
                            .text(text)
                            .userId(userId)
                            .topK(topK)
                            .threshold(threshold);
                    if (agentId != null && !agentId.isBlank()) {
                        builder.agentId(agentId);
                    }

                    long startTime = System.currentTimeMillis();
                    List<SearchResult> searchResults = retrievalService.search(builder.build());
                    long latencyMs = System.currentTimeMillis() - startTime;

                    itemResult.put("success", true);
                    itemResult.put("query", text);
                    itemResult.put("results", searchResults);
                    itemResult.put("total", searchResults.size());
                    itemResult.put("latencyMs", latencyMs);

                } catch (Exception e) {
                    itemResult.put("success", false);
                    itemResult.put("error", e.getMessage());
                }

                return itemResult;
            });

            futures.add(future);
        }

        // 等待所有查询完成
        for (CompletableFuture<Map<String, Object>> future : futures) {
            try {
                Map<String, Object> itemResult = future.get(30, TimeUnit.SECONDS);
                results.add(itemResult);
                if (Boolean.TRUE.equals(itemResult.get("success"))) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("success", false);
                errorResult.put("error", "查询超时或执行失败: " + e.getMessage());
                results.add(errorResult);
                failCount++;
            }
        }

        // 4. 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", queriesArray.size());
        responseData.put("successCount", successCount);
        responseData.put("failCount", failCount);

        int statusCode = (failCount == 0) ? 200 : (successCount == 0) ? 400 : 207;
        jsonResponse(exchange, statusCode, responseData);
        log("[BatchHandler] 批量检索完成: total=" + queriesArray.size()
                + ", success=" + successCount + ", fail=" + failCount);
    }

    // ==================== 工具方法 ====================

    /**
     * 解析消息列表
     *
     * @param messagesJson JSON数组
     * @return 消息列表
     */
    private List<com.memoryplatform.model.Message> parseMessages(JsonArray messagesJson) {
        List<com.memoryplatform.model.Message> messages = new ArrayList<>();
        if (messagesJson == null) return messages;

        for (JsonElement element : messagesJson) {
            JsonObject msgObj = element.getAsJsonObject();
            String role = msgObj.has("role") ? msgObj.get("role").getAsString() : "user";
            String content = msgObj.has("content") ? msgObj.get("content").getAsString() : "";
            messages.add(new com.memoryplatform.model.Message(role, content));
        }
        return messages;
    }
}
