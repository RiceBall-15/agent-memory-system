package com.memoryplatform.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * HTTP服务器封装
 * <p>
 * 基于JDK内置 {@link com.sun.net.httpserver.HttpServer} 的轻量级HTTP服务器。
 * 提供优雅的启动/停止接口和完善的异常处理。
 * </p>
 * <p>
 * 功能特性：
 * <ul>
 *   <li>使用JDK内置HttpServer，无需额外依赖</li>
 *   <li>可配置的线程池</li>
 *   <li>优雅的启动和停止</li>
 *   <li>完善的异常处理和日志</li>
 *   <li>与Router框架无缝集成</li>
 *   <li>支持WebSocket上下文注册</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @see Router
 */
@Slf4j
public class MemoryHttpServer {

    /** 默认端口 */
    public static final int DEFAULT_PORT = 8080;

    /** 默认线程数 */
    public static final int DEFAULT_THREAD_COUNT = 10;

    /** 默认超时时间（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 底层HttpServer实例 */
    private com.sun.net.httpserver.HttpServer server;

    /** 是否正在运行 */
    private volatile boolean running = false;

    /** 服务器端口 */
    private int port;

    /** 服务器地址 */
    private String host;

    /** 线程池大小 */
    private int threadCount;

    /** 超时时间（秒） */
    private int timeoutSeconds;

    /** 路由管理器引用 */
    private Router router;

    /** 版本化路由管理器引用 */
    private VersionedRouter versionedRouter;

    /**
     * 创建服务器实例（使用默认配置）
     */
    public MemoryHttpServer() {
        this(null, DEFAULT_PORT, DEFAULT_THREAD_COUNT, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 创建服务器实例
     *
     * @param port 监听端口
     */
    public MemoryHttpServer(int port) {
        this(null, port, DEFAULT_THREAD_COUNT, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 创建服务器实例
     *
     * @param host 监听地址（null表示所有地址）
     * @param port 监听端口
     * @param threadCount 线程池大小
     * @param timeoutSeconds 超时时间（秒）
     */
    public MemoryHttpServer(String host, int port, int threadCount, int timeoutSeconds) {
        this.host = host;
        this.port = port;
        this.threadCount = threadCount;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 启动服务器
     * <p>
     * 绑定指定端口并开始接受连接。
     * 使用Router处理所有传入的HTTP请求。
     * </p>
     *
     * @param port 监听端口
     * @param router 路由管理器
     * @throws IOException 如果启动失败
     */
    public void start(int port, Router router) throws IOException {
        if (running) {
            log.info("[MemoryHttpServer] 服务器已在运行")
            return;
        }

        this.port = port;
        this.router = router;

        // 创建HttpServer
        InetSocketAddress address;
        if (host != null && !host.isEmpty()) {
            address = new InetSocketAddress(host, port);
        } else {
            address = new InetSocketAddress(port);
        }

        server = HttpServer.create(address, 0);

        // 配置虚拟线程池
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.setAddress(address);

        // 注册全局处理器
        server.createContext("/", exchange -> {
            try {
                router.handle(exchange);
            } catch (Exception e) {
                log.error("[MemoryHttpServer] 处理请求异常: " + e.getMessage());
                e.printStackTrace();

                try {
                    String response = "{\"success\":false,\"error\":{\"code\":500,\"message\":\"Internal Server Error\"}}";
                    byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.getResponseBody().close();
                } catch (IOException ex) {
                    log.error("[MemoryHttpServer] 发送错误响应失败: " + ex.getMessage());
                }
            }
        });

        // 启动服务器
        server.start();
        running = true;

        log.info("===========================================")
        log.info("  MemoryPlatform HTTP Server Started")
        log.info("  Port: " + port)
        log.info("  Threads: " + threadCount)
        log.info("  Timeout: " + timeoutSeconds + "s")
        log.info("  Routes: " + getRouteCount(router))
        log.info("  Middlewares: " + router.getMiddlewares().size())
        log.info("===========================================")

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                log.info("[MemoryHttpServer] JVM关闭，正在停止服务器...")
                stop();
            }
        }));
    }

    /**
     * 启动服务器（使用默认端口）
     *
     * @param router 路由管理器
     * @throws IOException 如果启动失败
     */
    public void start(Router router) throws IOException {
        start(DEFAULT_PORT, router);
    }

    /**
     * 启动服务器（版本化路由）
     * <p>
     * 使用VersionedRouter处理请求，支持多版本API路由。
     * </p>
     *
     * @param port            监听端口
     * @param versionedRouter 版本化路由管理器
     * @throws IOException 如果启动失败
     */
    public void startVersioned(int port, VersionedRouter versionedRouter) throws IOException {
        if (running) {
            log.info("[MemoryHttpServer] 服务器已在运行")
            return;
        }

        this.port = port;
        this.versionedRouter = versionedRouter;

        // 创建HttpServer
        InetSocketAddress address;
        if (host != null && !host.isEmpty()) {
            address = new InetSocketAddress(host, port);
        } else {
            address = new InetSocketAddress(port);
        }

        server = HttpServer.create(address, 0);

        // 配置虚拟线程池
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.setAddress(address);

        // 注册全局处理器 - 使用VersionedRouter
        server.createContext("/", exchange -> {
            try {
                versionedRouter.handle(exchange);
            } catch (Exception e) {
                log.error("[MemoryHttpServer] 处理请求异常: " + e.getMessage());
                e.printStackTrace();

                try {
                    String response = "{\"success\":false,\"error\":{\"code\":500,\"message\":\"Internal Server Error\"}}";
                    byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.getResponseBody().close();
                } catch (IOException ex) {
                    log.error("[MemoryHttpServer] 发送错误响应失败: " + ex.getMessage());
                }
            }
        });

        // 启动服务器
        server.start();
        running = true;

        Map<String, Object> stats = versionedRouter.getStats();
        log.info("===========================================")
        log.info("  MemoryPlatform HTTP Server Started (Versioned)")
        log.info("  Port: " + port)
        log.info("  Threads: " + threadCount)
        log.info("  Timeout: " + timeoutSeconds + "s")
        log.info("  Versions: " + ApiVersion.getAllVersions())
        log.info("  Total Routes: " + stats.get("totalRoutes"))
        log.info("  Default Version: " + stats.get("defaultVersion"))
        log.info("  Fallback: " + stats.get("fallbackEnabled"))
        log.info("===========================================")

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                log.info("[MemoryHttpServer] JVM关闭，正在停止服务器...")
                stop();
            }
        }));
    }

    /**
     * 停止服务器
     * <p>
     * 优雅地停止服务器，等待正在处理的请求完成。
     * </p>
     */
    public void stop() {
        if (!running || server == null) {
            log.info("[MemoryHttpServer] 服务器未运行")
            return;
        }

        log.info("[MemoryHttpServer] 正在停止服务器...")

        server.stop(timeoutSeconds);
        running = false;

        log.info("[MemoryHttpServer] 服务器已停止")
    }

    /**
     * 立即停止服务器
     */
    public void stopImmediate() {
        if (!running || server == null) {
            return;
        }

        log.info("[MemoryHttpServer] 立即停止服务器...")
        server.stop(0);
        running = false;
        log.info("[MemoryHttpServer] 服务器已立即停止")
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
    public InetSocketAddress getAddress() {
        if (server != null) {
            return server.getAddress();
        }
        return null;
    }

    /**
     * 获取底层JDK HttpServer实例（用于注册WebSocket等额外上下文）
     *
     * @return HttpServer实例，未启动时返回null
     */
    public com.sun.net.httpserver.HttpServer getHttpServer() {
        return server;
    }

    /**
     * 注册WebSocket上下文到HttpServer
     * <p>
     * 在服务器启动后调用，将WebSocket处理器注册到指定路径。
     * JDK HttpServer的最长前缀匹配确保 /ws 路径优先于 / 路径。
     * </p>
     *
     * @param path     WebSocket路径，如 "/ws"
     * @param handler  WebSocket HttpHandler
     */
    public void registerWebSocketContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        if (server == null) {
            log.error("[MemoryHttpServer] HttpServer未启动，无法注册WebSocket上下文")
            return;
        }
        server.createContext(path, handler);
        log.info("[MemoryHttpServer] 已注册WebSocket上下文: " + path)
    }

    /**
     * 获取路由数量
     */
    private int getRouteCount(Router router) {
        int count = 0;
        for (var methodRoutes : router.getRoutes().values()) {
            count += methodRoutes.size();
        }
        return count;
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
     * 服务器构建器
     */
    public static class Builder {
        private String host = null;
        private int port = DEFAULT_PORT;
        private int threadCount = DEFAULT_THREAD_COUNT;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public MemoryHttpServer build() {
            return new MemoryHttpServer(host, port, threadCount, timeoutSeconds);
        }
    }

    /**
     * 快速启动服务器
     *
     * @param port 监听端口
     * @param router 路由管理器
     * @return 服务器实例
     * @throws IOException 如果启动失败
     */
    public static MemoryHttpServer quickStart(int port, Router router) throws IOException {
        MemoryHttpServer server = new MemoryHttpServer();
        server.start(port, router);
        return server;
    }

    /**
     * 快速启动服务器（默认端口）
     *
     * @param router 路由管理器
     * @return 服务器实例
     * @throws IOException 如果启动失败
     */
    public static MemoryHttpServer quickStart(Router router) throws IOException {
        return quickStart(DEFAULT_PORT, router);
    }
}
