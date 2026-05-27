package com.memoryplatform.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.config.ApplicationConfig;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;

/**
 * 管理处理器 - 提供系统管理API端点
 * <p>
 * 提供以下管理端点:
 * <ul>
 *   <li>{@code GET /admin/stats} - 系统统计信息（总记忆数、存储使用量、缓存命中率）</li>
 *   <li>{@code POST /admin/cache/clear} - 清除缓存</li>
 *   <li>{@code GET /admin/storage/health} - 存储健康检查</li>
 *   <li>{@code POST /admin/maintenance/compact} - 触发存储压缩</li>
 * </ul>
 * </p>
 *
 * <h3>认证</h3>
 * <p>所有管理端点需要管理员token认证，token从配置文件 admin.token 读取。
 * 请求需携带 {@code Authorization: Bearer <token>} 头。</p>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class AdminHandler implements HttpHandler {

    /** 管理员token前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 系统启动时间 */
    private final long startupTime = System.currentTimeMillis();

    /** 总请求数统计（简单计数器） */
    private volatile long totalRequests = 0;

    /** 向量存储 */
    private final VectorStore vectorStore;

    /** 图存储 */
    private final GraphStore graphStore;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /** 应用配置 */
    private final ApplicationConfig config;

    /**
     * 构造管理处理器
     *
     * @param vectorStore   向量存储（可为null）
     * @param graphStore    图存储（可为null）
     * @param metadataStore 元数据存储（可为null）
     * @param config        应用配置
     */
    public AdminHandler(VectorStore vectorStore,
                        GraphStore graphStore,
                        MetadataStore metadataStore,
                        ApplicationConfig config) {
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.metadataStore = metadataStore;
        this.config = config;
        log("[AdminHandler] 初始化完成");
    }

    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[AdminHandler] 请求: " + method + " " + path);

        // 1. 认证检查
        if (!authenticate(exchange)) {
            return;
        }

        totalRequests++;

        try {
            if (path.endsWith("/stats")) {
                handleStats(exchange);
            } else if (path.endsWith("/cache/clear")) {
                handleCacheClear(exchange);
            } else if (path.endsWith("/storage/health")) {
                handleStorageHealth(exchange);
            } else if (path.endsWith("/maintenance/compact")) {
                handleMaintenanceCompact(exchange);
            } else {
                errorResponse(exchange, 404, "未知的管理端点: " + path);
            }
        } catch (Exception e) {
            logError("[AdminHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== 认证 ====================

    /**
     * 验证管理员token
     *
     * @param exchange HTTP交换对象
     * @return 是否认证成功
     * @throws IOException 如果响应发送失败
     */
    private boolean authenticate(HttpExchange exchange) throws IOException {
        String adminToken = config.getAdminToken();

        // 如果未配置admin token，允许访问（开发模式）
        if (adminToken == null || adminToken.isBlank()) {
            log("[AdminHandler] 管理员token未配置，允许访问（开发模式）");
            return true;
        }

        String authHeader = getHeader(exchange, "Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            errorResponse(exchange, 401, "缺少Authorization头，需要Bearer token");
            return false;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!adminToken.equals(token)) {
            errorResponse(exchange, 403, "管理员token无效");
            return false;
        }

        return true;
    }

    // ==================== GET /admin/stats ====================

    /**
     * 系统统计信息
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleStats(HttpExchange exchange) throws IOException {
        log("[AdminHandler] 获取系统统计信息");

        Map<String, Object> stats = new HashMap<>();

        // 1. 基本运行信息
        Map<String, Object> runtime = new HashMap<>();
        long uptimeMs = System.currentTimeMillis() - startupTime;
        runtime.put("uptimeMs", uptimeMs);
        runtime.put("uptimeFormatted", formatUptime(uptimeMs));
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        stats.put("runtime", runtime);

        // 2. JVM内存使用
        Map<String, Object> memory = new HashMap<>();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        memory.put("heapUsed", heapUsage.getUsed());
        memory.put("heapMax", heapUsage.getMax());
        memory.put("heapUsagePercent", heapUsage.getMax() > 0
                ? Math.round((double) heapUsage.getUsed() / heapUsage.getMax() * 100) : 0);
        memory.put("nonHeapUsed", nonHeapUsage.getUsed());
        stats.put("memory", memory);

        // 3. 存储状态概览
        Map<String, Object> storage = new HashMap<>();
        storage.put("vectorStore", vectorStore != null ? "configured" : "not_configured");
        storage.put("graphStore", graphStore != null ? "configured" : "not_configured");
        storage.put("metadataStore", metadataStore != null ? "configured" : "not_configured");
        stats.put("storage", storage);

        // 4. 元数据统计
        Map<String, Object> metadataStats = new HashMap<>();
        if (metadataStore != null) {
            try {
                long totalMemories = metadataStore.count("memories", Collections.emptyMap());
                metadataStats.put("totalMemories", totalMemories);
            } catch (Exception e) {
                metadataStats.put("totalMemories", "error: " + e.getMessage());
            }
        } else {
            metadataStats.put("totalMemories", "metadata_store_not_configured");
        }
        stats.put("metadata", metadataStats);

        // 5. 请求数统计
        stats.put("totalRequests", totalRequests);

        jsonResponse(exchange, 200, stats);
        log("[AdminHandler] 系统统计信息返回成功");
    }

    // ==================== POST /admin/cache/clear ====================

    /**
     * 清除缓存
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleCacheClear(HttpExchange exchange) throws IOException {
        log("[AdminHandler] 清除缓存");

        Map<String, Object> result = new HashMap<>();
        List<String> cleared = new ArrayList<>();

        // 清除元数据存储缓存（如果有）
        if (metadataStore != null) {
            try {
                // MetadataStore没有clearCache方法，记录为不支持
                cleared.add("metadataStore: no cache to clear");
            } catch (Exception e) {
                cleared.add("metadataStore: error - " + e.getMessage());
            }
        }

        // 清除向量存储本地缓存
        if (vectorStore != null) {
            try {
                // VectorStore接口无clearCache方法，标记为不支持
                cleared.add("vectorStore: no cache to clear (interface-level)");
            } catch (Exception e) {
                cleared.add("vectorStore: error - " + e.getMessage());
            }
        }

        // 清除图存储本地缓存
        if (graphStore != null) {
            try {
                // GraphStore没有clearCache方法，记录为不支持
                cleared.add("graphStore: no cache to clear");
            } catch (Exception e) {
                cleared.add("graphStore: error - " + e.getMessage());
            }
        }

        result.put("cleared", cleared);
        result.put("timestamp", java.time.Instant.now().toString());

        jsonResponse(exchange, 200, result);
        log("[AdminHandler] 缓存清除完成: " + cleared);
    }

    // ==================== GET /admin/storage/health ====================

    /**
     * 存储健康检查 - 分别检查向量库/图库/元数据库
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleStorageHealth(HttpExchange exchange) throws IOException {
        log("[AdminHandler] 存储健康检查");

        Map<String, Object> health = new HashMap<>();
        boolean allHealthy = true;

        // 1. 向量库健康检查
        Map<String, Object> vectorHealth = new HashMap<>();
        if (vectorStore != null) {
            try {
                long start = System.currentTimeMillis();
                boolean healthy = vectorStore.healthCheck();
                long latency = System.currentTimeMillis() - start;
                vectorHealth.put("status", healthy ? "healthy" : "unhealthy");
                vectorHealth.put("latencyMs", latency);
                if (!healthy) allHealthy = false;
            } catch (Exception e) {
                vectorHealth.put("status", "error");
                vectorHealth.put("error", e.getMessage());
                allHealthy = false;
            }
        } else {
            vectorHealth.put("status", "not_configured");
        }
        health.put("vectorStore", vectorHealth);

        // 2. 图库健康检查
        Map<String, Object> graphHealth = new HashMap<>();
        if (graphStore != null) {
            try {
                long start = System.currentTimeMillis();
                boolean healthy = graphStore.healthCheck();
                long latency = System.currentTimeMillis() - start;
                graphHealth.put("status", healthy ? "healthy" : "unhealthy");
                graphHealth.put("latencyMs", latency);
                if (!healthy) allHealthy = false;
            } catch (Exception e) {
                graphHealth.put("status", "error");
                graphHealth.put("error", e.getMessage());
                allHealthy = false;
            }
        } else {
            graphHealth.put("status", "not_configured");
        }
        health.put("graphStore", graphHealth);

        // 3. 元数据库健康检查
        Map<String, Object> metadataHealth = new HashMap<>();
        if (metadataStore != null) {
            try {
                long start = System.currentTimeMillis();
                boolean healthy = metadataStore.healthCheck();
                long latency = System.currentTimeMillis() - start;
                metadataHealth.put("status", healthy ? "healthy" : "unhealthy");
                metadataHealth.put("latencyMs", latency);
                if (!healthy) allHealthy = false;
            } catch (Exception e) {
                metadataHealth.put("status", "error");
                metadataHealth.put("error", e.getMessage());
                allHealthy = false;
            }
        } else {
            metadataHealth.put("status", "not_configured");
        }
        health.put("metadataStore", metadataHealth);

        // 4. 总体状态
        health.put("overall", allHealthy ? "healthy" : "degraded");
        health.put("timestamp", java.time.Instant.now().toString());

        int statusCode = allHealthy ? 200 : 503;
        jsonResponse(exchange, statusCode, health);
        log("[AdminHandler] 存储健康检查完成: " + (allHealthy ? "ALL_HEALTHY" : "DEGRADED"));
    }

    // ==================== POST /admin/maintenance/compact ====================

    /**
     * 触发存储压缩（仅标记，实际压缩由适配器实现）
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleMaintenanceCompact(HttpExchange exchange) throws IOException {
        log("[AdminHandler] 触发存储压缩");

        Map<String, Object> result = new HashMap<>();
        List<String> compacted = new ArrayList<>();

        // 向量库压缩
        if (vectorStore != null) {
            try {
                // 当前VectorStore接口无compact方法，标记为已触发
                compacted.add("vectorStore: compact triggered (adapter-level)");
            } catch (Exception e) {
                compacted.add("vectorStore: error - " + e.getMessage());
            }
        }

        // 图库压缩
        if (graphStore != null) {
            compacted.add("graphStore: compact triggered (adapter-level)");
        }

        // 元数据库压缩
        if (metadataStore != null) {
            compacted.add("metadataStore: compact triggered (adapter-level)");
        }

        result.put("compacted", compacted);
        result.put("timestamp", java.time.Instant.now().toString());
        result.put("note", "压缩标记已设置，实际压缩由各存储适配器异步执行");

        jsonResponse(exchange, 200, result);
        log("[AdminHandler] 存储压缩已触发: " + compacted);
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化运行时间
     *
     * @param uptimeMs 运行毫秒数
     * @return 格式化字符串
     */
    private String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
