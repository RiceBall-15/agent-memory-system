package com.memoryplatform.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全配置类
 * <p>
 * 注册请求过滤器，提供：
 * <ul>
 *   <li>限流保护（令牌桶算法）</li>
 *   <li>安全响应头（X-Content-Type-Options, X-Frame-Options等）</li>
 *   <li>请求日志记录</li>
 *   <li>CORS预检处理</li>
 * </ul>
 * </p>
 * <p>
 * 过滤器执行顺序：
 * <pre>
 * 请求 → RateLimitFilter → SecurityHeadersFilter → Controller → SecurityHeadersFilter → 响应
 * </pre>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Value("${app.security.rate-limit.per-second:10}")
    private int rateLimitPerSecond;

    @Value("${app.security.rate-limit.burst:20}")
    private int rateLimitBurst;

    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${app.admin.token:changeme}")
    private String adminToken;

    /**
     * 限流+安全头过滤器
     * <p>
     * 合并原 {@link com.memoryplatform.security.SecurityFilter} 的功能，
     * 适配Spring Boot Filter体系。
     * </p>
     */
    @Bean
    public FilterRegistrationBean<Filter> securityFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SecurityRequestFilter(rateLimitPerSecond, rateLimitBurst));
        registration.addUrlPatterns("/*");
        registration.setName("securityFilter");
        registration.setOrder(1);
        return registration;
    }

    /**
     * Spring内部过滤器实现
     * <p>
     * 功能等同于原 {@link com.memoryplatform.security.SecurityFilter}
     * 的中间件逻辑，但适配Spring Boot Filter API。
     * </p>
     */
    static class SecurityRequestFilter implements Filter {

        private final int rateLimitPerSecond;
        private final int rateLimitBurst;

        // 简易令牌桶：per-IP限流
        private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

        SecurityRequestFilter(int rateLimitPerSecond, int rateLimitBurst) {
            this.rateLimitPerSecond = rateLimitPerSecond;
            this.rateLimitBurst = rateLimitBurst;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            String clientIp = getClientIp(httpRequest);
            String method = httpRequest.getMethod();
            String uri = httpRequest.getRequestURI();

            // --- 0. Actuator端点放行 ---
            if (uri.startsWith("/actuator")) {
                chain.doFilter(request, response);
                return;
            }
            // --- 1. CORS预检处理 ---
            if ("OPTIONS".equalsIgnoreCase(method)) {
                setSecurityHeaders(httpResponse);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                return;
            }

            // --- 2. 限流检查 ---
            TokenBucket bucket = buckets.computeIfAbsent(clientIp,
                    k -> new TokenBucket(rateLimitPerSecond, rateLimitBurst));

            if (!bucket.tryConsume()) {
                log.warn("[SecurityFilter] 请求被限流: ip={}, uri={}", clientIp, uri);
                httpResponse.setStatus(429);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write(
                        "{\"success\":false,\"message\":\"Rate limit exceeded\",\"error\":{\"code\":429,\"message\":\"Too many requests\"}}");
                return;
            }

            // --- 3. 设置安全响应头 ---
            setSecurityHeaders(httpResponse);

            // --- 4. 记录请求日志 ---
            long startTime = System.currentTimeMillis();
            try {
                chain.doFilter(request, response);
            } finally {
                long elapsed = System.currentTimeMillis() - startTime;
                int status = httpResponse.getStatus();
                if (status >= 400) {
                    log.warn("[SecurityFilter] {} {} {} {}ms ip={}",
                            method, uri, status, elapsed, clientIp);
                } else {
                    log.debug("[SecurityFilter] {} {} {} {}ms ip={}",
                            method, uri, status, elapsed, clientIp);
                }
            }
        }

        /**
         * 设置安全响应头
         */
        private void setSecurityHeaders(HttpServletResponse response) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("X-XSS-Protection", "1; mode=block");
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
        }

        /**
         * 获取真实客户端IP（支持反向代理）
         */
        private String getClientIp(HttpServletRequest request) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.isEmpty()) {
                return xri;
            }
            return request.getRemoteAddr();
        }
    }

    /**
     * 简易令牌桶实现
     * <p>
     * 与原 {@link com.memoryplatform.security.RateLimiter} 逻辑一致。
     * </p>
     */
    static class TokenBucket {
        private final int maxTokens;
        private final double refillRate; // tokens per ms
        private double tokens;
        private long lastRefillTime;

        TokenBucket(int tokensPerSecond, int burst) {
            this.maxTokens = burst;
            this.refillRate = tokensPerSecond / 1000.0;
            this.tokens = burst;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            tokens = Math.min(maxTokens, tokens + elapsed * refillRate);
            lastRefillTime = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
