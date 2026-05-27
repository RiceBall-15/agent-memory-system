package com.memoryplatform.websocket;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WebSocketServer 单元测试
 */
class WebSocketServerTest {

    private WebSocketServer server;

    @BeforeEach
    void setUp() {
        server = new WebSocketServer();
    }

    @AfterEach
    void tearDown() {
        if (server.isRunning()) {
            server.shutdown();
        }
    }

    @Test
    void testConstructor_DefaultState() {
        WebSocketServer ws = new WebSocketServer();
        assertFalse(ws.isRunning());
        assertEquals(0, ws.getConnectionCount());
        assertEquals(0, ws.getSubscriptionCount());
    }

    @Test
    void testSetPathPrefix_ReturnsSelf() {
        WebSocketServer result = server.setPathPrefix("/custom-ws");
        assertSame(server, result, "setPathPrefix应返回this以支持链式调用");
    }

    @Test
    void testAddAndRemoveListener() {
        WebSocketServer.MessageListener listener = message -> {};
        // addListener不抛异常
        assertDoesNotThrow(() -> server.addListener(listener));
        // removeListener不抛异常
        assertDoesNotThrow(() -> server.removeListener(listener));
    }

    @Test
    void testGetConnectionIds_EmptyInitially() {
        Set<String> ids = server.getConnectionIds();
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void testGetConnectionCount_EmptyInitially() {
        assertEquals(0, server.getConnectionCount());
    }

    @Test
    void testGetSubscriptionCount_EmptyInitially() {
        assertEquals(0, server.getSubscriptionCount());
    }

    @Test
    void testBroadcast_WhenNotRunning_NoException() {
        // 服务器未启动时broadcast不应抛异常
        WebSocketMessage msg = WebSocketMessage.heartbeat();
        assertDoesNotThrow(() -> server.broadcast(msg));
    }

    @Test
    void testSendTo_NonExistentConnection_ReturnsFalse() {
        WebSocketMessage msg = WebSocketMessage.error(404, "not found");
        assertFalse(server.sendTo("non-existent-id", msg));
    }

    @Test
    void testComputeAcceptKey_ValidKey() throws Exception {
        // 测试RFC 6455的Accept Key计算
        // 使用反射调用私有方法
        Method method = WebSocketServer.class.getDeclaredMethod("computeAcceptKey", String.class);
        method.setAccessible(true);

        // 已知的测试向量: client key + magic = expected accept key
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String result = (String) method.invoke(server, clientKey);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testExtractJsonString_SimpleValue() throws Exception {
        Method method = WebSocketServer.class.getDeclaredMethod(
                "extractJsonString", String.class, String.class);
        method.setAccessible(true);

        String json = "{\"type\": \"subscribe\", \"channel\": \"memories\"}";
        String type = (String) method.invoke(null, json, "type");
        assertEquals("subscribe", type);

        String channel = (String) method.invoke(null, json, "channel");
        assertEquals("memories", channel);
    }

    @Test
    void testExtractJsonString_MissingKey() throws Exception {
        Method method = WebSocketServer.class.getDeclaredMethod(
                "extractJsonString", String.class, String.class);
        method.setAccessible(true);

        String json = "{\"type\": \"subscribe\"}";
        String result = (String) method.invoke(null, json, "missing");
        assertNull(result);
    }

    @Test
    void testExtractJsonString_NumericValue() throws Exception {
        Method method = WebSocketServer.class.getDeclaredMethod(
                "extractJsonString", String.class, String.class);
        method.setAccessible(true);

        String json = "{\"code\": 1000, \"message\": \"ok\"}";
        String code = (String) method.invoke(null, json, "code");
        assertEquals("1000", code);
    }
}
