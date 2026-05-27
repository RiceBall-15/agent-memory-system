package com.memoryplatform.metrics;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

/**
 * Prometheus指标HTTP服务器
 * <p>
 * 专用的HTTP服务器，用于暴露Prometheus格式的指标数据。
 * 仅监听本地地址(127.0.0.1)，确保安全性和隔离性。
 * </p>
 *
 * <h3>功能特性</h3>
 * <ul>
 *   <li>GET /metrics - 返回Prometheus文本格式指标</li>
 *   <li>仅监听本地地址(127.0.0.1)</li>
 *   <li>默认端口9090</li>
 *   <li>可配置的线程池大小</li>
 *   <li>优雅的启动和停止</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建并启动服务器
 * MetricsHttpServer server = new MetricsHttpServer(9090);
 * server.start();
 *
 * // 或使用构建器
 * MetricsHttpServer server = MetricsHttpServer.builder()
 *     .port(9090)
 *     .host("127.0.0.1")
 *     .threadCount(4)
 *     .build();
 * server.start();
 *
 * // 停止服务器
 * server.stop();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @see MetricsManager
 * @see MetricsCollector
 */
public class MetricsHttpServer {

    /** 默认端口 */
    public static final int DEFAULT_PORT = 9090;

    /** 默认监听地址 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** 默认线程池大小 */
    public static final int DEFAULT_THREAD_COUNT = 2;

    /** 默认超时时间（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;

    /** 底层HttpServer实例 */
    private HttpServer server;

    /** 是否正在运行 */
    private volatile boolean running = false;

    /** 服务器端口 */
    private final int port;

    /** 服务器地址 */
    private final String host;

    /** 线程池大小 */
    private final int threadCount;

    /** 超时时间（秒） */
    private final int timeoutSeconds;

    /** 是否已注册自定义收集器 */
    private volatile boolean collectorRegistered = false;

    /**
     * 使用默认配置创建服务器
     */
    public MetricsHttpServer() {
        this(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_THREAD_COUNT, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 使用指定端口创建服务器
     *
     * @param port 监听端口
     */
    public MetricsHttpServer(int port) {
        this(DEFAULT_HOST, port, DEFAULT_THREAD_COUNT, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 使用完整配置创建服务器
     *
     * @param host           监听地址
     * @param port           监听端口
     * @param threadCount    线程池大小
     * @param timeoutSeconds 超时时间（秒）
     */
    public MetricsHttpServer(String host, int port, int threadCount, int timeoutSeconds) {
        this.host = host;
        this.port = port;
        this.threadCount = threadCount;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 启动Metrics HTTP服务器
     * <p>
     * 绑定指定地址和端口，开始接受HTTP请求。
     * 自动注册自定义MetricsCollector。
     * </p>
     *
     * @throws IOException 如果启动失败
     */
    public synchronized void start() throws IOException {
        if (running) {
            System.out.println("[MetricsHttpServer] 服务器已在运行，端口: " + port);
            return;
        }

        // 注册自定义收集器
        if (!collectorRegistered) {
            registerCustomCollector();
        }

        // 创建HttpServer
        InetSocketAddress address = new InetSocketAddress(host, port);
        server = HttpServer.create(address, 0);

        // 配置线程池
        server.setExecutor(Executors.newFixedThreadPool(threadCount));

        // 注册 /metrics 端点
        server.createContext("/metrics", exchange -> {
            try {
                String metricsText = generateMetricsText();
                byte[] bytes = metricsText.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type",
                        "text/plain; version=0.0.4; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                System.err.println("[MetricsHttpServer] 处理请求异常: " + e.getMessage());
                e.printStackTrace();

                try {
                    String errorResponse = "# Error: " + e.getMessage() + "\n";
                    byte[] errorBytes = errorResponse.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                } catch (IOException ex) {
                    System.err.println("[MetricsHttpServer] 发送错误响应失败: " + ex.getMessage());
                }
            }
        });

        // 注册根路径处理器（返回简单的状态信息）
        server.createContext("/", exchange -> {
            try {
                String status = "{\"status\":\"ok\",\"service\":\"prometheus-metrics\",\"port\":" + port + "}";
                byte[] bytes = status.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                System.err.println("[MetricsHttpServer] 处理根路径请求异常: " + e.getMessage());
            }
        });

        // 启动服务器
        server.start();
        running = true;

        System.out.println("===========================================");
        System.out.println("  Prometheus Metrics HTTP Server Started");
        System.out.println("  Host: " + host);
        System.out.println("  Port: " + port);
        System.out.println("  Threads: " + threadCount);
        System.out.println("  Endpoint: http://" + host + ":" + port + "/metrics");
        System.out.println("===========================================");

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                System.out.println("[MetricsHttpServer] JVM关闭，正在停止Metrics服务器...");
                stop();
            }
        }));
    }

    /**
     * 停止Metrics HTTP服务器
     */
    public synchronized void stop() {
        if (!running || server == null) {
            System.out.println("[MetricsHttpServer] 服务器未运行");
            return;
        }

        System.out.println("[MetricsHttpServer] 正在停止Metrics HTTP服务器...");
        server.stop(timeoutSeconds);
        running = false;
        System.out.println("[MetricsHttpServer] Metrics HTTP服务器已停止");
    }

    /**
     * 立即停止Metrics HTTP服务器
     */
    public void stopImmediate() {
        if (!running || server == null) {
            return;
        }

        System.out.println("[MetricsHttpServer] 立即停止Metrics HTTP服务器...");
        server.stop(0);
        running = false;
        System.out.println("[MetricsHttpServer] Metrics HTTP服务器已立即停止");
    }

    /**
     * 检查服务器是否正在运行
     *
     * @return true如果正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取服务器端口
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取服务器地址
     *
     * @return 服务器地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 注册自定义MetricsCollector
     */
    private void registerCustomCollector() {
        try {
            new MetricsCollector().register();
            collectorRegistered = true;
            System.out.println("[MetricsHttpServer] 自定义MetricsCollector已注册");
        } catch (Exception e) {
            System.err.println("[MetricsHttpServer] 注册MetricsCollector失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 生成Prometheus文本格式的指标数据
     *
     * @return 指标文本
     */
    private String generateMetricsText() {
        try {
            StringWriter writer = new StringWriter();
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
            return writer.toString();
        } catch (IOException e) {
            System.err.println("[MetricsHttpServer] 生成指标文本失败: " + e.getMessage());
            e.printStackTrace();
            return "# Error generating metrics: " + e.getMessage() + "\n";
        }
    }

    /**
     * 创建服务器构建器
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速启动Metrics HTTP服务器
     *
     * @param port 监听端口
     * @return 服务器实例
     * @throws IOException 如果启动失败
     */
    public static MetricsHttpServer quickStart(int port) throws IOException {
        MetricsHttpServer server = new MetricsHttpServer(port);
        server.start();
        return server;
    }

    /**
     * 快速启动Metrics HTTP服务器（使用默认端口）
     *
     * @return 服务器实例
     * @throws IOException 如果启动失败
     */
    public static MetricsHttpServer quickStart() throws IOException {
        return quickStart(DEFAULT_PORT);
    }

    /**
     * 服务器构建器
     * <p>
     * 使用Builder模式配置服务器参数：
     * <pre>{@code
     * MetricsHttpServer server = MetricsHttpServer.builder()
     *     .port(9090)
     *     .host("127.0.0.1")
     *     .threadCount(4)
     *     .timeoutSeconds(10)
     *     .build();
     * server.start();
     * }</pre>
     * </p>
     */
    public static class Builder {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private int threadCount = DEFAULT_THREAD_COUNT;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        /**
         * 设置监听地址
         *
         * @param host 监听地址
         * @return this
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置监听端口
         *
         * @param port 监听端口
         * @return this
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置线程池大小
         *
         * @param threadCount 线程数
         * @return this
         */
        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        /**
         * 设置超时时间
         *
         * @param timeoutSeconds 超时秒数
         * @return this
         */
        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * 构建服务器实例
         *
         * @return 服务器实例
         */
        public MetricsHttpServer build() {
            return new MetricsHttpServer(host, port, threadCount, timeoutSeconds);
        }
    }
}
