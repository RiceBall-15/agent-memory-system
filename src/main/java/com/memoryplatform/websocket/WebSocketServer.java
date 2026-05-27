package com.memoryplatform.websocket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket服务器 - 基于JDK HttpServer的WebSocket实现
 * <p>
 * 无需外部依赖，完全基于JDK内置HttpServer实现WebSocket协议。
 * 支持文本帧通信、心跳检测、连接管理、消息广播。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>WebSocket升级握手（RFC 6455）</li>
 *   <li>文本帧编解码（opcode 0x1, 0x8, 0x9, 0xA）</li>
 *   <li>连接管理（ConcurrentHashMap + 连接生命周期）</li>
 *   <li>心跳检测（30秒ping/pong，自动断连清理）</li>
 *   <li>消息广播（基于订阅过滤）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * WebSocketServer wsServer = new WebSocketServer();
 * // 注册到HttpServer的/ws路径
 * server.createContext("/ws", wsServer);
 * wsServer.start();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebSocketServer implements com.sun.net.httpserver.HttpHandler {

    // ==================== 常量 ====================

    /** WebSocket魔术字符串（握手用） */
    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000; // 30秒

    /** 心跳超时（毫秒），超过此时间未收到pong则断开 */
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000; // 60秒

    /** 连接清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 10_000; // 10秒

    // ==================== 帧操作码 ====================

    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_CLOSE = 0x8;
    private static final byte OPCODE_PING = 0x9;
    private static final byte OPCODE_PONG = 0xA;

    // ==================== 状态 ====================

    /** 连接ID生成器 */
    private final AtomicInteger connectionIdGen = new AtomicInteger(0);

    /** 活跃连接映射: connectionId -> WebSocketConnection */
    private final ConcurrentHashMap<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    /** 所有订阅: connectionId -> Set<WebSocketSubscription> */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSubscription>> subscriptions = new ConcurrentHashMap<>();

    /** 定时任务调度器 */
    private ScheduledExecutorService scheduler;

    /** 是否已启动 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** WebSocket路径前缀 */
    private String pathPrefix = "/ws";

    /** 消息监听器列表 */
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    // ==================== 连接内部类 ====================

    /**
     * WebSocket连接表示
     */
    public static class WebSocketConnection {
        /** 连接ID */
        public final String id;
        /** HttpExchange对象 */
        public final HttpExchange exchange;
        /** 输出流（用于发送帧） */
        public final OutputStream outputStream;
        /** 是否已关闭 */
        public volatile boolean closed = false;
        /** 最后一次收到消息的时间 */
        public volatile long lastMessageTime;
        /** 最后一次ping发送时间 */
        public volatile long lastPingTime = 0;
        /** 是否收到pong响应 */
        public volatile boolean pongReceived = true;
        /** 客户端地址 */
        public final String clientAddress;

        public WebSocketConnection(String id, HttpExchange exchange, OutputStream outputStream) {
            this.id = id;
            this.exchange = exchange;
            this.outputStream = outputStream;
            this.lastMessageTime = System.currentTimeMillis();
            this.clientAddress = exchange.getRemoteAddress() != null
                    ? exchange.getRemoteAddress().toString() : "unknown";
        }
    }

    // ==================== 监听器接口 ====================

    /**
     * WebSocket消息监听器
     */
    public interface MessageListener {
        /**
         * 收到客户端消息时回调
         *
         * @param connectionId 连接ID
         * @param message     消息内容
         */
        void onMessage(String connectionId, String message);

        /**
         * 新连接建立时回调
         *
         * @param connectionId 连接ID
         */
        void onConnect(String connectionId);

        /**
         * 连接断开时回调
         *
         * @param connectionId 连接ID
         */
        void onDisconnect(String connectionId);
    }

    // ==================== 公共API ====================

    /**
     * 创建WebSocket服务器
     */
    public WebSocketServer() {
    }

    /**
     * 设置WebSocket路径前缀（必须在start前调用）
     *
     * @param prefix 路径前缀，默认"/ws"
     * @return this
     */
    public WebSocketServer setPathPrefix(String prefix) {
        this.pathPrefix = prefix;
        return this;
    }

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

    /**
     * 启动心跳检测和连接清理
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.out.println("[WebSocketServer] 已经启动");
            return;
        }

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "WebSocket-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        // 心跳检测任务
        scheduler.scheduleAtFixedRate(this::heartbeatCheck,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 连接清理任务
        scheduler.scheduleAtFixedRate(this::cleanupStaleConnections,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("[WebSocketServer] 启动完成, 路径前缀=" + pathPrefix);
        System.out.println("[WebSocketServer] 心跳间隔=" + (HEARTBEAT_INTERVAL_MS / 1000) + "s");
    }

    /**
     * 关闭WebSocket服务器，断开所有连接
     */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;

        System.out.println("[WebSocketServer] 正在关闭...");

        // 关闭所有连接
        for (WebSocketConnection conn : connections.values()) {
            closeConnection(conn, "Server shutting down");
        }
        connections.clear();
        subscriptions.clear();

        // 停止调度器
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("[WebSocketServer] 已关闭");
    }

    // ==================== HttpHandler实现 ====================

    /**
     * 处理HTTP请求 - 检测是否为WebSocket升级请求
     *
     * @param exchange HTTP交换对象
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String upgradeHeader = exchange.getRequestHeaders().getFirst("Upgrade");
        if (upgradeHeader == null || !upgradeHeader.equalsIgnoreCase("websocket")) {
            sendHttpResponse(exchange, 400, "Bad Request: Expected WebSocket upgrade");
            return;
        }

        String key = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");
        if (key == null || key.isBlank()) {
            sendHttpResponse(exchange, 400, "Bad Request: Missing Sec-WebSocket-Key");
            return;
        }

        // 完成WebSocket握手
        performHandshake(exchange, key);
    }

    /**
     * 执行WebSocket握手
     */
    private void performHandshake(HttpExchange exchange, String clientKey) throws IOException {
        // 计算Accept Key
        String acceptKey = computeAcceptKey(clientKey);

        // 发送101 Switching Protocols响应
        exchange.getResponseHeaders().set("Upgrade", "websocket");
        exchange.getResponseHeaders().set("Connection", "Upgrade");
        exchange.getResponseHeaders().set("Sec-WebSocket-Accept", acceptKey);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        exchange.sendResponseHeaders(101, -1);
        OutputStream os = exchange.getResponseBody();
        os.flush(); // 确保响应已发送

        // 创建连接
        String connId = "ws-" + connectionIdGen.incrementAndGet();
        WebSocketConnection connection = new WebSocketConnection(connId, exchange, os);
        connections.put(connId, connection);

        System.out.println("[WebSocketServer] 新连接: " + connId + " from " + connection.clientAddress);

        // 通知监听器
        for (MessageListener listener : listeners) {
            try {
                listener.onConnect(connId);
            } catch (Exception e) {
                System.err.println("[WebSocketServer] onConnect回调异常: " + e.getMessage());
            }
        }

        // 在独立线程中处理WebSocket消息
        Thread handlerThread = new Thread(() -> handleWebSocketMessages(connection),
                "WS-Handler-" + connId);
        handlerThread.setDaemon(true);
        handlerThread.start();
    }

    /**
     * 处理WebSocket消息循环
     */
    private void handleWebSocketMessages(WebSocketConnection connection) {
        System.out.println("[WebSocketServer] 消息处理线程启动: " + connection.id);

        try {
            InputStream is = connection.exchange.getRequestBody();
            byte[] buffer = new byte[4096];

            while (!connection.closed && running.get()) {
                try {
                    // 读取第一帧的前两个字节
                    int b1 = is.read();
                    if (b1 == -1) break; // 流结束

                    int b2 = is.read();
                    if (b2 == -1) break;

                    byte opcode = (byte) (b1 & 0x0F);
                    boolean masked = (b2 & 0x80) != 0;
                    long payloadLength = b2 & 0x7F;

                    // 读取扩展长度
                    if (payloadLength == 126) {
                        int len1 = is.read();
                        int len2 = is.read();
                        if (len1 == -1 || len2 == -1) break;
                        payloadLength = (len1 << 8) | len2;
                    } else if (payloadLength == 127) {
                        payloadLength = 0;
                        for (int i = 0; i < 8; i++) {
                            int byteVal = is.read();
                            if (byteVal == -1) break;
                            payloadLength = (payloadLength << 8) | byteVal;
                        }
                    }

                    // 读取掩码密钥（如果客户端发来的消息）
                    byte[] maskKey = null;
                    if (masked) {
                        maskKey = new byte[4];
                        int read = is.read(maskKey);
                        if (read < 4) break;
                    }

                    // 读取payload数据
                    byte[] payload = new byte[(int) payloadLength];
                    int totalRead = 0;
                    while (totalRead < payloadLength) {
                        int read = is.read(payload, totalRead, (int) (payloadLength - totalRead));
                        if (read == -1) break;
                        totalRead += read;
                    }

                    // 如果有掩码，解码payload
                    if (masked && maskKey != null) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
                        }
                    }

                    connection.lastMessageTime = System.currentTimeMillis();

                    // 根据opcode处理
                    handleFrame(connection, opcode, payload);

                } catch (java.net.SocketException e) {
                    if (!connection.closed) {
                        System.out.println("[WebSocketServer] 连接断开: " + connection.id + " - " + e.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    if (!connection.closed) {
                        System.err.println("[WebSocketServer] 读取错误: " + connection.id + " - " + e.getMessage());
                    }
                    break;
                }
            }
        } finally {
            if (!connection.closed) {
                removeConnection(connection);
            }
        }
    }

    /**
     * 处理WebSocket帧
     */
    private void handleFrame(WebSocketConnection connection, byte opcode, byte[] payload) {
        switch (opcode) {
            case OPCODE_TEXT:
                String text = new String(payload, StandardCharsets.UTF_8);
                System.out.println("[WebSocketServer] 收到消息 from " + connection.id + ": "
                        + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
                handleClientMessage(connection, text);
                break;

            case OPCODE_PING:
                // 收到ping，立即回复pong
                sendFrame(connection, OPCODE_PONG, payload);
                break;

            case OPCODE_PONG:
                // 收到pong，标记
                connection.pongReceived = true;
                break;

            case OPCODE_CLOSE:
                System.out.println("[WebSocketServer] 收到关闭帧: " + connection.id);
                sendCloseFrame(connection, 1000, "Normal closure");
                removeConnection(connection);
                break;

            default:
                System.out.println("[WebSocketServer] 未知opcode: " + opcode);
                break;
        }
    }

    /**
     * 处理客户端消息（文本）
     * <p>
     * 支持的消息格式：
     * <pre>
     * {"action": "subscribe", "channel": "ALL", "userId": "...", "agentId": "..."}
     * {"action": "unsubscribe", "subscriptionId": "..."}
     * {"action": "ping"}
     * </pre>
     * </p>
     */
    private void handleClientMessage(WebSocketConnection connection, String text) {
        try {
            // 简易JSON解析（避免依赖外部库）
            text = text.trim();

            if (text.equals("{\"action\":\"ping\"}") || text.equals("{\"action\":\"pong\"}")) {
                // 心跳响应
                sendFrame(connection, OPCODE_PONG, "pong".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // 解析action
            String action = extractJsonString(text, "action");

            if ("subscribe".equals(action)) {
                handleSubscribe(connection, text);
            } else if ("unsubscribe".equals(action)) {
                handleUnsubscribe(connection, text);
            } else {
                // 通知监听器处理
                for (MessageListener listener : listeners) {
                    try {
                        listener.onMessage(connection.id, text);
                    } catch (Exception e) {
                        System.err.println("[WebSocketServer] listener回调异常: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[WebSocketServer] 消息处理异常: " + e.getMessage());
            sendError(connection, 400, "Invalid message format");
        }
    }

    /**
     * 处理订阅请求
     */
    private void handleSubscribe(WebSocketConnection connection, String text) {
        WebSocketSubscription.Channel channel = WebSocketSubscription.Channel.ALL;
        String userId = null;
        String agentId = null;

        String channelStr = extractJsonString(text, "channel");
        if (channelStr != null) {
            try {
                channel = WebSocketSubscription.Channel.valueOf(channelStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(connection, 400, "Invalid channel: " + channelStr);
                return;
            }
        }

        userId = extractJsonString(text, "userId");
        agentId = extractJsonString(text, "agentId");

        WebSocketSubscription subscription = WebSocketSubscription.builder(connection.id)
                .channel(channel)
                .userId(userId)
                .agentId(agentId)
                .build();

        subscriptions.computeIfAbsent(connection.id, k -> new CopyOnWriteArraySet<>())
                .add(subscription);

        System.out.println("[WebSocketServer] 新订阅: " + subscription);

        // 发送订阅确认
        WebSocketMessage ack = WebSocketMessage.subscriptionAck(
                subscription.getSubscriptionId(), channel.name());
        sendText(connection, ack.toJson());
    }

    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(WebSocketConnection connection, String text) {
        String subId = extractJsonString(text, "subscriptionId");
        CopyOnWriteArraySet<WebSocketSubscription> subs = subscriptions.get(connection.id);

        if (subs != null && subId != null) {
            subs.removeIf(s -> s.getSubscriptionId().equals(subId));
            System.out.println("[WebSocketServer] 取消订阅: " + subId);
        }
    }

    // ==================== 广播API ====================

    /**
     * 广播消息到所有订阅匹配的客户端
     *
     * @param message 待广播的消息
     */
    public void broadcast(WebSocketMessage message) {
        if (!running.get()) return;

        String json = message.toJson();
        int sent = 0;

        for (Map.Entry<String, CopyOnWriteArraySet<WebSocketSubscription>> entry : subscriptions.entrySet()) {
            String connId = entry.getKey();
            WebSocketConnection conn = connections.get(connId);
            if (conn == null || conn.closed) continue;

            // 检查是否有匹配的订阅
            for (WebSocketSubscription sub : entry.getValue()) {
                if (sub.matches(message)) {
                    if (sendText(conn, json)) {
                        sent++;
                    }
                    break; // 一个连接只发一次
                }
            }
        }

        System.out.println("[WebSocketServer] 广播 " + message.getType() + " 到 " + sent + " 个客户端");
    }

    /**
     * 向指定连接发送消息
     *
     * @param connectionId 连接ID
     * @param message     消息内容
     * @return 是否发送成功
     */
    public boolean sendTo(String connectionId, WebSocketMessage message) {
        WebSocketConnection conn = connections.get(connectionId);
        if (conn == null || conn.closed) return false;
        return sendText(conn, message.toJson());
    }

    /**
     * 发送文本帧
     *
     * @return 是否成功
     */
    private boolean sendText(WebSocketConnection connection, String text) {
        return sendFrame(connection, OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 发送WebSocket帧（服务器到客户端，不需要掩码）
     */
    private boolean sendFrame(WebSocketConnection connection, byte opcode, byte[] payload) {
        if (connection.closed) return false;

        try {
            synchronized (connection.outputStream) {
                // FIN + opcode
                connection.outputStream.write(0x80 | opcode);

                // payload长度（服务器端不使用掩码）
                int len = payload.length;
                if (len < 126) {
                    connection.outputStream.write(len);
                } else if (len < 65536) {
                    connection.outputStream.write(126);
                    connection.outputStream.write((len >> 8) & 0xFF);
                    connection.outputStream.write(len & 0xFF);
                } else {
                    connection.outputStream.write(127);
                    long l = len;
                    for (int i = 7; i >= 0; i--) {
                        connection.outputStream.write((int) ((l >> (i * 8)) & 0xFF));
                    }
                }

                connection.outputStream.write(payload);
                connection.outputStream.flush();
            }
            return true;
        } catch (IOException e) {
            System.err.println("[WebSocketServer] 发送帧失败: " + connection.id + " - " + e.getMessage());
            removeConnection(connection);
            return false;
        }
    }

    /**
     * 发送关闭帧
     */
    private void sendCloseFrame(WebSocketConnection connection, int code, String reason) {
        byte[] payload;
        if (reason != null && !reason.isEmpty()) {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        } else {
            payload = new byte[]{(byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF)};
        }
        sendFrame(connection, OPCODE_CLOSE, payload);
    }

    // ==================== 心跳和清理 ====================

    /**
     * 心跳检测
     */
    private void heartbeatCheck() {
        long now = System.currentTimeMillis();

        for (WebSocketConnection conn : connections.values()) {
            if (conn.closed) continue;

            // 检查是否超时未收到消息
            if (now - conn.lastMessageTime > HEARTBEAT_TIMEOUT_MS) {
                System.out.println("[WebSocketServer] 心跳超时，断开连接: " + conn.id);
                closeConnection(conn, "Heartbeat timeout");
                continue;
            }

            // 发送ping（每30秒）
            if (now - conn.lastPingTime > HEARTBEAT_INTERVAL_MS) {
                if (!conn.pongReceived && conn.lastPingTime > 0) {
                    // 上次ping未收到pong
                    System.out.println("[WebSocketServer] 未收到pong，断开连接: " + conn.id);
                    closeConnection(conn, "No pong response");
                    continue;
                }
                conn.pongReceived = false;
                conn.lastPingTime = now;
                sendFrame(conn, OPCODE_PING, "ping".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * 清理过期连接
     */
    private void cleanupStaleConnections() {
        long now = System.currentTimeMillis();
        List<WebSocketConnection> toRemove = new ArrayList<>();

        for (WebSocketConnection conn : connections.values()) {
            if (conn.closed) {
                toRemove.add(conn);
            }
        }

        for (WebSocketConnection conn : toRemove) {
            removeConnection(conn);
        }

        if (!toRemove.isEmpty()) {
            System.out.println("[WebSocketServer] 清理了 " + toRemove.size() + " 个过期连接");
        }
    }

    // ==================== 连接管理 ====================

    /**
     * 关闭并移除连接
     */
    private void closeConnection(WebSocketConnection connection, String reason) {
        if (connection.closed) return;
        connection.closed = true;

        try {
            sendCloseFrame(connection, 1000, reason);
        } catch (Exception e) {
            // ignore
        }

        removeConnection(connection);
    }

    /**
     * 移除连接
     */
    private void removeConnection(WebSocketConnection connection) {
        boolean removed = connections.remove(connection.id) != null;
        subscriptions.remove(connection.id);

        if (removed) {
            System.out.println("[WebSocketServer] 连接已移除: " + connection.id);
            for (MessageListener listener : listeners) {
                try {
                    listener.onDisconnect(connection.id);
                } catch (Exception e) {
                    System.err.println("[WebSocketServer] onDisconnect回调异常: " + e.getMessage());
                }
            }
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取活跃连接数
     */
    public int getConnectionCount() {
        int count = 0;
        for (WebSocketConnection conn : connections.values()) {
            if (!conn.closed) count++;
        }
        return count;
    }

    /**
     * 获取所有活跃连接ID
     */
    public Set<String> getConnectionIds() {
        Set<String> ids = new HashSet<>();
        for (WebSocketConnection conn : connections.values()) {
            if (!conn.closed) ids.add(conn.id);
        }
        return ids;
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
     * 是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== 工具方法 ====================

    /**
     * 计算WebSocket Accept Key（RFC 6455）
     */
    private String computeAcceptKey(String clientKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            String combined = clientKey + WEBSOCKET_MAGIC;
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute WebSocket accept key", e);
        }
    }

    /**
     * 发送HTTP错误响应
     */
    private void sendHttpResponse(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 发送错误消息到WebSocket连接
     */
    private void sendError(WebSocketConnection connection, int code, String message) {
        WebSocketMessage errorMsg = WebSocketMessage.error(code, message);
        sendText(connection, errorMsg.toJson());
    }

    /**
     * 简易JSON字符串提取（避免依赖外部JSON库）
     * <p>仅支持简单的 "key": "value" 模式，适用于内部协议消息。</p>
     */
    private static String extractJsonString(String json, String key) {
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
        if (quote == '"') {
            // 字符串值
            int valueEnd = json.indexOf('"', valueStart + 1);
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
