package com.memoryplatform.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MemoryWebSocketHandler 单元测试
 */
class WebSocketServerTest {

    private MemoryWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MemoryWebSocketHandler();
    }

    @AfterEach
    void tearDown() {
        // handler由Spring管理，无需手动关闭
    }

    @Test
    void testGetActiveConnections_EmptyInitially() {
        assertEquals(0, handler.getActiveConnections());
    }

    @Test
    void testGetActiveConnectionIds_EmptyInitially() {
        Set<String> ids = handler.getActiveConnectionIds();
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void testGetSubscriptionCount_EmptyInitially() {
        assertEquals(0, handler.getSubscriptionCount());
    }

    @Test
    void testHasActiveConnections_FalseWhenEmpty() {
        assertFalse(handler.hasActiveConnections());
    }

    @Test
    void testBroadcast_NoExceptionWhenEmpty() {
        // 无连接时broadcast不应抛异常
        WebSocketMessage msg = WebSocketMessage.heartbeat();
        assertDoesNotThrow(() -> handler.broadcast(msg));
    }

    @Test
    void testSendToSession_NonExistentSession_ReturnsFalse() {
        WebSocketMessage msg = WebSocketMessage.error(404, "not found");
        assertFalse(handler.sendToSession("non-existent-id", msg.toJson()));
    }

    @Test
    void testAddAndRemoveListener() {
        MemoryWebSocketHandler.MessageListener listener = new MemoryWebSocketHandler.MessageListener() {
            @Override
            public void onMessage(String sessionId, String message) {}
            @Override
            public void onConnect(String sessionId) {}
            @Override
            public void onDisconnect(String sessionId) {}
        };
        // addListener不抛异常
        assertDoesNotThrow(() -> handler.addListener(listener));
        // removeListener不抛异常
        assertDoesNotThrow(() -> handler.removeListener(listener));
    }

    @Test
    void testExtractJsonString_SimpleValue() {
        String json = "{\"type\": \"subscribe\", \"channel\": \"memories\"}";
        String type = MemoryWebSocketHandler.extractJsonString(json, "type");
        assertEquals("subscribe", type);

        String channel = MemoryWebSocketHandler.extractJsonString(json, "channel");
        assertEquals("memories", channel);
    }

    @Test
    void testExtractJsonString_MissingKey() {
        String json = "{\"type\": \"subscribe\"}";
        String result = MemoryWebSocketHandler.extractJsonString(json, "missing");
        assertNull(result);
    }

    @Test
    void testExtractJsonString_NumericValue() {
        String json = "{\"code\": 1000, \"message\": \"ok\"}";
        String code = MemoryWebSocketHandler.extractJsonString(json, "code");
        assertEquals("1000", code);
    }

    @Test
    void testExtractJsonString_BooleanValue() {
        String json = "{\"enabled\": true, \"name\": \"test\"}";
        String enabled = MemoryWebSocketHandler.extractJsonString(json, "enabled");
        assertEquals("true", enabled);
    }

    @Test
    void testExtractJsonString_NullValue() {
        String json = "{\"value\": null}";
        String value = MemoryWebSocketHandler.extractJsonString(json, "value");
        assertNull(value);
    }

    @Test
    void testBroadcastRaw_NoExceptionWhenEmpty() {
        assertDoesNotThrow(() -> handler.broadcastRaw("test message"));
    }
}
