package com.memoryplatform.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 版本化路由管理器
 * <p>
 * 管理多个API版本的路由，支持版本检测、版本兼容和版本降级。
 * 每个版本维护独立的 {@link Router} 实例，通过版本前缀隔离路由空间。
 * </p>
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li>路径前缀隔离: /api/v1/*, /api/v2/*</li>
 *   <li>版本兼容: V2自动包含V1的所有路由</li>
 *   <li>版本降级: 如果V2路由不存在，回退到V1</li>
 *   <li>版本检测: 从URL路径或Accept头检测版本</li>
 *   <li>路由克隆: V2可以继承V1的路由并覆盖特定端点</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * VersionedRouter vr = new VersionedRouter();
 *
 * // 获取V1路由器并注册路由
 * Router v1 = vr.getRouter(ApiVersion.V1);
 * v1.get("/api/v1/memories/{id}", handler);
 *
 * // 获取V2路由器（自动继承V1路由）
 * Router v2 = vr.getRouter(ApiVersion.V2);
 * v2.get("/api/v2/memories/{id}", enhancedHandler);
 *
 * // 处理请求
 * vr.handle(exchange);
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 2.0
 * @since 2.0
 * @see ApiVersion
 * @see Router
 */
public class VersionedRouter {

    /** 版本路由器映射 */
    private final Map<ApiVersion, Router> versionRouters = new ConcurrentHashMap<>();

    /** 版本中间件（应用于所有版本） */
    private final List<Middleware> globalMiddlewares = new CopyOnWriteArrayList<>();

    /** 404处理器 */
    private com.memoryplatform.server.HttpHandler notFoundHandler;

    /** 默认版本 */
    private ApiVersion defaultVersion = ApiVersion.getDefault();

    /** 是否启用版本降级 */
    private boolean fallbackEnabled = true;

    /**
     * 创建版本化路由器
     */
    public VersionedRouter() {
        // 为每个版本创建独立的路由器
        for (ApiVersion version : ApiVersion.values()) {
            versionRouters.put(version, new Router());
        }
        System.out.println("[VersionedRouter] 初始化完成, 支持版本: " + ApiVersion.getAllVersions());
    }

    /**
     * 获取指定版本的路由器
     *
     * @param version API版本
     * @return 对应版本的Router实例
     */
    public Router getRouter(ApiVersion version) {
        return versionRouters.get(version);
    }

    /**
     * 获取指定版本的路由器（字符串参数）
     *
     * @param versionStr 版本字符串（如 "v1", "v2"）
     * @return 对应版本的Router实例，版本不存在返回null
     */
    public Router getRouter(String versionStr) {
        ApiVersion version = ApiVersion.fromPrefix(versionStr);
        return version != null ? versionRouters.get(version) : null;
    }

    /**
     * 添加全局中间件（应用于所有版本）
     *
     * @param middleware 中间件实例
     * @return 版本化路由器实例（支持链式调用）
     */
    public VersionedRouter addGlobalMiddleware(Middleware middleware) {
        globalMiddlewares.add(middleware);
        // 同时添加到所有版本的路由器
        for (Router router : versionRouters.values()) {
            router.addMiddleware(middleware);
        }
        System.out.println("[VersionedRouter] 添加全局中间件: " + middleware.getName());
        return this;
    }

    /**
     * 为指定版本添加中间件
     *
     * @param version    API版本
     * @param middleware 中间件实例
     * @return 版本化路由器实例
     */
    public VersionedRouter addMiddleware(ApiVersion version, Middleware middleware) {
        Router router = versionRouters.get(version);
        if (router != null) {
            router.addMiddleware(middleware);
            System.out.println("[VersionedRouter] 添加版本中间件 [" + version + "]: " + middleware.getName());
        }
        return this;
    }

    /**
     * 克隆V1路由到V2
     * <p>
     * 将V1中注册的所有路由复制到V2，并替换路径前缀。
     * V2可以随后覆盖特定端点。
     * </p>
     *
     * @return 版本化路由器实例
     */
    public VersionedRouter inheritV1ToV2() {
        Router v1Router = versionRouters.get(ApiVersion.V1);
        Router v2Router = versionRouters.get(ApiVersion.V2);

        if (v1Router == null || v2Router == null) {
            System.err.println("[VersionedRouter] 无法继承: V1或V2路由器不存在");
            return this;
        }

        int inheritedCount = 0;
        for (Map.Entry<String, List<Router.Route>> entry : v1Router.getRoutes().entrySet()) {
            String method = entry.getKey();
            for (Router.Route route : entry.getValue()) {
                String v1Path = route.getPath();
                // 将 /api/v1/* 转换为 /api/v2/*，或将 /api/* 转换为 /api/v2/*
                String v2Path = convertPathForVersion(v1Path, ApiVersion.V2);
                if (v2Path != null) {
                    // 使用版本包装器包装处理器
                    com.memoryplatform.server.HttpHandler wrappedHandler =
                        wrapWithVersion(route.getHandler(), ApiVersion.V2);
                    addRouteToRouter(v2Router, method, v2Path, wrappedHandler);
                    inheritedCount++;
                }
            }
        }

        System.out.println("[VersionedRouter] 从V1继承 " + inheritedCount + " 条路由到V2");
        return this;
    }

    /**
     * 将无版本前缀的路由注册到指定版本
     * <p>
     * 自动添加版本路径前缀。例如:
     * - 路径 "/api/memories" + 版本 V1 → "/api/v1/memories"
     * - 路径 "/api/memories" + 版本 V2 → "/api/v2/memories"
     * </p>
     *
     * @param version API版本
     * @param method  HTTP方法
     * @param path    路径（不含版本前缀）
     * @param handler 处理器
     * @return 版本化路由器实例
     */
    public VersionedRouter registerRoute(ApiVersion version, String method,
                                          String path, com.memoryplatform.server.HttpHandler handler) {
        Router router = versionRouters.get(version);
        if (router == null) {
            System.err.println("[VersionedRouter] 版本不存在: " + version);
            return this;
        }

        String versionedPath = convertPathForVersion(path, version);
        if (versionedPath == null) {
            versionedPath = version.getApiPathPrefix() + normalizePath(path);
        }

        com.memoryplatform.server.HttpHandler wrappedHandler = wrapWithVersion(handler, version);
        addRouteToRouter(router, method, versionedPath, wrappedHandler);

        return this;
    }

    /**
     * 为指定版本注册GET路由
     */
    public VersionedRouter get(ApiVersion version, String path,
                                com.memoryplatform.server.HttpHandler handler) {
        return registerRoute(version, "GET", path, handler);
    }

    /**
     * 为指定版本注册POST路由
     */
    public VersionedRouter post(ApiVersion version, String path,
                                 com.memoryplatform.server.HttpHandler handler) {
        return registerRoute(version, "POST", path, handler);
    }

    /**
     * 为指定版本注册PUT路由
     */
    public VersionedRouter put(ApiVersion version, String path,
                                com.memoryplatform.server.HttpHandler handler) {
        return registerRoute(version, "PUT", path, handler);
    }

    /**
     * 为指定版本注册DELETE路由
     */
    public VersionedRouter delete(ApiVersion version, String path,
                                   com.memoryplatform.server.HttpHandler handler) {
        return registerRoute(version, "DELETE", path, handler);
    }

    /**
     * 为指定版本注册PATCH路由
     */
    public VersionedRouter patch(ApiVersion version, String path,
                                  com.memoryplatform.server.HttpHandler handler) {
        return registerRoute(version, "PATCH", path, handler);
    }

    /**
     * 为所有版本注册相同路由
     *
     * @param method  HTTP方法
     * @param path    路径
     * @param handler 处理器
     * @return 版本化路由器实例
     */
    public VersionedRouter registerAllVersions(String method, String path,
                                                com.memoryplatform.server.HttpHandler handler) {
        for (ApiVersion version : ApiVersion.values()) {
            registerRoute(version, method, path, handler);
        }
        return this;
    }

    /**
     * 设置404处理器
     *
     * @param handler 处理器
     * @return 版本化路由器实例
     */
    public VersionedRouter onNotFound(com.memoryplatform.server.HttpHandler handler) {
        this.notFoundHandler = handler;
        return this;
    }

    /**
     * 设置默认版本
     *
     * @param version 默认版本
     * @return 版本化路由器实例
     */
    public VersionedRouter setDefaultVersion(ApiVersion version) {
        this.defaultVersion = version;
        System.out.println("[VersionedRouter] 默认版本设置为: " + version);
        return this;
    }

    /**
     * 启用/禁用版本降级
     *
     * @param enabled 是否启用
     * @return 版本化路由器实例
     */
    public VersionedRouter setFallbackEnabled(boolean enabled) {
        this.fallbackEnabled = enabled;
        System.out.println("[VersionedRouter] 版本降级: " + (enabled ? "启用" : "禁用"));
        return this;
    }

    /**
     * 处理HTTP请求
     * <p>
     * 流程:
     * <ol>
     *   <li>检测请求的API版本</li>
     *   <li>在对应版本的路由器中查找路由</li>
     *   <li>如果未找到且启用降级，尝试更早的版本</li>
     *   <li>设置版本响应头</li>
     * </ol>
     * </p>
     *
     * @param exchange HTTP交换对象
     */
    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String acceptHeader = exchange.getRequestHeaders().getFirst("Accept");
        String queryVersion = getQueryParam(exchange, "api_version");

        // 1. 检测版本
        ApiVersion detectedVersion = ApiVersion.detect(path, acceptHeader, queryVersion);

        System.out.println("[VersionedRouter] 请求: " + exchange.getRequestMethod() + " " + path
                + " → 版本: " + detectedVersion);

        // 2. 尝试在检测到的版本中查找路由
        Router router = versionRouters.get(detectedVersion);
        if (router != null) {
            // 设置版本响应头
            setVersionHeaders(exchange, detectedVersion);

            // 委托给对应版本的路由器处理
            router.handle(exchange);
            return;
        }

        // 3. 版本降级：尝试更早的版本
        if (fallbackEnabled) {
            for (ApiVersion v : ApiVersion.getVersionsFrom(ApiVersion.V1)) {
                if (v.getOrder() <= detectedVersion.getOrder()) {
                    Router fallbackRouter = versionRouters.get(v);
                    if (fallbackRouter != null) {
                        System.out.println("[VersionedRouter] 版本降级: " + detectedVersion + " → " + v);
                        setVersionHeaders(exchange, v);
                        fallbackRouter.handle(exchange);
                        return;
                    }
                }
            }
        }

        // 4. 404处理
        handleNotFound(exchange, path);
    }

    /**
     * 设置版本相关响应头
     *
     * @param exchange HTTP交换对象
     * @param version  API版本
     */
    private void setVersionHeaders(HttpExchange exchange, ApiVersion version) {
        exchange.getResponseHeaders().set("X-API-Version", version.getPrefix());
        exchange.getResponseHeaders().set("X-API-Version-Info", version.getSemanticVersion());
        exchange.getResponseHeaders().set("X-API-Supported-Versions", "v1, v2");
    }

    /**
     * 处理404未找到
     */
    private void handleNotFound(HttpExchange exchange, String path) {
        try {
            if (notFoundHandler != null) {
                notFoundHandler.handle(exchange, new HashMap<>());
            } else {
                String response = String.format(
                    "{\"success\":false,\"error\":{\"code\":404,\"message\":\"Not Found: %s\",\"versions\":[\"v1\",\"v2\"]}}",
                    path
                );
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-API-Supported-Versions", "v1, v2");
                exchange.sendResponseHeaders(404, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            }
        } catch (Exception e) {
            System.err.println("[VersionedRouter] 处理404错误失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有版本的路由统计信息
     *
     * @return 版本路由统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("defaultVersion", defaultVersion.getPrefix());
        stats.put("fallbackEnabled", fallbackEnabled);
        stats.put("supportedVersions", ApiVersion.getAllVersions().size());

        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        int totalRoutes = 0;
        for (Map.Entry<ApiVersion, Router> entry : versionRouters.entrySet()) {
            int count = 0;
            for (var methodRoutes : entry.getValue().getRoutes().values()) {
                count += methodRoutes.size();
            }
            routeCounts.put(entry.getKey().getPrefix(), count);
            totalRoutes += count;
        }
        stats.put("routeCounts", routeCounts);
        stats.put("totalRoutes", totalRoutes);

        return stats;
    }

    /**
     * 打印所有版本的路由信息
     */
    public void printAllRoutes() {
        System.out.println("\n╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║           Versioned API Routes                            ║");
        System.out.println("╠═══════════════════════════════════════════════════════════╣");

        for (ApiVersion version : ApiVersion.getAllVersions()) {
            Router router = versionRouters.get(version);
            if (router == null) continue;

            System.out.printf("║  [%s] %-50s║%n", version.getPrefix(), version.getSemanticVersion());

            for (Map.Entry<String, List<Router.Route>> entry : router.getRoutes().entrySet()) {
                for (Router.Route route : entry.getValue()) {
                    System.out.printf("║    %-7s %-46s║%n", route.getMethod(), route.getPath());
                }
            }

            int routeCount = 0;
            for (var methodRoutes : router.getRoutes().values()) {
                routeCount += methodRoutes.size();
            }
            System.out.printf("║    共 %d 条路由%42s║%n", routeCount, "");
            System.out.println("║                                                           ║");
        }

        System.out.println("╚═══════════════════════════════════════════════════════════╝\n");
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 路径转换：将路径添加版本前缀
     *
     * @param path    原始路径
     * @param version 目标版本
     * @return 转换后的路径
     */
    private String convertPathForVersion(String path, ApiVersion version) {
        if (path == null) return null;

        String normalizedPath = normalizePath(path);

        // 如果已经包含版本前缀，替换它
        for (ApiVersion v : ApiVersion.values()) {
            String oldPrefix = v.getApiPathPrefix();
            if (normalizedPath.startsWith(oldPrefix + "/") || normalizedPath.equals(oldPrefix)) {
                return version.getApiPathPrefix() + normalizedPath.substring(oldPrefix.length());
            }
        }

        // 如果路径以 /api/ 开头但没有版本前缀，添加版本前缀
        if (normalizedPath.startsWith("/api/")) {
            return "/api/" + version.getPrefix() + normalizedPath.substring(4);
        }

        // 其他路径，直接添加版本前缀
        return version.getApiPathPrefix() + normalizedPath;
    }

    /**
     * 规范化路径
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 包装处理器，添加版本信息
     */
    private com.memoryplatform.server.HttpHandler wrapWithVersion(
            com.memoryplatform.server.HttpHandler handler, ApiVersion version) {
        return (exchange, pathParams) -> {
            // 设置版本信息到exchange属性中
            exchange.setAttribute("apiVersion", version);
            exchange.setAttribute("apiVersionPrefix", version.getPrefix());
            handler.handle(exchange, pathParams);
        };
    }

    /**
     * 向路由器添加路由
     */
    private void addRouteToRouter(Router router, String method, String path,
                                   com.memoryplatform.server.HttpHandler handler) {
        switch (method.toUpperCase()) {
            case "GET": router.get(path, handler); break;
            case "POST": router.post(path, handler); break;
            case "PUT": router.put(path, handler); break;
            case "DELETE": router.delete(path, handler); break;
            case "PATCH": router.patch(path, handler); break;
            default:
                System.err.println("[VersionedRouter] 不支持的HTTP方法: " + method);
        }
    }

    /**
     * 从exchange获取查询参数
     */
    private String getQueryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
