package com.memoryplatform.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
/**
 * 路由管理器
 * <p>
 * 轻量级HTTP路由框架的核心组件。支持：
 * <ul>
 *   <li>RESTful风格路由：GET, POST, PUT, DELETE, PATCH</li>
 *   <li>路径参数：/api/memories/{id}</li>
 *   <li>通配符匹配：/api/*/details</li>
 *   <li>中间件链</li>
 *   <li>路由分组</li>
 * </ul>
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 * @see HttpHandler
 * @see Middleware
 */
@Slf4j
public class Router {
    
    /** 所属API版本（由VersionedRouter设置） */
    private ApiVersion apiVersion;

    /** 路由注册表，按HTTP方法组织 */
    private final Map<String, List<Route>> routes = new ConcurrentHashMap<>();
    
    /** 中间件列表 */
    private final List<Middleware> middlewares = new CopyOnWriteArrayList<>();
    
    /** 路由分组列表 */
    private final List<Group> groups = new CopyOnWriteArrayList<>();
    
    /** 路径参数正则模式缓存 */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();
    
    /** 404处理器 */
    private HttpHandler notFoundHandler;
    
    /** 405处理器 */
    private HttpHandler methodNotAllowedHandler;
    
    /**
     * 注册GET路由
     * 
     * @param path 路径模式，支持 {param} 参数
     * @param handler 处理器
     * @return 路由管理器实例（支持链式调用）
     */
    public Router get(String path, HttpHandler handler) {
        return addRoute("GET", path, handler);
    }
    
    /**
     * 注册GET路由（带版本前缀自动添加）
     * <p>
     * 如果路由器绑定了API版本，路径会自动添加版本前缀。
     * </p>
     *
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router versionedGet(String path, HttpHandler handler) {
        return addRoute("GET", applyVersionPrefix(path), handler);
    }

    /**
     * 注册POST路由（带版本前缀自动添加）
     */
    public Router versionedPost(String path, HttpHandler handler) {
        return addRoute("POST", applyVersionPrefix(path), handler);
    }

    /**
     * 注册PUT路由（带版本前缀自动添加）
     */
    public Router versionedPut(String path, HttpHandler handler) {
        return addRoute("PUT", applyVersionPrefix(path), handler);
    }

    /**
     * 注册DELETE路由（带版本前缀自动添加）
     */
    public Router versionedDelete(String path, HttpHandler handler) {
        return addRoute("DELETE", applyVersionPrefix(path), handler);
    }

    /**
     * 注册PATCH路由（带版本前缀自动添加）
     */
    public Router versionedPatch(String path, HttpHandler handler) {
        return addRoute("PATCH", applyVersionPrefix(path), handler);
    }

    /**
     * 绑定API版本
     *
     * @param version API版本
     * @return 路由管理器实例
     */
    public Router bindVersion(ApiVersion version) {
        this.apiVersion = version;
        log.info("[Router] 绑定API版本: " + version)
        return this;
    }

    /**
     * 获取绑定的API版本
     *
     * @return API版本，未绑定返回null
     */
    public ApiVersion getApiVersion() {
        return apiVersion;
    }

    /**
     * 应用版本前缀到路径
     * <p>
     * 如果路由器绑定了API版本且路径尚未包含版本前缀，则自动添加。
     * </p>
     *
     * @param path 原始路径
     * @return 带版本前缀的路径
     */
    private String applyVersionPrefix(String path) {
        if (apiVersion == null) return path;

        String normalized = normalizePath(path);
        String prefix = apiVersion.getApiPathPrefix();

        // 如果路径已经包含版本前缀，不重复添加
        if (normalized.startsWith(prefix + "/") || normalized.equals(prefix)) {
            return normalized;
        }

        // 如果路径以 /api/ 开头，在 /api/ 后插入版本
        if (normalized.startsWith("/api/")) {
            return "/api/" + apiVersion.getPrefix() + normalized.substring(4);
        }

        // 其他路径，直接添加版本前缀
        return prefix + normalized;
    }

    /**
     * 添加中间件
    /**
     * 注册POST路由
     * 
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router post(String path, HttpHandler handler) {
        return addRoute("POST", path, handler);
    }
    
    /**
     * 注册PUT路由
     * 
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router put(String path, HttpHandler handler) {
        return addRoute("PUT", path, handler);
    }
    
    /**
     * 注册DELETE路由
     * 
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router delete(String path, HttpHandler handler) {
        return addRoute("DELETE", path, handler);
    }
    
    /**
     * 注册PATCH路由
     * 
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router patch(String path, HttpHandler handler) {
        return addRoute("PATCH", path, handler);
    }
    
    /**
     * 注册支持所有HTTP方法的路由
     * 
     * @param path 路径模式
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router any(String path, HttpHandler handler) {
        get(path, handler);
        post(path, handler);
        put(path, handler);
        delete(path, handler);
        patch(path, handler);
        return this;
    }
    
    /**
     * 添加路由
     */
    private Router addRoute(String method, String path, HttpHandler handler) {
        String normalizedPath = normalizePath(path);
        Route route = new Route(method, normalizedPath, handler);
        
        routes.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(route);
        
        // 预编译路径模式
        if (normalizedPath.contains("{") || normalizedPath.contains("*")) {
            patternCache.computeIfAbsent(normalizedPath, this::compilePattern);
        }
        
        log.info("[Router] 注册路由: " + method + " " + normalizedPath)
        
        return this;
    }
    
    /**
     * 添加中间件
     * 
     * @param middleware 中间件实例
     * @return 路由管理器实例
     */
    public Router addMiddleware(Middleware middleware) {
        middlewares.add(middleware);
        // 按优先级排序
        middlewares.sort(Comparator.comparingInt(Middleware::getPriority));
        log.info("[Router] 添加中间件: " + middleware.getName() + " (优先级: " + middleware.getPriority() + ")")
        return this;
    }
    
    /**
     * 移除中间件
     * 
     * @param middleware 中间件实例
     * @return 路由管理器实例
     */
    public Router removeMiddleware(Middleware middleware) {
        middlewares.remove(middleware);
        log.info("[Router] 移除中间件: " + middleware.getName())
        return this;
    }
    
    /**
     * 创建路由分组
     * <p>
     * 路由分组允许为一组路由设置公共前缀。
     * </p>
     * 
     * @param prefix 分组前缀
     * @return 路由分组实例
     */
    public Group createGroup(String prefix) {
        Group group = new Group(prefix);
        groups.add(group);
        log.info("[Router] 创建路由分组: " + prefix)
        return group;
    }
    
    /**
     * 设置404未找到处理器
     * 
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router onNotFound(HttpHandler handler) {
        this.notFoundHandler = handler;
        return this;
    }
    
    /**
     * 设置405方法不允许处理器
     * 
     * @param handler 处理器
     * @return 路由管理器实例
     */
    public Router onMethodNotAllowed(HttpHandler handler) {
        this.methodNotAllowedHandler = handler;
        return this;
    }
    
    /**
     * 处理HTTP请求
     * <p>
     * 由MemoryHttpServer调用，执行中间件链和路由匹配。
     * </p>
     * 
     * @param exchange HTTP交换对象
     */
    public void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();
        
        log.info("[Router] 收到请求: " + method + " " + path)
        
        // 执行中间件链
        executeMiddlewareChain(exchange, () -> {
            // 查找匹配的路由
            RouteMatch match = findRoute(method, path);
            
            if (match == null) {
                if (hasRouteForPath(path)) {
                    handleMethodNotAllowed(exchange);
                } else {
                    handleNotFound(exchange);
                }
                return;
            }
            
            // 执行处理器
            try {
                log.info("[Router] 匹配路由: " + match.route.getMethod() + " " + match.route.getPath() + " -> " + path)
                match.route.getHandler().handle(exchange, match.pathParams);
            } catch (Exception e) {
                log.error("[Router] 处理请求异常: " + e.getMessage());
                e.printStackTrace();
                handleServerError(exchange, e);
            }
        });
    }
    
    /**
     * 执行中间件链
     */
    private void executeMiddlewareChain(HttpExchange exchange, Runnable finalAction) {
        Runnable chain = finalAction;
        
        // 从后向前构建链
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            Middleware middleware = middlewares.get(i);
            Runnable currentChain = chain;
            chain = () -> {
                try {
                    middleware.handle(exchange, currentChain);
                } catch (Exception e) {
                    log.error("[Router] 中间件执行异常 [" + middleware.getName() + "]: " + e.getMessage());
                    e.printStackTrace();
                    currentChain.run();
                }
            };
        }
        
        chain.run();
    }
    
    /**
     * 查找匹配的路由
     */
    private RouteMatch findRoute(String method, String path) {
        // 首先检查当前路由
        List<Route> methodRoutes = routes.get(method);
        if (methodRoutes != null) {
            for (Route route : methodRoutes) {
                Map<String, String> pathParams = matchPath(route.getPath(), path);
                if (pathParams != null) {
                    return new RouteMatch(route, pathParams);
                }
            }
        }
        
        // 检查分组路由
        for (Group group : groups) {
            RouteMatch match = group.findRoute(method, path);
            if (match != null) {
                return match;
            }
        }
        
        return null;
    }
    
    /**
     * 检查路径是否有任何方法的路由
     */
    private boolean hasRouteForPath(String path) {
        for (List<Route> methodRoutes : routes.values()) {
            for (Route route : methodRoutes) {
                Map<String, String> pathParams = matchPath(route.getPath(), path);
                if (pathParams != null) {
                    return true;
                }
            }
        }
        
        for (Group group : groups) {
            if (group.hasRouteForPath(path)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 路径匹配
     * <p>
     * 支持精确匹配、路径参数和通配符。
     * </p>
     * 
     * @param pattern 路由模式
     * @param path 实际路径
     * @return 路径参数映射，不匹配返回null
     */
    private Map<String, String> matchPath(String pattern, String path) {
        // 精确匹配
        if (pattern.equals(path)) {
            return new HashMap<>();
        }
        
        // 获取或编译正则模式
        Pattern regex = patternCache.computeIfAbsent(pattern, this::compilePattern);
        Matcher matcher = regex.matcher(path);
        
        if (!matcher.matches()) {
            return null;
        }
        
        // 提取路径参数
        Map<String, String> pathParams = new HashMap<>();
        List<String> paramNames = extractParamNames(pattern);
        
        for (int i = 0; i < paramNames.size(); i++) {
            String value = matcher.group(i + 1);
            if (value != null) {
                pathParams.put(paramNames.get(i), value);
            }
        }
        
        return pathParams;
    }
    
    /**
     * 编译路径模式为正则表达式
     * <p>
     * 转换规则：
     * <ul>
     *   <li>{param} -> ([^/]+)  匹配非斜杠字符</li>
     *   <li>* -> ([^/]+)  匹配单个路径段</li>
     *   <li>** -> (.*)  匹配任意字符</li>
     * </ul>
     * </p>
     */
    private Pattern compilePattern(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        
        String[] parts = pattern.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            
            if (i > 0) {
                regex.append("/");
            }
            
            if (part.startsWith("{") && part.endsWith("}")) {
                // 路径参数
                regex.append("([^/]+)");
            } else if (part.equals("**")) {
                // 通配符（贪婪）
                regex.append("(.*)");
            } else if (part.equals("*")) {
                // 通配符（非贪婪）
                regex.append("([^/]+)");
            } else {
                // 精确匹配
                regex.append(Pattern.quote(part));
            }
        }
        
        regex.append("$");
        
        return Pattern.compile(regex.toString());
    }
    
    /**
     * 提取路径参数名称
     */
    private List<String> extractParamNames(String pattern) {
        List<String> paramNames = new ArrayList<>();
        String[] parts = pattern.split("/", -1);
        
        for (String part : parts) {
            if (part.startsWith("{") && part.endsWith("}")) {
                paramNames.add(part.substring(1, part.length() - 1));
            } else if (part.equals("*") || part.equals("**")) {
                paramNames.add("*");
            }
        }
        
        return paramNames;
    }
    
    /**
     * 规范化路径
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        
        // 确保以/开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        // 移除末尾的/（根路径除外）
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        
        return path;
    }
    
    /**
     * 处理404未找到
     */
    private void handleNotFound(HttpExchange exchange) {
        try {
            if (notFoundHandler != null) {
                notFoundHandler.handle(exchange, new HashMap<>());
            } else {
                String path = exchange.getRequestURI().getPath();
                String response = String.format(
                    "{\"success\":false,\"error\":{\"code\":404,\"message\":\"Not Found: %s\"}}",
                    path
                );
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            }
        } catch (Exception e) {
            log.error("[Router] 处理404错误失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理405方法不允许
     */
    private void handleMethodNotAllowed(HttpExchange exchange) {
        try {
            if (methodNotAllowedHandler != null) {
                methodNotAllowedHandler.handle(exchange, new HashMap<>());
            } else {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                String response = String.format(
                    "{\"success\":false,\"error\":{\"code\":405,\"message\":\"Method Not Allowed: %s %s\"}}",
                    method, path
                );
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Allow", getAllowedMethods(path));
                exchange.sendResponseHeaders(405, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            }
        } catch (Exception e) {
            log.error("[Router] 处理405错误失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定路径支持的所有方法
     */
    private String getAllowedMethods(String path) {
        Set<String> methods = new HashSet<>();
        
        for (Map.Entry<String, List<Route>> entry : routes.entrySet()) {
            for (Route route : entry.getValue()) {
                Map<String, String> pathParams = matchPath(route.getPath(), path);
                if (pathParams != null) {
                    methods.add(entry.getKey());
                }
            }
        }
        
        return String.join(", ", methods);
    }
    
    /**
     * 处理服务器错误
     */
    private void handleServerError(HttpExchange exchange, Exception e) {
        try {
            String response = String.format(
                "{\"success\":false,\"error\":{\"code\":500,\"message\":\"Internal Server Error: %s\"}}",
                e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error"
            );
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        } catch (Exception ex) {
            log.error("[Router] 处理500错误失败: " + ex.getMessage());
        }
    }
    
    /**
     * 获取注册的所有路由
     * 
     * @return 路由映射
     */
    public Map<String, List<Route>> getRoutes() {
        return Collections.unmodifiableMap(routes);
    }
    
    /**
     * 获取所有中间件
     * 
     * @return 中间件列表
     */
    public List<Middleware> getMiddlewares() {
        return Collections.unmodifiableList(middlewares);
    }
    
    /**
     * 路由定义
     */
    public static class Route {
        private final String method;
        private final String path;
        private final HttpHandler handler;
        
        public Route(String method, String path, HttpHandler handler) {
            this.method = method;
            this.path = path;
            this.handler = handler;
        }
        
        public String getMethod() { return method; }
        public String getPath() { return path; }
        public HttpHandler getHandler() { return handler; }
    }
    
    /**
     * 路由匹配结果
     */
    public static class RouteMatch {
        private final Route route;
        private final Map<String, String> pathParams;
        
        public RouteMatch(Route route, Map<String, String> pathParams) {
            this.route = route;
            this.pathParams = pathParams;
        }
        
        public Route getRoute() { return route; }
        public Map<String, String> getPathParams() { return pathParams; }
    }
    
    /**
     * 路由分组
     * <p>
     * 允许为一组路由设置公共前缀。
     * </p>
     */
    public class Group {
        private final String prefix;
        private final List<Middleware> groupMiddlewares = new CopyOnWriteArrayList<>();
        
        Group(String prefix) {
            this.prefix = normalizePath(prefix);
        }
        
        public Group get(String path, HttpHandler handler) {
            addRoute("GET", path, handler);
            return this;
        }
        
        public Group post(String path, HttpHandler handler) {
            addRoute("POST", path, handler);
            return this;
        }
        
        public Group put(String path, HttpHandler handler) {
            addRoute("PUT", path, handler);
            return this;
        }
        
        public Group delete(String path, HttpHandler handler) {
            addRoute("DELETE", path, handler);
            return this;
        }
        
        public Group patch(String path, HttpHandler handler) {
            addRoute("PATCH", path, handler);
            return this;
        }
        
        /**
         * 添加分组中间件
         * 
         * @param middleware 中间件
         * @return 分组实例
         */
        public Group addMiddleware(Middleware middleware) {
            groupMiddlewares.add(middleware);
            return this;
        }
        
        private void addRoute(String method, String path, HttpHandler handler) {
            String fullPath = prefix + normalizePath(path);
            Router.this.addRoute(method, fullPath, handler);
        }
        
        /**
         * 查找匹配的路由
         */
        RouteMatch findRoute(String method, String path) {
            List<Route> methodRoutes = routes.get(method);
            if (methodRoutes == null) {
                return null;
            }
            
            for (Route route : methodRoutes) {
                if (route.getPath().startsWith(prefix)) {
                    Map<String, String> pathParams = matchPath(route.getPath(), path);
                    if (pathParams != null) {
                        return new RouteMatch(route, pathParams);
                    }
                }
            }
            
            return null;
        }
        
        /**
         * 检查路径是否有路由
         */
        boolean hasRouteForPath(String path) {
            for (List<Route> methodRoutes : routes.values()) {
                for (Route route : methodRoutes) {
                    if (route.getPath().startsWith(prefix)) {
                        Map<String, String> pathParams = matchPath(route.getPath(), path);
                        if (pathParams != null) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        
        /**
         * 获取分组前缀
         */
        public String getPrefix() {
            return prefix;
        }
    }
}
