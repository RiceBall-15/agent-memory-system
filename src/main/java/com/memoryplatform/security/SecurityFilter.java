package com.memoryplatform.security;

import com.google.gson.Gson;
import com.memoryplatform.server.Middleware;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * 安全过滤器中间件
 * <p>
 * 提供以下安全功能：
 * <ul>
 *   <li>安全响应头注入（X-Content-Type-Options, X-Frame-Options, X-XSS-Protection）</li>
 *   <li>请求日志记录（IP、方法、路径、响应码、耗时）</li>
 *   <li>全局异常处理（捕获未处理异常，返回500）</li>
 *   <li>可选的令牌限流</li>
 * </ul>
 * </p>
 * <p>
 * 优先级设置为1（最早执行），确保安全头在所有响应中都存在。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
@Slf4j
public class SecurityFilter implements Middleware {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Gson GSON = new Gson();

    /** 是否启用限流 */
    private final boolean rateLimitEnabled;

    /** 限流器（可选） */
    private final RateLimiter rateLimiter;

    /**
     * 创建安全过滤器（默认配置，不启用限流）
     */
    public SecurityFilter() {
        this(false, null);
    }

    /**
     * 创建安全过滤器（可选启用限流）
     *
     * @param rateLimitEnabled 是否启用限流
     */
    public SecurityFilter(boolean rateLimitEnabled) {
        this(rateLimitEnabled, rateLimitEnabled ? new RateLimiter() : null);
    }

    /**
     * 创建安全过滤器（自定义限流器）
     *
     * @param rateLimitEnabled 是否启用限流
     * @param rateLimiter 自定义限流器实例
     */
    public SecurityFilter(boolean rateLimitEnabled, RateLimiter rateLimiter) {
        this.rateLimitEnabled = rateLimitEnabled;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean handle(HttpExchange exchange, Runnable next) {
        String clientIp = getClientIp(exchange);
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();

        long startTime = System.currentTimeMillis();

        // 1. 注入安全响应头
        addSecurityHeaders(exchange);

        // 2. 限流检查
        if (rateLimitEnabled && rateLimiter != null) {
            if (!rateLimiter.tryAcquire(clientIp)) {
                sendRateLimitResponse(exchange, clientIp);
                logRequest(clientIp, method, path, query, 429, System.currentTimeMillis() - startTime);
                return false;
            }
        }

        // 3. 执行后续处理器链，捕获异常
        try {
            next.run();
        } catch (Exception e) {
            log.error("[SecurityFilter] 未处理异常: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(exchange, e);
        }

        // 4. 记录请求日志
        long duration = System.currentTimeMillis() - startTime;
        int statusCode = exchange.getResponseCode();
        logRequest(clientIp, method, path, query, statusCode, duration);

        return true;
    }

    /**
     * 注入安全响应头
     */
    private void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp(HttpExchange exchange) {
        // 优先从X-Forwarded-For获取（反向代理场景）
        String xForwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // 取第一个IP（最原始的客户端）
            String[] ips = xForwardedFor.split(",");
            return ips[0].trim();
        }

        // 从Remote-Addr获取
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }

        return "unknown";
    }

    /**
     * 发送限流响应（429 Too Many Requests）
     */
    private void sendRateLimitResponse(HttpExchange exchange, String clientIp) {
        log.error("[SecurityFilter] 限流触发 - IP: " + clientIp)

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);

        Map<String, Object> error = new HashMap<>();
        error.put("code", 429);
        error.put("message", "Too Many Requests - 请求过于频繁，请稍后再试");
        error.put("retryAfter", 1); // 建议1秒后重试
        response.put("error", error);

        try {
            String json = GSON.toJson(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Retry-After", "1");
            exchange.sendResponseHeaders(429, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("[SecurityFilter] 发送限流响应失败: " + e.getMessage());
        }
    }

    /**
     * 发送500错误响应
     */
    private void sendErrorResponse(HttpExchange exchange, Exception e) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);

        Map<String, Object> error = new HashMap<>();
        error.put("code", 500);
        error.put("message", "Internal Server Error - 服务器内部错误");
        // 不向客户端暴露详细异常信息
        response.put("error", error);

        try {
            String json = GSON.toJson(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ex) {
            log.error("[SecurityFilter] 发送错误响应失败: " + ex.getMessage());
        }
    }

    /**
     * 记录请求日志
     */
    private void logRequest(String ip, String method, String path, String query, int statusCode, long duration) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append(LocalDateTime.now().format(FORMATTER))
                  .append(" | ")
                  .append(method).append(" ").append(path);

        if (query != null && !query.isEmpty()) {
            logMessage.append("?").append(query);
        }

        logMessage.append(" | IP: ").append(ip)
                  .append(" | Status: ").append(statusCode)
                  .append(" | Duration: ").append(duration).append("ms");

        // 根据状态码选择颜色标记
        String level;
        if (statusCode >= 200 && statusCode < 400) {
            level = "INFO";
        } else if (statusCode == 429) {
            level = "WARN";
        } else if (statusCode >= 400 && statusCode < 500) {
            level = "WARN";
        } else {
            level = "ERROR";
        }

        log.info("[" + level + "] [SecurityFilter] " + logMessage.toString())
    }

    @Override
    public String getName() {
        return "SecurityFilter";
    }

    @Override
    public int getPriority() {
        return 1; // 最高优先级，最早执行
    }

    /**
     * 获取限流器实例
     */
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
