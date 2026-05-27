package com.memoryplatform.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * CORS中间件
 * <p>
 * 处理跨域资源共享（CORS）请求。支持预检请求（OPTIONS）处理和自定义配置。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
@Slf4j
public class CorsMiddleware implements Middleware {
    
    private final Set<String> allowedOrigins;
    private final Set<String> allowedMethods;
    private final Set<String> allowedHeaders;
    private final boolean allowCredentials;
    private final int maxAge;
    
    /**
     * 创建CORS中间件（默认配置：允许所有源）
     */
    public CorsMiddleware() {
        this.allowedOrigins = new HashSet<>(Arrays.asList("*"));
        this.allowedMethods = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        this.allowedHeaders = new HashSet<>(Arrays.asList("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"));
        this.allowCredentials = false;
        this.maxAge = 3600;
    }
    
    public CorsMiddleware(Builder builder) {
        this.allowedOrigins = new HashSet<>(builder.allowedOrigins);
        this.allowedMethods = new HashSet<>(builder.allowedMethods);
        this.allowedHeaders = new HashSet<>(builder.allowedHeaders);
        this.allowCredentials = builder.allowCredentials;
        this.maxAge = builder.maxAge;
    }
    
    @Override
    public boolean handle(HttpExchange exchange, Runnable next) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String method = exchange.getRequestMethod().toUpperCase();
        
        addCorsHeaders(exchange, origin);
        
        // 处理预检请求
        if ("OPTIONS".equals(method)) {
            handlePreflight(exchange);
            return false;
        }
        
        next.run();
        return true;
    }
    
    private void addCorsHeaders(HttpExchange exchange, String origin) {
        if (allowedOrigins.contains("*")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        } else if (origin != null && allowedOrigins.contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        }
        
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
        
        if (allowCredentials) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        
        exchange.getResponseHeaders().set("Access-Control-Max-Age", String.valueOf(maxAge));
    }
    
    private void handlePreflight(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        } catch (IOException e) {
            log.error("[CorsMiddleware] 处理预检请求失败: " + e.getMessage());
        }
    }
    
    @Override
    public String getName() { return "CorsMiddleware"; }
    
    @Override
    public int getPriority() { return 20; }
    
    /**
     * CORS中间件构建器
     */
    public static class Builder {
        private Set<String> allowedOrigins = new HashSet<>(Arrays.asList("*"));
        private Set<String> allowedMethods = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        private Set<String> allowedHeaders = new HashSet<>(Arrays.asList("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin"));
        private boolean allowCredentials = false;
        private int maxAge = 3600;
        
        public Builder allowedOrigins(String... origins) {
            this.allowedOrigins = new HashSet<>(Arrays.asList(origins));
            return this;
        }
        
        public Builder allowedMethods(String... methods) {
            this.allowedMethods = new HashSet<>(Arrays.asList(methods));
            return this;
        }
        
        public Builder allowedHeaders(String... headers) {
            this.allowedHeaders = new HashSet<>(Arrays.asList(headers));
            return this;
        }
        
        public Builder allowCredentials(boolean allow) {
            this.allowCredentials = allow;
            return this;
        }
        
        public Builder maxAge(int maxAge) {
            this.maxAge = maxAge;
            return this;
        }
        
        public CorsMiddleware build() {
            return new CorsMiddleware(this);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
