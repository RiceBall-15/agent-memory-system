package com.memoryplatform.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 路由框架测试类
 * <p>
 * 演示MemoryPlatform HTTP路由框架的各种功能。
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
public class RouterTest {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static void main(String[] args) throws IOException {
        System.out.println("===========================================");
        System.out.println("  MemoryPlatform HTTP Router Framework");
        System.out.println("  测试启动");
        System.out.println("===========================================\n");
        
        // 创建路由
        Router router = createRouter();
        
        // 创建并启动服务器
        MemoryHttpServer server = MemoryHttpServer.builder()
                .port(8080)
                .threadCount(10)
                .build();
        
        server.start(router);
        
        System.out.println("\n服务器已启动，等待测试...");
        System.out.println("测试地址: http://localhost:8080");
        System.out.println("\n可用路由:");
        System.out.println("  GET    /api/health");
        System.out.println("  GET    /api/memories");
        System.out.println("  POST   /api/memories");
        System.out.println("  GET    /api/memories/{id}");
        System.out.println("  PUT    /api/memories/{id}");
        System.out.println("  DELETE /api/memories/{id}");
        System.out.println("  GET    /api/users/{userId}/memories");
        System.out.println("  GET    /public/info");
        System.out.println("  POST   /api/data (需要API Key)");
        System.out.println("\n按Enter停止服务器...");
        
        try {
            System.in.read();
        } catch (Exception e) {
            // 忽略
        }
        
        server.stop();
        System.out.println("服务器已停止");
    }
    
    /**
     * 创建路由配置
     */
    private static Router createRouter() {
        Router router = new Router();
        
        // ========== 添加中间件 ==========
        
        // 1. 日志中间件（最先执行）
        router.addMiddleware(new LoggingMiddleware(true));
        
        // 2. CORS中间件
        router.addMiddleware(CorsMiddleware.builder()
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-API-Key")
                .maxAge(3600)
                .build());
        
        // 3. API Key认证中间件
        router.addMiddleware(AuthMiddleware.builder()
                .apiKey("test-secret-key-123")
                .apiKey("another-key-456")
                .excludedPaths("/api/health", "/public")
                .build());
        
        // ========== 基础路由 ==========
        
        // 健康检查
        router.get("/api/health", (exchange, params) -> {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "healthy");
            response.put("timestamp", System.currentTimeMillis());
            response.put("version", "1.0.0");
            
            router.jsonResponse(exchange, 200, response);
        });
        
        // 获取所有记忆
        router.get("/api/memories", (exchange, params) -> {
            // 模拟数据
            Map<String, Object> data = new HashMap<>();
            data.put("total", 100);
            data.put("memories", java.util.List.of(
                Map.of("id", "1", "title", "第一个记忆", "content", "内容1"),
                Map.of("id", "2", "title", "第二个记忆", "content", "内容2")
            ));
            
            router.jsonResponse(exchange, 200, data);
        });
        
        // 创建记忆
        router.post("/api/memories", (exchange, params) -> {
            String body = router.readBodyAs(exchange, String.class);
            System.out.println("[Test] 收到创建记忆请求: " + body);
            
            Map<String, Object> response = new HashMap<>();
            response.put("id", "new-123");
            response.put("message", "记忆创建成功");
            
            router.jsonResponse(exchange, 201, response);
        });
        
        // ========== 带路径参数的路由 ==========
        
        // 获取单个记忆
        router.get("/api/memories/{id}", (exchange, params) -> {
            String id = params.get("id");
            System.out.println("[Test] 获取记忆: id=" + id);
            
            Map<String, Object> memory = new HashMap<>();
            memory.put("id", id);
            memory.put("title", "记忆标题-" + id);
            memory.put("content", "记忆内容-" + id);
            memory.put("createdAt", System.currentTimeMillis());
            
            router.jsonResponse(exchange, 200, memory);
        });
        
        // 更新记忆
        router.put("/api/memories/{id}", (exchange, params) -> {
            String id = params.get("id");
            String body = router.readBodyAs(exchange, String.class);
            System.out.println("[Test] 更新记忆: id=" + id + ", body=" + body);
            
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("message", "记忆更新成功");
            
            router.jsonResponse(exchange, 200, response);
        });
        
        // 删除记忆
        router.delete("/api/memories/{id}", (exchange, params) -> {
            String id = params.get("id");
            System.out.println("[Test] 删除记忆: id=" + id);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "记忆删除成功");
            response.put("deletedId", id);
            
            router.jsonResponse(exchange, 200, response);
        });
        
        // ========== 嵌套路径参数 ==========
        
        // 获取用户的记忆列表
        router.get("/api/users/{userId}/memories", (exchange, params) -> {
            String userId = params.get("userId");
            System.out.println("[Test] 获取用户记忆: userId=" + userId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("userId", userId);
            data.put("memories", java.util.List.of(
                Map.of("id", "1", "title", "用户" + userId + "的记忆1")
            ));
            
            router.jsonResponse(exchange, 200, data);
        });
        
        // ========== 路由分组 ==========
        
        Router.Group publicGroup = router.createGroup("/public");
        
        publicGroup.get("/info", (exchange, params) -> {
            Map<String, Object> info = new HashMap<>();
            info.put("name", "MemoryPlatform");
            info.put("version", "1.0.0");
            info.put("description", "AI记忆管理系统");
            
            router.jsonResponse(exchange, 200, info);
        });
        
        // ========== 需要认证的路由 ==========
        
        router.post("/api/data", (exchange, params) -> {
            String body = router.readBodyAs(exchange, String.class);
            System.out.println("[Test] 保存数据: " + body);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "数据保存成功");
            
            router.jsonResponse(exchange, 201, response);
        });
        
        // ========== 错误处理路由 ==========
        
        // 404处理器（可选，框架有默认处理）
        router.onNotFound((exchange, params) -> {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "页面未找到");
            response.put("path", exchange.getRequestURI().getPath());
            
            router.jsonResponse(exchange, 404, response);
        });
        
        return router;
    }
}
