package com.memoryplatform.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * HTTP处理器接口
 * <p>
 * 所有路由处理器都需要实现此接口。
 * 提供请求处理和响应的标准化方法。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
@Slf4j
public interface HttpHandler {
    
    /** Gson实例，用于JSON序列化 */
    Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * 处理HTTP请求
     * 
     * @param exchange HTTP交换对象
     * @param pathParams 路径参数映射
     * @throws IOException 如果IO操作失败
     */
    void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException;
    
    /**
     * 从请求中读取请求体
     * 
     * @param exchange HTTP交换对象
     * @return 请求体字符串
     * @throws IOException 如果读取失败
     */
    default String readBody(HttpExchange exchange) throws IOException {
        InputStream inputStream = exchange.getRequestBody();
        byte[] bytes = inputStream.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * 从请求中读取请求体并解析为指定类型的对象
     * 
     * @param exchange HTTP交换对象
     * @param clazz 目标类型
     * @return 解析后的对象
     * @throws IOException 如果读取失败
     */
    default <T> T readBodyAs(HttpExchange exchange, Class<T> clazz) throws IOException {
        String body = readBody(exchange);
        if (body.isEmpty()) {
            return null;
        }
        return GSON.fromJson(body, clazz);
    }
    
    /**
     * 返回JSON响应
     * 
     * @param exchange HTTP交换对象
     * @param status HTTP状态码
     * @param data 要返回的数据对象
     * @throws IOException 如果写入响应失败
     */
    default void jsonResponse(HttpExchange exchange, int status, Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * 返回错误响应
     * 
     * @param exchange HTTP交换对象
     * @param status HTTP状态码
     * @param message 错误消息
     * @throws IOException 如果写入响应失败
     */
    default void errorResponse(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        
        Map<String, Object> error = new HashMap<>();
        error.put("code", status);
        error.put("message", message);
        response.put("error", error);
        
        jsonResponse(exchange, status, response);
    }
    
    /**
     * 返回成功响应
     * 
     * @param exchange HTTP交换对象
     * @param data 返回的数据
     * @throws IOException 如果写入响应失败
     */
    default void successResponse(HttpExchange exchange, Object data) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        
        jsonResponse(exchange, 200, response);
    }
    
    /**
     * 获取请求方法
     * 
     * @param exchange HTTP交换对象
     * @return HTTP方法
     */
    default String getMethod(HttpExchange exchange) {
        return exchange.getRequestMethod().toUpperCase();
    }
    
    /**
     * 获取请求路径
     * 
     * @param exchange HTTP交换对象
     * @return 请求路径
     */
    default String getPath(HttpExchange exchange) {
        return exchange.getRequestURI().getPath();
    }
    
    /**
     * 获取请求头
     * 
     * @param exchange HTTP交换对象
     * @param headerName 头部名称
     * @return 头部值
     */
    default String getHeader(HttpExchange exchange, String headerName) {
        return exchange.getRequestHeaders().getFirst(headerName);
    }
    
    /**
     * 获取查询参数
     * 
     * @param exchange HTTP交换对象
     * @param paramName 参数名
     * @return 参数值
     */
    default String getQueryParam(HttpExchange exchange, String paramName) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                try {
                    return java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return keyValue[1];
                }
            }
        }
        return null;
    }
    
    /**
     * 获取所有查询参数
     * 
     * @param exchange HTTP交换对象
     * @return 参数映射
     */
    default Map<String, String> getQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    try {
                        params.put(keyValue[0], java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name()));
                    } catch (Exception e) {
                        params.put(keyValue[0], keyValue[1]);
                    }
                }
            }
        }
        
        return params;
    }
    
    /**
     * 打印日志
     * 
     * @param message 日志消息
     */
    default void log(String message) {
        log.info("[HttpHandler] " + message)
    }
    
    /**
     * 打印错误日志
     * 
     * @param message 错误消息
     */
    default void logError(String message) {
        log.error("[HttpHandler ERROR] " + message)
    }
}
