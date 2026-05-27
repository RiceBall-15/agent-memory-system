package com.memoryplatform.server;

import com.sun.net.httpserver.HttpExchange;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.extern.slf4j.Slf4j;
/**
 * 日志中间件
 * <p>
 * 记录所有HTTP请求的日志信息，包括请求时间、方法、路径、IP、User-Agent、响应状态码和处理耗时。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
@Slf4j
public class LoggingMiddleware implements Middleware {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    /** 是否记录详细的请求头信息 */
    private final boolean verbose;
    
    /**
     * 创建日志中间件（默认不记录详细信息）
     */
    public LoggingMiddleware() {
        this(false);
    }
    
    /**
     * 创建日志中间件
     * 
     * @param verbose 是否记录详细信息
     */
    public LoggingMiddleware(boolean verbose) {
        this.verbose = verbose;
    }
    
    @Override
    public boolean handle(HttpExchange exchange, Runnable next) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        
        long startTime = System.currentTimeMillis();
        
        // 构建请求日志
        StringBuilder logMessage = new StringBuilder();
        logMessage.append(timestamp).append(" | ")
                  .append(method).append(" ").append(path);
        
        if (query != null && !query.isEmpty()) {
            logMessage.append("?").append(query);
        }
        
        logMessage.append(" | IP: ").append(clientIp);
        
        if (userAgent != null) {
            logMessage.append(" | ").append(userAgent);
        }
        
        if (verbose) {
            logMessage.append("\n  Headers: ").append(exchange.getRequestHeaders());
        }
        
        log.info("[REQUEST] " + logMessage.toString())
        
        // 继续执行处理器链
        next.run();
        
        // 记录响应日志
        long duration = System.currentTimeMillis() - startTime;
        int statusCode = exchange.getResponseCode();
        String statusColor = getStatusColor(statusCode);
        
        log.info(String.format(
            "[RESPONSE] %s %s -> %s%d%s (%dms)",
            method, path, statusColor, statusCode, "\u001B[0m", duration
        ))
        
        return true;
    }
    
    private String getStatusColor(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "\u001B[32m"; // 绿色
        if (statusCode >= 300 && statusCode < 400) return "\u001B[33m"; // 黄色
        if (statusCode >= 400 && statusCode < 500) return "\u001B[36m"; // 青色
        if (statusCode >= 500) return "\u001B[31m"; // 红色
        return "";
    }
    
    @Override
    public String getName() { return "LoggingMiddleware"; }
    
    @Override
    public int getPriority() { return 10; }
}
