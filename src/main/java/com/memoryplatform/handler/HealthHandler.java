package com.memoryplatform.handler;

import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.util.*;

/**
 * 健康检查处理器 - 系统健康状态监控
 * <p>
 * 提供以下端点:
 * <ul>
 *   <li>{@code GET /health} - 系统整体健康状态</li>
 * </ul>
 * </p>
 *
 * <p>
 * 返回各存储层的健康状态和系统运行信息，可用于:
 * <ul>
 *   <li>负载均衡器健康探测</li>
 *   <li>Kubernetes liveness/readiness探针</li>
 *   <li>监控系统心跳检测</li>
 * </ul>
 * </p>
 *
 * <h3>响应示例</h3>
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "timestamp": "2024-01-01T00:00:00Z",
 *   "uptimeMs": 123456,
 *   "stores": {
 *     "vector": "UP",
 *     "graph": "UP",
 *     "metadata": "UP"
 *   },
 *   "memory": {
 *     "usedMB": 256,
 *     "maxMB": 1024
 *   }
 * }
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class HealthHandler implements HttpHandler {

    /** 启动时间 */
    private final Instant startTime = Instant.now();

    /** 向量存储 */
    private final VectorStore vectorStore;

    /** 图存储 */
    private final GraphStore graphStore;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /**
     * 构造健康检查处理器
     *
     * @param vectorStore   向量存储（可为null）
     * @param graphStore    图存储（可为null）
     * @param metadataStore 元数据存储（可为null）
     */
    public HealthHandler(VectorStore vectorStore, GraphStore graphStore, MetadataStore metadataStore) {
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.metadataStore = metadataStore;
        log("[HealthHandler] 初始化完成");
    }

    /**
     * 处理健康检查请求
     *
     * @param exchange   HTTP交换对象
     * @param pathParams 路径参数映射
     * @throws IOException 如果IO操作失败
     */
    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[HealthHandler] 请求: " + method + " " + path);

        // 只允许GET方法
        if (!"GET".equals(method)) {
            errorResponse(exchange, 405, "健康检查仅支持GET方法");
            return;
        }

        try {
            Map<String, Object> healthData = buildHealthResponse();

            // 判断整体状态
            String overallStatus = (String) healthData.get("status");
            int httpStatus = "DOWN".equals(overallStatus) ? 503 : 200;

            jsonResponse(exchange, httpStatus, healthData);
            log("[HealthHandler] 健康检查完成, status=" + overallStatus);

        } catch (Exception e) {
            logError("[HealthHandler] 健康检查异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "健康检查失败: " + e.getMessage());
        }
    }

    /**
     * 构建健康检查响应数据
     *
     * @return 健康状态映射
     */
    private Map<String, Object> buildHealthResponse() {
        Map<String, Object> response = new HashMap<>();

        // 1. 检查各存储层状态
        Map<String, String> storeStatuses = new LinkedHashMap<>();

        String vectorStatus = checkVectorStore();
        String graphStatus = checkGraphStore();
        String metadataStatus = checkMetadataStore();

        storeStatuses.put("vector", vectorStatus);
        storeStatuses.put("graph", graphStatus);
        storeStatuses.put("metadata", metadataStatus);

        // 2. 判断整体状态
        boolean allUp = "UP".equals(vectorStatus) && "UP".equals(graphStatus) && "UP".equals(metadataStatus);
        // 如果所有存储都未配置，视为DEGRADED
        boolean allNull = vectorStore == null && graphStore == null && metadataStore == null;
        // 如果至少有一个UP，整体为UP
        boolean anyUp = "UP".equals(vectorStatus) || "UP".equals(graphStatus) || "UP".equals(metadataStatus);

        String overallStatus;
        if (allNull) {
            overallStatus = "DEGRADED"; // 所有存储未配置
        } else if (allUp) {
            overallStatus = "UP";
        } else if (anyUp) {
            overallStatus = "DEGRADED"; // 部分存储不可用
        } else {
            overallStatus = "DOWN";
        }

        response.put("status", overallStatus);
        response.put("stores", storeStatuses);

        // 3. 时间信息
        response.put("timestamp", Instant.now().toString());
        long uptimeMs = System.currentTimeMillis() - startTime.toEpochMilli();
        response.put("uptimeMs", uptimeMs);
        response.put("uptimeFormatted", formatUptime(uptimeMs));

        // 4. JVM内存信息
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            long usedMB = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long maxMB = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);

            Map<String, Object> memoryInfo = new LinkedHashMap<>();
            memoryInfo.put("usedMB", usedMB);
            memoryInfo.put("maxMB", maxMB);
            response.put("memory", memoryInfo);
        } catch (Exception e) {
            logError("[HealthHandler] 获取内存信息失败: " + e.getMessage());
        }

        // 5. JVM运行时信息
        try {
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            Map<String, Object> runtimeInfo = new LinkedHashMap<>();
            runtimeInfo.put("pid", runtimeBean.getName());
            runtimeInfo.put("javaVersion", System.getProperty("java.version"));
            runtimeInfo.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            response.put("runtime", runtimeInfo);
        } catch (Exception e) {
            logError("[HealthHandler] 获取运行时信息失败: " + e.getMessage());
        }

        return response;
    }

    /**
     * 检查向量存储健康状态
     *
     * @return "UP" 或 "DOWN" 或 "NOT_CONFIGURED"
     */
    private String checkVectorStore() {
        if (vectorStore == null) {
            return "NOT_CONFIGURED";
        }
        try {
            return vectorStore.healthCheck() ? "UP" : "DOWN";
        } catch (Exception e) {
            logError("[HealthHandler] 向量存储健康检查失败: " + e.getMessage());
            return "DOWN";
        }
    }

    /**
     * 检查图存储健康状态
     *
     * @return "UP" 或 "DOWN" 或 "NOT_CONFIGURED"
     */
    private String checkGraphStore() {
        if (graphStore == null) {
            return "NOT_CONFIGURED";
        }
        try {
            return graphStore.healthCheck() ? "UP" : "DOWN";
        } catch (Exception e) {
            logError("[HealthHandler] 图存储健康检查失败: " + e.getMessage());
            return "DOWN";
        }
    }

    /**
     * 检查元数据存储健康状态
     *
     * @return "UP" 或 "DOWN" 或 "NOT_CONFIGURED"
     */
    private String checkMetadataStore() {
        if (metadataStore == null) {
            return "NOT_CONFIGURED";
        }
        try {
            return metadataStore.healthCheck() ? "UP" : "DOWN";
        } catch (Exception e) {
            logError("[HealthHandler] 元数据存储健康检查失败: " + e.getMessage());
            return "DOWN";
        }
    }

    /**
     * 格式化运行时间为可读字符串
     *
     * @param uptimeMs 运行时间（毫秒）
     * @return 格式化的运行时间字符串
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
