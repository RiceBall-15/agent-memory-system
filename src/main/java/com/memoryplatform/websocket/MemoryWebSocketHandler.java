package com.memoryplatform.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.TextWebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Spring WebSocket处理器 - 替代基于JDK HttpServer的WebSocketServer
 * <p>
 * 基于Spring WebSocket框架实现，提供：
 * <ul>
 *   <li>WebSocket连接管理（ConcurrentHashMap + Session生命周期）</li>
 *   <li>消息接收和分发</li>
 *   <li>订阅管理（按频道和用户/Agent过滤）</li>
 *   <li>消息广播（基于订阅过滤）</li>
 *   <li>心跳检测（Spring内置支持）</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 2.0
 * @since 2.0
 */
@Slf4j
@Component
public class MemoryWebSocketHandler extends TextWebSocketHandler {

    /** 活跃连接映射: sessionId -> WebSocketSession */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 订阅映射: sessionId -> Set<WebSocketSubscription> */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSubscription>> subscriptions = new ConcurrentHashMap<>();

    /** 消息监听器列表 */
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    // ==================== 监听器接口 ====================

    /**
     * WebSocket消息监听器
     */
    public interface MessageListener {
        /**
         * 收到客户端消息时回调
         *
         * @param sessionId 会话ID
         * @param message   消息内容
         */
        void onMessage(String sessionId, String message);

        /**
         * 新连接建立时回调
         *
         * @param sessionId 会话ID
         */
        void onConnect(String sessionId);

        /**
         * 连接断开时回调
         *
         * @param sessionId 会话ID
         */
        void onDisconnect(String sessionId);
    }

    // ==================== 公共API ====================

    /**
     * 添加消息监听器
     *
     * @param listener 监听器
     */
    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除消息监听器
     *
     * @param listener 监听器
     */
    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    // ==================== Spring WebSocket回调 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("[MemoryWebSocket] 连接建立: {} from {}", sessionId, session.getRemoteAddress());

        // 通知监听器
        for (MessageListener listener : listeners) {
            try {
                listener.onConnect(sessionId);
            } catch (Exception e) {
                log.error("[MemoryWebSocket] onConnect回调异常: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextWebSocketMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();
        log.debug("[MemoryWebSocket] 收到消息: {} -> {}", sessionId,
                payload.length() > 100 ? payload.substring(0, 100) + "..." : payload);

        handleClientMessage(sessionId, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        subscriptions.remove(sessionId);
        log.info("[MemoryWebSocket] 连接关闭: {} status={}", sessionId, status);

        // 通知监听器
        for (MessageListener listener : listeners) {
            try {
                listener.onDisconnect(sessionId);
            } catch (Exception e) {
                log.error("[MemoryWebSocket] onDisconnect回调异常: {}", e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[MemoryWebSocket] 传输错误: {}", session.getId(), exception);
        // 清理连接
        sessions.remove(session.getId());
        subscriptions.remove(session.getId());
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 本系统仅支持文本消息
        log.warn("[MemoryWebSocket] 收到二进制消息，已忽略: {}", session.getId());
    }

    // ==================== 消息处理 ====================

    /**
     * 处理客户端消息
     * <p>
     * 支持的消息格式：
     * <pre>
     * {"action": "subscribe", "channel": "ALL", "userId": "...", "agentId": "..."}
     * {"action": "unsubscribe", "subscriptionId": "..."}
     * {"action": "ping"}
     * </pre>
     * </p>
     */
    private void handleClientMessage(String sessionId, String text) {
        try {
            text = text.trim();

            if (text.equals("{\"action\":\"ping\"}") || text.equals("{\"action\":\"pong\"}")) {
                // 心跳响应 - 发送pong
                sendToSession(sessionId, "{\"action\":\"pong\"}");
                return;
            }

            // 解析action
            String action = extractJsonString(text, "action");

            if ("subscribe".equals(action)) {
                handleSubscribe(sessionId, text);
            } else if ("unsubscribe".equals(action)) {
                handleUnsubscribe(sessionId, text);
            } else {
                // 通知监听器处理
                for (MessageListener listener : listeners) {
                    try {
                        listener.onMessage(sessionId, text);
                    } catch (Exception e) {
                        log.error("[MemoryWebSocket] listener回调异常: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[MemoryWebSocket] 消息处理异常: {}", e.getMessage());
            sendToSession(sessionId, WebSocketMessage.error(400, "Invalid message format").toJson());
        }
    }

    /**
     * 处理订阅请求
     */
    private void handleSubscribe(String sessionId, String text) {
        WebSocketSubscription.Channel channel = WebSocketSubscription.Channel.ALL;
        String userId = null;
        String agentId = null;

        String channelStr = extractJsonString(text, "channel");
        if (channelStr != null) {
            try {
                channel = WebSocketSubscription.Channel.valueOf(channelStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendToSession(sessionId, WebSocketMessage.error(400, "Invalid channel: " + channelStr).toJson());
                return;
            }
        }

        userId = extractJsonString(text, "userId");
        agentId = extractJsonString(text, "agentId");

        WebSocketSubscription subscription = WebSocketSubscription.builder(sessionId)
                .channel(channel)
                .userId(userId)
                .agentId(agentId)
                .build();

        subscriptions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>())
                .add(subscription);

        log.info("[MemoryWebSocket] 新订阅: {}", subscription);

        // 发送订阅确认
        WebSocketMessage ack = WebSocketMessage.subscriptionAck(
                subscription.getSubscriptionId(), channel.name());
        sendToSession(sessionId, ack.toJson());
    }

    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(String sessionId, String text) {
        String subId = extractJsonString(text, "subscriptionId");
        CopyOnWriteArraySet<WebSocketSubscription> subs = subscriptions.get(sessionId);

        if (subs != null && subId != null) {
            subs.removeIf(s -> s.getSubscriptionId().equals(subId));
            log.info("[MemoryWebSocket] 取消订阅: {}", subId);
        }
    }

    // ==================== 广播API ====================

    /**
     * 广播消息到所有订阅匹配的客户端
     *
     * @param message 待广播的消息
     */
    public void broadcast(WebSocketMessage message) {
        String json = message.toJson();
        int sent = 0;

        for (Map.Entry<String, CopyOnWriteArraySet<WebSocketSubscription>> entry : subscriptions.entrySet()) {
            String sessionId = entry.getKey();
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()) continue;

            // 检查是否有匹配的订阅
            for (WebSocketSubscription sub : entry.getValue()) {
                if (sub.matches(message)) {
                    if (sendMessage(session, json)) {
                        sent++;
                    }
                    break; // 一个连接只发一次
                }
            }
        }

        log.debug("[MemoryWebSocket] 广播 {} 到 {} 个客户端", message.getType(), sent);
    }

    /**
     * 向指定会话发送消息
     *
     * @param sessionId 会话ID
     * @param message  消息内容
     * @return 是否发送成功
     */
    public boolean sendToSession(String sessionId, String message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) return false;
        return sendMessage(session, message);
    }

    /**
     * 广播文本消息到所有连接的客户端（无订阅过滤）
     *
     * @param message 文本消息
     */
    public void broadcastRaw(String message) {
        sessions.values().forEach(session -> {
            if (session.isOpen()) {
                sendMessage(session, message);
            }
        });
    }

    /**
     * 发送WebSocket文本消息
     *
     * @param session WebSocket会话
     * @param message 消息内容
     * @return 是否发送成功
     */
    private boolean sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                return true;
            }
        } catch (Exception e) {
            log.error("[MemoryWebSocket] 发送消息失败: {} - {}", session.getId(), e.getMessage());
        }
        return false;
    }

    // ==================== 状态查询 ====================

    /**
     * 获取活跃连接数
     */
    public int getActiveConnections() {
        return sessions.size();
    }

    /**
     * 获取活跃连接ID集合
     */
    public Set<String> getActiveConnectionIds() {
        return Collections.unmodifiableSet(new HashSet<>(sessions.keySet()));
    }

    /**
     * 获取总订阅数
     */
    public int getSubscriptionCount() {
        int count = 0;
        for (CopyOnWriteArraySet<WebSocketSubscription> subs : subscriptions.values()) {
            count += subs.size();
        }
        return count;
    }

    /**
     * 检查是否有活跃连接
     */
    public boolean hasActiveConnections() {
        return !sessions.isEmpty();
    }

    // ==================== 工具方法 ====================

    /**
     * 简易JSON字符串提取（避免依赖外部JSON库）
     * <p>仅支持简单的 "key": "value" 模式，适用于内部协议消息。</p>
     */
    static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx == -1) return null;

        // 跳过空白
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

        if (valueStart >= json.length()) return null;

        char quote = json.charAt(valueStart);
        if (quote == '\"') {
            // 字符串值
            int valueEnd = json.indexOf('\"', valueStart + 1);
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        } else {
            // 非字符串值（null, number, boolean）
            int valueEnd = valueStart;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            String val = json.substring(valueStart, valueEnd).trim();
            return "null".equals(val) ? null : val;
        }
    }
}
