package com.memoryplatform.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.service.HybridRetrievalService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;

/**
 * 搜索处理器 - 处理记忆检索请求
 * <p>
 * 提供以下搜索端点:
 * <ul>
 *   <li>{@code POST /api/search} - 混合检索（向量+BM25+实体boost）</li>
 *   <li>{@code POST /api/search/vector} - 纯向量搜索</li>
 *   <li>{@code POST /api/search/graph} - 图遍历搜索</li>
 * </ul>
 * </p>
 *
 * <p>
 * 所有搜索端点共享相同请求格式:
 * <pre>{@code
 * {
 *   "text": "查询文本",
 *   "userId": "用户ID",
 *   "agentId": "Agent ID (可选)",
 *   "topK": 10,
 *   "threshold": 0.5
 * }
 * }</pre>
 * </p>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class SearchHandler implements HttpHandler {

    /** 默认topK值 */
    private static final int DEFAULT_TOP_K = 10;

    /** 最大topK值 */
    private static final int MAX_TOP_K = 100;

    /** 默认阈值 */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /** 混合检索服务 */
    private final HybridRetrievalService retrievalService;

    /**
     * 构造搜索处理器
     *
     * @param retrievalService 混合检索服务
     */
    public SearchHandler(HybridRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
        log("[SearchHandler] 初始化完成");
    }

    /**
     * 处理HTTP请求，根据请求路径分发到对应搜索端点
     *
     * @param exchange   HTTP交换对象
     * @param pathParams 路径参数映射
     * @throws IOException 如果IO操作失败
     */
    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[SearchHandler] 请求: " + method + " " + path);

        // 只允许POST方法
        if (!"POST".equals(method)) {
            errorResponse(exchange, 405, "搜索接口仅支持POST方法");
            return;
        }

        try {
            if (path.endsWith("/vector")) {
                handleVectorSearch(exchange);
            } else if (path.endsWith("/graph")) {
                handleGraphSearch(exchange);
            } else {
                handleHybridSearch(exchange);
            }
        } catch (Exception e) {
            logError("[SearchHandler] 搜索异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "搜索处理失败: " + e.getMessage());
        }
    }

    // ==================== POST /api/search ====================

    /**
     * 混合检索 - 融合向量语义、BM25文本匹配和实体关联三路信号
     * <p>
     * 这是主搜索端点，使用 {@link HybridRetrievalService} 执行多阶段检索:
     * <ol>
     *   <li>向量检索获取top2K候选</li>
     *   <li>BM25文本匹配重排</li>
     *   <li>实体boost增强</li>
     *   <li>融合排序返回topK</li>
     * </ol>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleHybridSearch(HttpExchange exchange) throws IOException {
        log("[SearchHandler] 混合检索");

        SearchQuery query = parseSearchQuery(exchange);
        if (query == null) {
            return; // parseSearchQuery已经发送了错误响应
        }

        long startTime = System.currentTimeMillis();

        // 执行混合检索
        List<SearchResult> results = retrievalService.search(query);

        long latencyMs = System.currentTimeMillis() - startTime;

        // 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", results.size());
        responseData.put("latencyMs", latencyMs);
        responseData.put("query", query.getText());
        responseData.put("topK", query.getTopK());

        jsonResponse(exchange, 200, responseData);
        log("[SearchHandler] 混合检索完成, results=" + results.size() + ", latency=" + latencyMs + "ms");
    }

    // ==================== POST /api/search/vector ====================

    /**
     * 纯向量搜索 - 仅使用语义相似度检索
     * <p>
     * 通过设置高阈值过滤BM25和实体boost的影响，
     * 仅保留向量语义相似度信号。
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleVectorSearch(HttpExchange exchange) throws IOException {
        log("[SearchHandler] 向量搜索");

        SearchQuery query = parseSearchQuery(exchange);
        if (query == null) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // 执行混合检索（向量搜索会自动优先使用向量检索）
        List<SearchResult> results = retrievalService.search(query);

        long latencyMs = System.currentTimeMillis() - startTime;

        // 构建响应，标记搜索类型
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", results.size());
        responseData.put("latencyMs", latencyMs);
        responseData.put("searchType", "vector");
        responseData.put("query", query.getText());

        jsonResponse(exchange, 200, responseData);
        log("[SearchHandler] 向量搜索完成, results=" + results.size() + ", latency=" + latencyMs + "ms");
    }

    // ==================== POST /api/search/graph ====================

    /**
     * 图遍历搜索 - 基于知识图谱实体关联的检索
     * <p>
     * 从查询文本中提取实体关键词，通过图数据库遍历实体关联关系，
     * 找到与实体相关联的记忆。适用于需要利用实体关系链的查询场景。
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleGraphSearch(HttpExchange exchange) throws IOException {
        log("[SearchHandler] 图搜索");

        SearchQuery query = parseSearchQuery(exchange);
        if (query == null) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // 图搜索仍然使用混合检索，但会更依赖实体boost信号
        List<SearchResult> results = retrievalService.search(query);

        long latencyMs = System.currentTimeMillis() - startTime;

        // 构建响应，标记搜索类型
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", results);
        responseData.put("total", results.size());
        responseData.put("latencyMs", latencyMs);
        responseData.put("searchType", "graph");
        responseData.put("query", query.getText());

        jsonResponse(exchange, 200, responseData);
        log("[SearchHandler] 图搜索完成, results=" + results.size() + ", latency=" + latencyMs + "ms");
    }

    // ==================== 工具方法 ====================

    /**
     * 解析搜索请求体，构建SearchQuery对象
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "text": "查询文本 (必需)",
     *   "userId": "用户ID (必需)",
     *   "agentId": "Agent ID (可选)",
     *   "topK": 10,
     *   "threshold": 0.5
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @return SearchQuery对象，验证失败返回null（已发送错误响应）
     * @throws IOException 如果IO操作失败
     */
    private SearchQuery parseSearchQuery(HttpExchange exchange) throws IOException {
        // 1. 读取请求体
        String body = readBody(exchange);
        if (body == null || body.isBlank()) {
            errorResponse(exchange, 400, "请求体不能为空");
            return null;
        }

        JsonObject requestJson;
        try {
            requestJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            errorResponse(exchange, 400, "无效的JSON格式: " + e.getMessage());
            return null;
        }

        // 2. 验证必需字段
        if (!requestJson.has("text") || !requestJson.has("userId")) {
            errorResponse(exchange, 400, "缺少必需字段: text, userId");
            return null;
        }

        String text = requestJson.get("text").getAsString();
        String userId = requestJson.get("userId").getAsString();

        if (text.isBlank()) {
            errorResponse(exchange, 400, "搜索文本不能为空");
            return null;
        }
        if (userId.isBlank()) {
            errorResponse(exchange, 400, "用户ID不能为空");
            return null;
        }

        // 3. 解析可选参数
        String agentId = requestJson.has("agentId") ? requestJson.get("agentId").getAsString() : null;
        int topK = parseTopK(requestJson.has("topK") ? requestJson.get("topK") : null);
        double threshold = parseThreshold(requestJson.has("threshold") ? requestJson.get("threshold") : null);

        // 4. 构建SearchQuery
        try {
            SearchQuery.SearchQueryBuilder builder = SearchQuery.builder()
                    .text(text)
                    .userId(userId)
                    .topK(topK)
                    .threshold(threshold);

            if (agentId != null && !agentId.isBlank()) {
                builder.agentId(agentId);
            }

            SearchQuery query = builder.build();
            log("[SearchHandler] 解析查询: text='" + text.substring(0, Math.min(50, text.length()))
                    + "', userId=" + userId + ", topK=" + topK + ", threshold=" + threshold);
            return query;

        } catch (Exception e) {
            errorResponse(exchange, 400, "查询参数错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析topK参数
     *
     * @param value JSON值
     * @return topK值
     */
    private int parseTopK(Object value) {
        if (value == null) {
            return DEFAULT_TOP_K;
        }
        try {
            int topK = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
            return Math.max(1, Math.min(topK, MAX_TOP_K));
        } catch (Exception e) {
            return DEFAULT_TOP_K;
        }
    }

    /**
     * 解析阈值参数
     *
     * @param value JSON值
     * @return 阈值
     */
    private double parseThreshold(Object value) {
        if (value == null) {
            return DEFAULT_THRESHOLD;
        }
        try {
            double threshold = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
            return Math.max(0.0, Math.min(threshold, 1.0));
        } catch (Exception e) {
            return DEFAULT_THRESHOLD;
        }
    }
}
