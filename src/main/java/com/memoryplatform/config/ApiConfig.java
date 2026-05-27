package com.memoryplatform.config;

import com.memoryplatform.handler.HealthHandler;
import com.memoryplatform.handler.MemoryHandler;
import com.memoryplatform.handler.SearchHandler;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.server.Router;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * API路由配置 - 统一注册所有REST API路由
 * <p>
 * 负责将所有处理器注册到路由器中，并提供统一的错误处理包装。
 * 所有API端点使用 {@code /api} 前缀，健康检查使用 {@code /health} 路径。
 * </p>
 *
 * <h3>注册的路由</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>处理器</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/memories</td><td>MemoryHandler</td><td>创建记忆（对话提取）</td></tr>
 *   <tr><td>GET</td><td>/api/memories/{id}</td><td>MemoryHandler</td><td>获取单条记忆</td></tr>
 *   <tr><td>PUT</td><td>/api/memories/{id}</td><td>MemoryHandler</td><td>更新记忆</td></tr>
 *   <tr><td>DELETE</td><td>/api/memories/{id}</td><td>MemoryHandler</td><td>删除记忆</td></tr>
 *   <tr><td>GET</td><td>/api/memories</td><td>MemoryHandler</td><td>列表查询</td></tr>
 *   <tr><td>POST</td><td>/api/search</td><td>SearchHandler</td><td>混合检索</td></tr>
 *   <tr><td>POST</td><td>/api/search/vector</td><td>SearchHandler</td><td>向量搜索</td></tr>
 *   <tr><td>POST</td><td>/api/search/graph</td><td>SearchHandler</td><td>图搜索</td></tr>
 *   <tr><td>GET</td><td>/health</td><td>HealthHandler</td><td>健康检查</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Router router = new Router();
 * ApiConfig.registerRoutes(router, memoryHandler, searchHandler, healthHandler);
 * server.start(port, router);
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 * @see MemoryHandler
 * @see SearchHandler
 * @see HealthHandler
 */
@Slf4j
public class ApiConfig {

    /** API路径前缀 */
    public static final String API_PREFIX = "/api";

    /** 健康检查路径 */
    public static final String HEALTH_PATH = "/health";

    /** 私有构造函数，防止实例化 */
    private ApiConfig() {
        throw new UnsupportedOperationException("ApiConfig是工具类，不应被实例化");
    }

    /**
     * 注册所有API路由到路由器
     * <p>
     * 统一注册所有REST API端点，并为每个处理器包装错误处理逻辑。
     * 错误处理会捕获所有未处理异常，返回标准化的JSON错误响应。
     * </p>
     *
     * @param router          路由管理器
     * @param memoryHandler   记忆处理器
     * @param searchHandler   搜索处理器
     * @param healthHandler   健康检查处理器
     */
    public static void registerRoutes(Router router,
                                       MemoryHandler memoryHandler,
                                       SearchHandler searchHandler,
                                       HealthHandler healthHandler) {
        log.info("[ApiConfig] 开始注册API路由...")

        // ========== 记忆CRUD路由 ==========
        registerMemoryRoutes(router, memoryHandler);

        // ========== 搜索路由 ==========
        registerSearchRoutes(router, searchHandler);

        // ========== 健康检查路由 ==========
        registerHealthRoute(router, healthHandler);

        log.info("[ApiConfig] API路由注册完成")
        log.info("[ApiConfig] API前缀: " + API_PREFIX)
        log.info("[ApiConfig] 健康检查: " + HEALTH_PATH)
    }

    /**
     * 注册记忆CRUD路由
     *
     * @param router        路由管理器
     * @param memoryHandler 记忆处理器
     */
    private static void registerMemoryRoutes(Router router, MemoryHandler memoryHandler) {
        log.info("[ApiConfig] 注册记忆路由...")

        // POST /api/memories - 创建记忆
        router.post(API_PREFIX + "/memories", wrapHandler(memoryHandler, "创建记忆"));

        // GET /api/memories/{id} - 获取单条记忆（必须在列表查询之前注册）
        router.get(API_PREFIX + "/memories/{id}", wrapHandler(memoryHandler, "获取记忆"));

        // PUT /api/memories/{id} - 更新记忆
        router.put(API_PREFIX + "/memories/{id}", wrapHandler(memoryHandler, "更新记忆"));

        // DELETE /api/memories/{id} - 删除记忆
        router.delete(API_PREFIX + "/memories/{id}", wrapHandler(memoryHandler, "删除记忆"));

        // GET /api/memories - 列表查询
        router.get(API_PREFIX + "/memories", wrapHandler(memoryHandler, "列表查询"));
    }

    /**
     * 注册搜索路由
     *
     * @param router        路由管理器
     * @param searchHandler 搜索处理器
     */
    private static void registerSearchRoutes(Router router, SearchHandler searchHandler) {
        log.info("[ApiConfig] 注册搜索路由...")

        // POST /api/search - 混合检索
        router.post(API_PREFIX + "/search", wrapHandler(searchHandler, "混合检索"));

        // POST /api/search/vector - 向量搜索
        router.post(API_PREFIX + "/search/vector", wrapHandler(searchHandler, "向量搜索"));

        // POST /api/search/graph - 图搜索
        router.post(API_PREFIX + "/search/graph", wrapHandler(searchHandler, "图搜索"));
    }

    /**
     * 注册健康检查路由
     *
     * @param router        路由管理器
     * @param healthHandler 健康检查处理器
     */
    private static void registerHealthRoute(Router router, HealthHandler healthHandler) {
        log.info("[ApiConfig] 注册健康检查路由...")

        // GET /health - 系统健康状态
        router.get(HEALTH_PATH, wrapHandler(healthHandler, "健康检查"));
    }

    /**
     * 包装处理器，添加统一的错误处理和日志
     * <p>
     * 所有处理器方法都会被包装在此方法中，提供:
     * <ul>
     *   <li>请求开始日志</li>
     *   <li>异常捕获和错误响应</li>
     *   <li>请求完成日志</li>
     * </ul>
     * </p>
     *
     * @param handler   原始处理器
     * @param operation 操作名称（用于日志）
     * @return 包装后的处理器
     */
    private static HttpHandler wrapHandler(HttpHandler handler, String operation) {
        return (exchange, pathParams) -> {
            long startTime = System.currentTimeMillis();
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            log.info("[ApiConfig] [" + operation + "] 请求开始: " + method + " " + path)

            try {
                handler.handle(exchange, pathParams);
            } catch (Exception e) {
                log.error("[ApiConfig] [" + operation + "] 请求异常: " + e.getMessage());
                e.printStackTrace();

                try {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);

                    Map<String, Object> error = new HashMap<>();
                    error.put("code", 500);
                    error.put("message", "内部服务器错误: " + e.getMessage());
                    error.put("operation", operation);
                    errorResponse.put("error", error);

                    String json = HttpHandler.GSON.toJson(errorResponse);
                    byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(500, bytes.length);

                    try (var os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (IOException ex) {
                    log.error("[ApiConfig] [" + operation + "] 发送错误响应失败: " + ex.getMessage());
                }
            } finally {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[ApiConfig] [" + operation + "] 请求完成: " + method + " " + path
                        + " (" + elapsed + "ms)")
            }
        };
    }

    /**
     * 打印所有注册的路由信息
     * <p>
     * 用于调试和启动信息展示。
     * </p>
     *
     * @param router 路由管理器
     */
    public static void printRoutes(Router router) {
        log.info("\n===========================================")
        log.info("  MemoryPlatform API Routes")
        log.info("===========================================")

        for (Map.Entry<String, java.util.List<Router.Route>> entry : router.getRoutes().entrySet()) {
            for (Router.Route route : entry.getValue()) {
                log.info(String.format("  %-7s %-30s%n", route.getMethod(), route.getPath()));
            }
        }

        log.info("===========================================\n")
    }
}
