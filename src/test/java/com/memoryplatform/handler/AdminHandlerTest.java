package com.memoryplatform.handler;

import com.memoryplatform.config.ApplicationConfig;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminHandler 单元测试
 * 覆盖：token认证、统计信息、缓存清除、存储健康检查、未知端点
 */
@ExtendWith(MockitoExtension.class)
class AdminHandlerTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private GraphStore graphStore;
    @Mock
    private MetadataStore metadataStore;
    @Mock
    private ApplicationConfig config;

    private AdminHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AdminHandler(vectorStore, graphStore, metadataStore, config);
    }

    // ==================== 辅助方法 ====================

    private HttpExchange createMockExchange(String method, String path,
                                             String authHeader) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers requestHeaders = new Headers();
        if (authHeader != null) {
            requestHeaders.set("Authorization", authHeader);
        }
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8080));

        // Mock request body (empty for GET)
        InputStream emptyIs = new ByteArrayInputStream(new byte[0]);
        when(exchange.getRequestBody()).thenReturn(emptyIs);

        // Mock response writing
        Headers responseHeaders = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        OutputStream os = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(os);
        when(exchange.getResponseCode()).thenReturn(200);

        return exchange;
    }

    // ==================== 认证测试 ====================

    @Test
    @DisplayName("未配置admin token - 开发模式允许访问")
    void noAdminToken_devMode_allowsAccess() throws Exception {
        when(config.getAdminToken()).thenReturn(null);
        HttpExchange exchange = createMockExchange("GET", "/admin/stats", null);

        handler.handle(exchange, Collections.emptyMap());

        // 应该正常返回200
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("空admin token - 开发模式允许访问")
    void blankAdminToken_devMode_allowsAccess() throws Exception {
        when(config.getAdminToken()).thenReturn("  ");
        HttpExchange exchange = createMockExchange("GET", "/admin/stats", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("有效token - 认证通过")
    void validToken_authenticationPasses() throws Exception {
        when(config.getAdminToken()).thenReturn("secret-token");
        when(metadataStore.count(anyString(), anyMap())).thenReturn(42L);

        HttpExchange exchange = createMockExchange("GET", "/admin/stats",
                "Bearer secret-token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("无效token - 返回403")
    void invalidToken_returns403() throws Exception {
        when(config.getAdminToken()).thenReturn("secret-token");
        HttpExchange exchange = createMockExchange("GET", "/admin/stats",
                "Bearer wrong-token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(403), anyLong());
    }

    @Test
    @DisplayName("缺少Authorization头 - 返回401")
    void missingAuthHeader_returns401() throws Exception {
        when(config.getAdminToken()).thenReturn("secret-token");
        HttpExchange exchange = createMockExchange("GET", "/admin/stats", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(401), anyLong());
    }

    // ==================== 统计信息测试 ====================

    @Test
    @DisplayName("GET /admin/stats - 返回统计信息")
    void getStats_returnsStats() throws Exception {
        when(config.getAdminToken()).thenReturn("token");
        when(metadataStore.count(anyString(), anyMap())).thenReturn(100L);

        HttpExchange exchange = createMockExchange("GET", "/admin/stats",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("GET /admin/stats - metadataStore为null")
    void getStats_metadataStoreNull() throws Exception {
        AdminHandler handlerNoMeta = new AdminHandler(vectorStore, graphStore, null, config);
        when(config.getAdminToken()).thenReturn("token");

        HttpExchange exchange = createMockExchange("GET", "/admin/stats",
                "Bearer token");

        handlerNoMeta.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    // ==================== 缓存清除测试 ====================

    @Test
    @DisplayName("POST /admin/cache/clear - 清除缓存")
    void postCacheClear() throws Exception {
        when(config.getAdminToken()).thenReturn("token");

        HttpExchange exchange = createMockExchange("POST", "/admin/cache/clear",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    // ==================== 存储健康检查测试 ====================

    @Test
    @DisplayName("GET /admin/storage/health - 所有存储健康")
    void getStorageHealth_allHealthy() throws Exception {
        when(config.getAdminToken()).thenReturn("token");
        when(vectorStore.healthCheck()).thenReturn(true);
        when(graphStore.healthCheck()).thenReturn(true);
        when(metadataStore.healthCheck()).thenReturn(true);

        HttpExchange exchange = createMockExchange("GET", "/admin/storage/health",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("GET /admin/storage/health - 向量库不健康返回503")
    void getStorageHealth_vectorUnhealthy_returns503() throws Exception {
        when(config.getAdminToken()).thenReturn("token");
        when(vectorStore.healthCheck()).thenReturn(false);
        when(graphStore.healthCheck()).thenReturn(true);
        when(metadataStore.healthCheck()).thenReturn(true);

        HttpExchange exchange = createMockExchange("GET", "/admin/storage/health",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(503), anyLong());
    }

    @Test
    @DisplayName("GET /admin/storage/health - 存储抛异常")
    void getStorageHealth_storeThrows() throws Exception {
        when(config.getAdminToken()).thenReturn("token");
        when(vectorStore.healthCheck()).thenThrow(new RuntimeException("Connection refused"));
        when(graphStore.healthCheck()).thenReturn(true);
        when(metadataStore.healthCheck()).thenReturn(true);

        HttpExchange exchange = createMockExchange("GET", "/admin/storage/health",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(503), anyLong());
    }

    @Test
    @DisplayName("GET /admin/storage/health - 所有存储未配置")
    void getStorageHealth_allNotConfigured() throws Exception {
        AdminHandler handlerNoStorage = new AdminHandler(null, null, null, config);
        when(config.getAdminToken()).thenReturn("token");

        HttpExchange exchange = createMockExchange("GET", "/admin/storage/health",
                "Bearer token");

        handlerNoStorage.handle(exchange, Collections.emptyMap());

        // 未配置不算不健康，应该是200
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    // ==================== 未知端点测试 ====================

    @Test
    @DisplayName("未知管理端点 - 返回404")
    void unknownEndpoint_returns404() throws Exception {
        when(config.getAdminToken()).thenReturn("token");

        HttpExchange exchange = createMockExchange("GET", "/admin/unknown",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(404), anyLong());
    }

    // ==================== 维护压缩测试 ====================

    @Test
    @DisplayName("POST /admin/maintenance/compact - 触发压缩")
    void postMaintenanceCompact() throws Exception {
        when(config.getAdminToken()).thenReturn("token");

        HttpExchange exchange = createMockExchange("POST", "/admin/maintenance/compact",
                "Bearer token");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }
}
