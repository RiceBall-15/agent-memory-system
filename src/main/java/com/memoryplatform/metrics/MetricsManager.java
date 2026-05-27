package com.memoryplatform.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpServer;

/**
 * Prometheus指标管理器
 * <p>
 * 提供统一的指标定义和静态方法，用于在应用各处收集和记录指标。
 * 所有指标使用 {@link CollectorRegistry#defaultRegistry} 注册，
 * 支持通过 /metrics 端点暴露给 Prometheus 抓取。
 * </p>
 *
 * <h3>指标清单</h3>
 * <ul>
 *   <li><b>api_requests_total</b> - API请求计数 (标签: method, path, status)</li>
 *   <li><b>extraction_total</b> - 记忆提取计数 (标签: status)</li>
 *   <li><b>search_total</b> - 搜索请求计数 (标签: type)</li>
 *   <li><b>write_total</b> - 写入操作计数 (标签: store, status)</li>
 *   <li><b>api_latency_seconds</b> - API请求延迟 (标签: method, path)</li>
 *   <li><b>extraction_latency_seconds</b> - 提取操作延迟</li>
 *   <li><b>search_latency_seconds</b> - 搜索操作延迟</li>
 *   <li><b>write_latency_seconds</b> - 写入操作延迟</li>
 *   <li><b>active_connections</b> - 活跃连接数</li>
 *   <li><b>queue_depth</b> - 队列深度</li>
 *   <li><b>circuit_breaker_state</b> - 熔断器状态 (标签: store)</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 记录API请求
 * MetricsManager.incRequestCount("GET", "/api/memories", "200");
 * MetricsManager.observeLatency("GET", "/api/memories", 0.125);
 *
 * // 记录写入操作
 * MetricsManager.incWriteCount("milvus", "success");
 * MetricsManager.observeWriteLatency(0.350);
 *
 * // 更新Gauge
 * MetricsManager.setActiveConnections(42);
 * MetricsManager.setQueueDepth(5);
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @see MetricsCollector
 * @see MetricsHttpServer
 */
public final class MetricsManager {

    /** 默认metrics端口 */
    public static final int DEFAULT_METRICS_PORT = 9090;

    /** 默认监听地址 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** HTTP服务器实例 */
    private static volatile HttpServer httpServer;

    /** 是否已初始化 */
    private static volatile boolean initialized = false;

    /** 活跃连接数Gauge */
    private static final AtomicInteger activeConnections = new AtomicInteger(0);

    /** 队列深度Gauge */
    private static final AtomicInteger queueDepth = new AtomicInteger(0);

    // ============ Counter 定义 ============

    /**
     * API请求计数器
     * <p>标签: method, path, status</p>
     */
    private static final Counter API_REQUESTS = Counter.build()
            .name("api_requests_total")
            .help("Total number of API requests")
            .labelNames("method", "path", "status")
            .register();

    /**
     * 记忆提取计数器
     * <p>标签: status (success/error)</p>
     */
    private static final Counter EXTRACTION_TOTAL = Counter.build()
            .name("extraction_total")
            .help("Total number of memory extraction operations")
            .labelNames("status")
            .register();

    /**
     * 搜索请求计数器
     * <p>标签: type (hybrid/vector/graph/metadata)</p>
     */
    private static final Counter SEARCH_TOTAL = Counter.build()
            .name("search_total")
            .help("Total number of search operations")
            .labelNames("type")
            .register();

    /**
     * 写入操作计数器
     * <p>标签: store, status</p>
     */
    private static final Counter WRITE_TOTAL = Counter.build()
            .name("write_total")
            .help("Total number of write operations")
            .labelNames("store", "status")
            .register();

    // ============ Histogram 定义 ============

    /**
     * API请求延迟直方图
     * <p>标签: method, path</p>
     * <p>默认桶: .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10</p>
     */
    private static final Histogram API_LATENCY = Histogram.build()
            .name("api_latency_seconds")
            .help("API request latency in seconds")
            .labelNames("method", "path")
            .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .register();

    /**
     * 提取操作延迟直方图
     */
    private static final Histogram EXTRACTION_LATENCY = Histogram.build()
            .name("extraction_latency_seconds")
            .help("Memory extraction latency in seconds")
            .buckets(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
            .register();

    /**
     * 搜索操作延迟直方图
     */
    private static final Histogram SEARCH_LATENCY = Histogram.build()
            .name("search_latency_seconds")
            .help("Search operation latency in seconds")
            .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
            .register();

    /**
     * 写入操作延迟直方图
     */
    private static final Histogram WRITE_LATENCY = Histogram.build()
            .name("write_latency_seconds")
            .help("Write operation latency in seconds")
            .buckets(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .register();

    // ============ Gauge 定义 ============

    /**
     * 活跃连接数Gauge
     */
    private static final Gauge ACTIVE_CONNECTIONS = Gauge.build()
            .name("active_connections")
            .help("Number of currently active connections")
            .register();

    /**
     * 队列深度Gauge
     */
    private static final Gauge QUEUE_DEPTH = Gauge.build()
            .name("queue_depth")
            .help("Current depth of the processing queue")
            .register();

    /**
     * 熔断器状态Gauge
     * <p>标签: store</p>
     * <p>值: 0=CLOSED, 1=OPEN, 2=HALF_OPEN</p>
     */
    private static final Gauge CIRCUIT_BREAKER_STATE = Gauge.build()
            .name("circuit_breaker_state")
            .help("Circuit breaker state (0=closed, 1=open, 2=half_open)")
            .labelNames("store")
            .register();

    /**
     * 私有构造函数，防止实例化
     */
    private MetricsManager() {
        throw new UnsupportedOperationException("MetricsManager是静态工具类，不可实例化");
    }

    // ============ Counter 操作方法 ============

    /**
     * 递增API请求计数
     *
     * @param method HTTP方法 (GET, POST, PUT, DELETE)
     * @param path   请求路径
     * @param status HTTP状态码 (200, 404, 500等)
     */
    public static void incRequestCount(String method, String path, String status) {
        API_REQUESTS.labels(method, path, status).inc();
    }

    /**
     * 递增API请求计数（指定增量）
     *
     * @param method HTTP方法
     * @param path   请求路径
     * @param status HTTP状态码
     * @param count  增量值
     */
    public static void incRequestCount(String method, String path, String status, double count) {
        API_REQUESTS.labels(method, path, status).inc(count);
    }

    /**
     * 递增记忆提取计数
     *
     * @param status 操作状态 (success/error)
     */
    public static void incExtractionCount(String status) {
        EXTRACTION_TOTAL.labels(status).inc();
    }

    /**
     * 递增搜索请求计数
     *
     * @param type 搜索类型 (hybrid/vector/graph/metadata)
     */
    public static void incSearchCount(String type) {
        SEARCH_TOTAL.labels(type).inc();
    }

    /**
     * 递增写入操作计数
     *
     * @param store  存储类型 (milvus/neo4j/jdbc/redis)
     * @param status 操作状态 (success/error)
     */
    public static void incWriteCount(String store, String status) {
        WRITE_TOTAL.labels(store, status).inc();
    }

    // ============ Histogram 操作方法 ============

    /**
     * 记录API请求延迟
     *
     * @param method  HTTP方法
     * @param path    请求路径
     * @param seconds 延迟（秒）
     */
    public static void observeLatency(String method, String path, double seconds) {
        API_LATENCY.labels(method, path).observe(seconds);
    }

    /**
     * 记录记忆提取延迟
     *
     * @param seconds 延迟（秒）
     */
    public static void observeExtractionLatency(double seconds) {
        EXTRACTION_LATENCY.observe(seconds);
    }

    /**
     * 记录搜索操作延迟
     *
     * @param seconds 延迟（秒）
     */
    public static void observeSearchLatency(double seconds) {
        SEARCH_LATENCY.observe(seconds);
    }

    /**
     * 记录写入操作延迟
     *
     * @param seconds 延迟（秒）
     */
    public static void observeWriteLatency(double seconds) {
        WRITE_LATENCY.observe(seconds);
    }

    // ============ Gauge 操作方法 ============

    /**
     * 设置活跃连接数
     *
     * @param count 连接数
     */
    public static void setActiveConnections(int count) {
        activeConnections.set(count);
        ACTIVE_CONNECTIONS.set(count);
    }

    /**
     * 增加活跃连接数
     *
     * @param delta 增量（可为负数）
     */
    public static void addActiveConnections(int delta) {
        int newVal = activeConnections.addAndGet(delta);
        ACTIVE_CONNECTIONS.set(newVal);
    }

    /**
     * 设置队列深度
     *
     * @param depth 队列深度
     */
    public static void setQueueDepth(int depth) {
        queueDepth.set(depth);
        QUEUE_DEPTH.set(depth);
    }

    /**
     * 增加队列深度
     *
     * @param delta 增量（可为负数）
     */
    public static void addQueueDepth(int delta) {
        int newVal = queueDepth.addAndGet(delta);
        QUEUE_DEPTH.set(newVal);
    }

    /**
     * 设置熔断器状态
     *
     * @param store 存储名称
     * @param state 状态值 (0=closed, 1=open, 2=half_open)
     */
    public static void setCircuitBreakerState(String store, int state) {
        CIRCUIT_BREAKER_STATE.labels(store).set(state);
    }

    /**
     * 设置熔断器状态（使用枚举）
     *
     * @param store 存储名称
     * @param state 熔断器状态
     */
    public static void setCircuitBreakerState(String store, com.memoryplatform.circuit.CircuitBreaker.State state) {
        int value;
        switch (state) {
            case CLOSED:
                value = 0;
                break;
            case OPEN:
                value = 1;
                break;
            case HALF_OPEN:
                value = 2;
                break;
            default:
                value = -1;
                break;
        }
        CIRCUIT_BREAKER_STATE.labels(store).set(value);
    }

    // ============ 指标导出 ============

    /**
     * 获取所有指标的Prometheus文本格式
     *
     * @return Prometheus格式的指标文本
     */
    public static String getMetricsText() {
        try {
            StringWriter writer = new StringWriter();
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
            return writer.toString();
        } catch (IOException e) {
            System.err.println("[MetricsManager] 生成指标文本失败: " + e.getMessage());
            e.printStackTrace();
            return "# Error generating metrics\n";
        }
    }

    // ============ HTTP服务器 ============

    /**
     * 启动Prometheus metrics HTTP服务器
     * <p>
     * 在指定端口启动HTTP服务器，监听本地地址(127.0.0.1)，
     * 提供 /metrics 端点返回Prometheus格式的指标数据。
     * </p>
     *
     * @param port 监听端口（默认9090）
     * @throws IOException 如果启动失败
     */
    public static synchronized void startHttpServer(int port) throws IOException {
        if (httpServer != null) {
            System.out.println("[MetricsManager] Metrics HTTP服务器已在运行，端口: "
                    + httpServer.getAddress().getPort());
            return;
        }

        InetSocketAddress address = new InetSocketAddress(DEFAULT_HOST, port);
        httpServer = HttpServer.create(address, 0);

        // 注册 /metrics 端点
        httpServer.createContext("/metrics", exchange -> {
            try {
                String metricsText = getMetricsText();
                byte[] bytes = metricsText.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type",
                        "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                System.err.println("[MetricsManager] 处理/metrics请求异常: " + e.getMessage());
                e.printStackTrace();

                try {
                    String errorResponse = "# Error: " + e.getMessage() + "\n";
                    byte[] errorBytes = errorResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                } catch (IOException ex) {
                    System.err.println("[MetricsManager] 发送错误响应失败: " + ex.getMessage());
                }
            }
        });

        httpServer.start();
        initialized = true;

        System.out.println("===========================================");
        System.out.println("  Prometheus Metrics Server Started");
        System.out.println("  Port: " + port);
        System.out.println("  Host: " + DEFAULT_HOST);
        System.out.println("  Endpoint: http://" + DEFAULT_HOST + ":" + port + "/metrics");
        System.out.println("===========================================");

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (httpServer != null) {
                System.out.println("[MetricsManager] JVM关闭，正在停止Metrics服务器...");
                stopHttpServer();
            }
        }));
    }

    /**
     * 启动Prometheus metrics HTTP服务器（使用默认端口）
     *
     * @throws IOException 如果启动失败
     */
    public static void startHttpServer() throws IOException {
        startHttpServer(DEFAULT_METRICS_PORT);
    }

    /**
     * 停止Prometheus metrics HTTP服务器
     */
    public static synchronized void stopHttpServer() {
        if (httpServer != null) {
            System.out.println("[MetricsManager] 正在停止Metrics HTTP服务器...");
            httpServer.stop(1);
            httpServer = null;
            initialized = false;
            System.out.println("[MetricsManager] Metrics HTTP服务器已停止");
        }
    }

    /**
     * 检查Metrics HTTP服务器是否正在运行
     *
     * @return true如果正在运行
     */
    public static boolean isHttpServerRunning() {
        return httpServer != null && initialized;
    }

    /**
     * 获取Metrics HTTP服务器端口
     *
     * @return 端口号，未启动返回-1
     */
    public static int getHttpServerPort() {
        if (httpServer != null) {
            return httpServer.getAddress().getPort();
        }
        return -1;
    }

    /**
     * 注册自定义MetricsCollector
     * <p>
     * 注册 {@link MetricsCollector} 到默认注册表，
     * 用于收集JVM和应用层面的自定义指标。
     * </p>
     */
    public static void registerCustomCollector() {
        try {
            new MetricsCollector().register();
            System.out.println("[MetricsManager] 自定义MetricsCollector已注册");
        } catch (Exception e) {
            System.err.println("[MetricsManager] 注册MetricsCollector失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============ Getter 方法 (供MetricsCollector使用) ============

    /**
     * 获取当前活跃连接数值
     *
     * @return 活跃连接数
     */
    public static int getActiveConnectionsValue() {
        return activeConnections.get();
    }

    /**
     * 获取当前队列深度值
     *
     * @return 队列深度
     */
    public static int getQueueDepthValue() {
        return queueDepth.get();
    }

    // ============ 便捷方法 ============

    /**
     * 记录API请求（完整方法）
     * <p>同时记录请求计数和延迟</p>
     *
     * @param method       HTTP方法
     * @param path         请求路径
     * @param status       HTTP状态码
     * @param latencyMs    延迟（毫秒）
     */
    public static void recordApiRequest(String method, String path, String status, double latencyMs) {
        incRequestCount(method, path, status);
        observeLatency(method, path, latencyMs / 1000.0);
    }

    /**
     * 记录写入操作（完整方法）
     * <p>同时记录写入计数和延迟</p>
     *
     * @param store     存储类型
     * @param status    操作状态
     * @param latencyMs 延迟（毫秒）
     */
    public static void recordWriteOperation(String store, String status, double latencyMs) {
        incWriteCount(store, status);
        observeWriteLatency(latencyMs / 1000.0);
    }

    /**
     * 记录搜索操作（完整方法）
     * <p>同时记录搜索计数和延迟</p>
     *
     * @param type      搜索类型
     * @param latencyMs 延迟（毫秒）
     */
    public static void recordSearchOperation(String type, double latencyMs) {
        incSearchCount(type);
        observeSearchLatency(latencyMs / 1000.0);
    }

    /**
     * 记录提取操作（完整方法）
     * <p>同时记录提取计数和延迟</p>
     *
     * @param status    操作状态
     * @param latencyMs 延迟（毫秒）
     */
    public static void recordExtractionOperation(String status, double latencyMs) {
        incExtractionCount(status);
        observeExtractionLatency(latencyMs / 1000.0);
    }
}
