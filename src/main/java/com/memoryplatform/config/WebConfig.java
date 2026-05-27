package com.memoryplatform.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置类
 * <p>
 * 负责配置：
 * <ul>
 *   <li>CORS跨域策略</li>
 *   <li>WebSocket端点注册</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    @Value("${app.security.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Value("${app.security.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${app.security.cors.allowed-headers:*}")
    private String allowedHeaders;

    @Value("${app.websocket.enabled:true}")
    private boolean websocketEnabled;

    @Value("${app.websocket.heartbeat-interval:30000}")
    private int heartbeatInterval;

    /**
     * 配置CORS跨域策略
     * <p>
     * 默认允许所有来源、所有方法、所有头。
     * 生产环境应限制 allowedOrigins 为具体域名。
     * </p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("[WebConfig] 配置CORS: origins={}, methods={}", allowedOrigins, allowedMethods);
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods(allowedMethods.split(","))
                .allowedHeaders(allowedHeaders.split(","))
                .exposedHeaders("X-Request-Id", "X-Response-Time")
                .allowCredentials(true)
                .maxAge(3600); // 预检缓存1小时
    }

    /**
     * 配置WebSocket端点
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!websocketEnabled) {
            log.info("[WebConfig] WebSocket已禁用");
            return;
        }

        log.info("[WebConfig] 注册WebSocket端点: /ws, heartbeat={}", heartbeatInterval);
        // WebSocket端点将在WebSocketServer迁移后注册
        // registry.addHandler(new MemoryWebSocketHandler(), "/ws")
        //         .setAllowedOrigins("*");
    }

    @Override
    public void registerWebSocketFilters(WebSocketHandlerRegistry registry) {
        // WebSocket filter registration if needed
    }
}
