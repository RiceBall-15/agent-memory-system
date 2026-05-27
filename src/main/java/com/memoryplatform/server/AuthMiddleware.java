package com.memoryplatform.server;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * API Key认证中间件
 * <p>
 * 提供基于API Key的简单认证机制。支持从Header或查询参数获取API Key。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
public class AuthMiddleware implements Middleware {
    
    private final Set<String> validApiKeys;
    private final String headerName;
    private final String queryParamName;
    private final Set<String> excludedPaths;
    
    /**
     * 创建API Key认证中间件
     * 
     * @param apiKey 有效的API Key
     */
    public AuthMiddleware(String apiKey) {
        this.validApiKeys = new HashSet<>();
        this.validApiKeys.add(apiKey);
        this.headerName = "X-API-Key";
        this.queryParamName = "api_key";
        this.excludedPaths = new HashSet<>();
    }
    
    public AuthMiddleware(Builder builder) {
        this.validApiKeys = new HashSet<>(builder.apiKeys);
        this.headerName = builder.headerName;
        this.queryParamName = builder.queryParamName;
        this.excludedPaths = new HashSet<>(builder.excludedPaths);
    }
    
    @Override
    public boolean handle(HttpExchange exchange, Runnable next) {
        String path = exchange.getRequestURI().getPath();
        
        // 检查是否在排除列表中
        for (String excludedPath : excludedPaths) {
            if (path.startsWith(excludedPath)) {
                next.run();
                return true;
            }
        }
        
        // 尝试从Header获取API Key
        String apiKey = exchange.getRequestHeaders().getFirst(headerName);
        
        // 如果Header中没有，尝试从查询参数获取
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = getApiKeyFromQuery(exchange);
        }
        
        // 验证API Key
        if (apiKey == null || apiKey.isEmpty()) {
            sendUnauthorizedResponse(exchange, "Missing API Key");
            return false;
        }
        
        if (!validApiKeys.contains(apiKey)) {
            sendUnauthorizedResponse(exchange, "Invalid API Key");
            return false;
        }
        
        // 认证通过
        System.out.println("[AuthMiddleware] 认证成功 - Path: " + path);
        next.run();
        return true;
    }
    
    private String getApiKeyFromQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equals(queryParamName)) {
                return keyValue[1];
            }
        }
        return null;
    }
    
    private void sendUnauthorizedResponse(HttpExchange exchange, String message) {
        System.out.println("[AuthMiddleware] 认证失败: " + message);
        
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            
            Map<String, Object> error = new HashMap<>();
            error.put("code", 401);
            error.put("message", message);
            response.put("error", error);
            
            Gson gson = new Gson();
            String json = gson.toJson(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("WWW-Authenticate", "ApiKey");
            exchange.sendResponseHeaders(401, bytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            System.err.println("[AuthMiddleware] 发送错误响应失败: " + e.getMessage());
        }
    }
    
    /**
     * 添加有效的API Key
     * 
     * @param apiKey API Key
     */
    public void addApiKey(String apiKey) {
        validApiKeys.add(apiKey);
    }
    
    /**
     * 移除API Key
     * 
     * @param apiKey API Key
     */
    public void removeApiKey(String apiKey) {
        validApiKeys.remove(apiKey);
    }
    
    /**
     * 添加排除路径
     * 
     * @param path 路径前缀
     */
    public void addExcludedPath(String path) {
        excludedPaths.add(path);
    }
    
    @Override
    public String getName() { return "AuthMiddleware"; }
    
    @Override
    public int getPriority() { return 50; }
    
    /**
     * Auth中间件构建器
     */
    public static class Builder {
        private final Set<String> apiKeys = new HashSet<>();
        private String headerName = "X-API-Key";
        private String queryParamName = "api_key";
        private final Set<String> excludedPaths = new HashSet<>();
        
        public Builder apiKey(String apiKey) {
            this.apiKeys.add(apiKey);
            return this;
        }
        
        public Builder apiKeys(Set<String> apiKeys) {
            this.apiKeys.addAll(apiKeys);
            return this;
        }
        
        public Builder headerName(String headerName) {
            this.headerName = headerName;
            return this;
        }
        
        public Builder queryParamName(String queryParamName) {
            this.queryParamName = queryParamName;
            return this;
        }
        
        public Builder excludedPath(String path) {
            this.excludedPaths.add(path);
            return this;
        }
        
        public Builder excludedPaths(String... paths) {
            this.excludedPaths.addAll(Arrays.asList(paths));
            return this;
        }
        
        public AuthMiddleware build() {
            if (apiKeys.isEmpty()) {
                throw new IllegalStateException("至少需要配置一个API Key");
            }
            return new AuthMiddleware(this);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
