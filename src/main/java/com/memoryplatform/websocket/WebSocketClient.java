package com.memoryplatform.websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
/**
 * WebSocket客户端封装
 * <p>
 * 提供WebSocket客户端连接能力，支持消息队列（防止阻塞）、
 * 自动重连（指数退避）、心跳维持。
 * </p>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>异步消息发送（内置消息队列，最大1000条）</li>
 *   <li>指数退避重连（初始1秒，最大60秒，最多尝试50次）</li>
 *   <li>自动心跳维持（每30秒ping）</li>
 *   <li>回调式消息接收</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * WebSocketClient client = new WebSocketClient("ws://localhost:8080/ws");
 * client.setMessageListener(message -> {
 *     log.info("收到: " + message)
 * });
 * client.connect();
 *
 * // 发送消息（放入队列，不阻塞）
 * client.send("{\"action\":\"subscribe\", \"channel\":\"ALL\"}");
 *
 * // 断开连接
 * client.disconnect();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
@Slf4j
public class WebSocketClient {

    // ==================== 常量 ====================

    /** WebSocket魔术字符串 */
    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 消息队列最大容量 */
    private static final int MAX_QUEUE_SIZE = 1000;

    /** 初始重连延迟（毫秒） */
    private static final long INITIAL_RETRY_DELAY_MS = 1_000;

    /** 最大重连延迟（毫秒） */
    private static final long MAX_RETRY_DELAY_MS = 60_000;

    /** 最大重连次数（0=无限） */
    private static final int MAX_RETRY_ATTEMPTS = 50;

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    // ==================== 消息监听器接口 ====================

    /**
     * 消息监听器
     */
    public interface MessageListener {
        /**
         * 收到消息时回调
         *
         * @param message 消息内容
         */
        void onMessage(String message);
    }

    /**
     * 连接状态监听器
     */
    public interface ConnectionListener {
        /** 连接成功 */
        void onConnected();
        /** 连接断开 */
        void onDisconnected(String reason);
        /** 连接错误 */
        void onError(String error);
    }

    // ==================== 字段 ====================

    /** 服务器地址 */
    private final String serverUri;

    /** 连接ID */
    private final String clientId = "client-" + UUID.randomUUID().toString().substring(0, 8);

    /** Socket连接 */
    private volatile Socket socket;

    /** 输入流 */
    private volatile InputStream inputStream;

    /** 输出流 */
    private volatile OutputStream outputStream;

    /** 是否已连接 */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 是否手动断开（不自动重连） */
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(false);

    /** 消息队列（防止发送阻塞） */
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    /** 消息监听器 */
    private volatile MessageListener messageListener;

    /** 连接监听器 */
    private volatile ConnectionListener connectionListener;

    /** 消息接收线程 */
    private Thread receiverThread;

    /** 消息发送线程 */
    private Thread senderThread;

    /** 心跳线程 */
    private Thread heartbeatThread;

    /** 重连次数 */
    private final AtomicInteger retryCount = new AtomicInteger(0);

    // ==================== 构造函数 ====================

    /**
     * 创建WebSocket客户端
     *
     * @param serverUri 服务器WebSocket地址，如 "ws://localhost:8080/ws"
     */
    public WebSocketClient(String serverUri) {
        this.serverUri = serverUri;
    }

    // ==================== 公共API ====================

    /**
     * 设置消息监听器
     */
    public WebSocketClient setMessageListener(MessageListener listener) {
        this.messageListener = listener;
        return this;
    }

    /**
     * 设置连接状态监听器
     */
    public WebSocketClient setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
        return this;
    }

    /**
     * 连接到服务器
     */
    public void connect() {
        if (connected.get()) {
            log.info("[WebSocketClient] 已经连接")
            return;
        }

        manualDisconnect.set(false);
        retryCount.set(0);
        startConnection();
    }

    /**
     * 断开连接（不自动重连）
     */
    public void disconnect() {
        manualDisconnect.set(true);
        connected.set(false);

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // ignore
        }

        stopThreads();
        log.info("[WebSocketClient] 已断开连接")
    }

    /**
     * 发送文本消息（非阻塞，放入队列）
     *
     * @param message 消息内容
     * @return true如果消息已入队，false如果队列已满
     */
    public boolean send(String message) {
        if (!connected.get()) {
            log.error("[WebSocketClient] 未连接，无法发送消息")
            return false;
        }

        boolean offered = messageQueue.offer(message);
        if (!offered) {
            log.error("[WebSocketClient] 消息队列已满，丢弃消息: "
                    + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
        }
        return offered;
    }

    /**
     * 发送WebSocket消息对象
     *
     * @param message 消息对象
     * @return true如果发送成功
     */
    public boolean send(WebSocketMessage message) {
        return send(message.toJson());
    }

    /**
     * 订阅事件
     *
     * @param channel 频道类型
     * @return true如果成功入队
     */
    public boolean subscribe(WebSocketSubscription.Channel channel) {
        String msg = String.format("{\"action\":\"subscribe\",\"channel\":\"%s\"}", channel.name());
        return send(msg);
    }

    /**
     * 订阅事件（带用户/Agent过滤）
     *
     * @param channel 频道类型
     * @param userId  用户ID过滤
     * @param agentId Agent ID过滤
     * @return true如果成功入队
     */
    public boolean subscribe(WebSocketSubscription.Channel channel, String userId, String agentId) {
        StringBuilder msg = new StringBuilder();
        msg.append("{\"action\":\"subscribe\",\"channel\":\"").append(channel.name()).append("\"");
        if (userId != null) msg.append(",\"userId\":\"").append(userId).append("\"");
        if (agentId != null) msg.append(",\"agentId\":\"").append(agentId).append("\"");
        msg.append("}");
        return send(msg.toString());
    }

    /**
     * 取消订阅
     *
     * @param subscriptionId 订阅ID
     * @return true如果成功入队
     */
    public boolean unsubscribe(String subscriptionId) {
        String msg = String.format("{\"action\":\"unsubscribe\",\"subscriptionId\":\"%s\"}", subscriptionId);
        return send(msg);
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 获取客户端ID
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 获取队列中待发送的消息数
     */
    public int getPendingMessageCount() {
        return messageQueue.size();
    }

    // ==================== 内部连接逻辑 ====================

    /**
     * 启动连接
     */
    private void startConnection() {
        try {
            URI uri = new URI(serverUri);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1) port = 80;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";

            log.info("[WebSocketClient] 连接: " + host + ":" + port + path)

            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60_000); // 60秒读超时

            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();

            // WebSocket握手
            performHandshake(host, port, path);

            connected.set(true);
            retryCount.set(0);

            log.info("[WebSocketClient] 连接成功: " + clientId)

            // 启动线程
            startReceiverThread();
            startSenderThread();
            startHeartbeatThread();

            // 通知连接成功
            ConnectionListener listener = connectionListener;
            if (listener != null) listener.onConnected();

        } catch (Exception e) {
            connected.set(false);
            log.error("[WebSocketClient] 连接失败: " + e.getMessage());
            ConnectionListener listener = connectionListener;
            if (listener != null) listener.onError(e.getMessage());

            // 自动重连
            if (!manualDisconnect.get()) {
                scheduleReconnect();
            }
        }
    }

    /**
     * 执行WebSocket握手
     */
    private void performHandshake(String host, int port, String path) throws Exception {
        String key = Base64.getEncoder().encodeToString(
                UUID.randomUUID().toString().substring(0, 16).getBytes());

        String hostHeader = host + (port != 80 ? ":" + port : "");

        StringBuilder handshake = new StringBuilder();
        handshake.append("GET ").append(path).append(" HTTP/1.1\r\n");
        handshake.append("Host: ").append(hostHeader).append("\r\n");
        handshake.append("Upgrade: websocket\r\n");
        handshake.append("Connection: Upgrade\r\n");
        handshake.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        handshake.append("Sec-WebSocket-Version: 13\r\n");
        handshake.append("\r\n");

        outputStream.write(handshake.toString().getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        // 读取响应
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        String line;
        boolean upgraded = false;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.contains("101")) {
                upgraded = true;
            }
        }

        if (!upgraded) {
            throw new IOException("WebSocket upgrade failed");
        }
    }

    /**
     * 启动消息接收线程
     */
    private void startReceiverThread() {
        receiverThread = Thread.ofVirtual().name("ws-receiver-" + clientId).start(() -> {
            try {
                while (connected.get() && !socket.isClosed()) {
                    // 读取帧
                    int b1 = inputStream.read();
                    if (b1 == -1) break;

                    int b2 = inputStream.read();
                    if (b2 == -1) break;

                    byte opcode = (byte) (b1 & 0x0F);
                    boolean masked = (b2 & 0x80) != 0;
                    long payloadLength = b2 & 0x7F;

                    if (payloadLength == 126) {
                        int len1 = inputStream.read();
                        int len2 = inputStream.read();
                        payloadLength = (len1 << 8) | len2;
                    } else if (payloadLength == 127) {
                        payloadLength = 0;
                        for (int i = 0; i < 8; i++) {
                            payloadLength = (payloadLength << 8) | inputStream.read();
                        }
                    }

                    // 读取掩码
                    byte[] maskKey = null;
                    if (masked) {
                        maskKey = new byte[4];
                        inputStream.read(maskKey);
                    }

                    // 读取payload
                    byte[] payload = new byte[(int) payloadLength];
                    int totalRead = 0;
                    while (totalRead < payloadLength) {
                        totalRead += inputStream.read(payload, totalRead, (int) (payloadLength - totalRead));
                    }

                    // 解码
                    if (masked && maskKey != null) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
                        }
                    }

                    // 处理
                    switch (opcode) {
                        case 0x1: // Text
                            String text = new String(payload, StandardCharsets.UTF_8);
                            MessageListener listener = messageListener;
                            if (listener != null) {
                                listener.onMessage(text);
                            }
                            break;
                        case 0x9: // Ping
                            // 回复pong
                            sendFrameInternal(0x80 | 0xA, payload);
                            break;
                        case 0xA: // Pong
                            // 心跳响应
                            break;
                        case 0x8: // Close
                            connected.set(false);
                            break;
                    }
                }
            } catch (IOException e) {
                if (!manualDisconnect.get()) {
                    log.error("[WebSocketClient] 接收线程异常: " + e.getMessage());
                }
            } finally {
                if (connected.get() && !manualDisconnect.get()) {
                    connected.set(false);
                    ConnectionListener listener = connectionListener;
                    if (listener != null) listener.onDisconnected("Connection lost");
                    scheduleReconnect();
                }
            }
        });
    }

    /**
     * 启动消息发送线程
     */
    private void startSenderThread() {
        senderThread = Thread.ofVirtual().name("ws-sender-" + clientId).start(() -> {
            while (connected.get() && !manualDisconnect.get()) {
                try {
                    String message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null && connected.get()) {
                        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                        sendFrameInternal(0x80 | 0x01, payload); // Text frame
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (connected.get()) {
                        log.error("[WebSocketClient] 发送线程异常: " + e.getMessage());
                    }
                    break;
                }
            }
        });
    }

    /**
     * 启动心跳线程
     */
    private void startHeartbeatThread() {
        heartbeatThread = Thread.ofVirtual().name("WS-Client-Heartbeat-" + clientId).daemon(true).start(() -> {
            while (connected.get() && !manualDisconnect.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (connected.get()) {
                        sendFrameInternal(0x80 | 0x09, "ping".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        });
    }

    /**
     * 发送帧（内部方法，带掩码）
     */
    private void sendFrameInternal(byte opcode, byte[] payload) throws IOException {
        if (outputStream == null) return;

        synchronized (outputStream) {
            outputStream.write(0x80 | (opcode & 0x0F));

            int len = payload.length;
            if (len < 126) {
                outputStream.write(0x80 | len); // mask位=1
            } else if (len < 65536) {
                outputStream.write(0x80 | 126);
                outputStream.write((len >> 8) & 0xFF);
                outputStream.write(len & 0xFF);
            } else {
                outputStream.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    outputStream.write((int) ((len >> (i * 8)) & 0xFF));
                }
            }

            // 掩码密钥
            byte[] mask = new byte[4];
            new Random().nextBytes(mask);
            outputStream.write(mask);

            // 掩码后的payload
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
            outputStream.write(payload);
            outputStream.flush();
        }
    }

    // ==================== 重连逻辑 ====================

    /**
     * 调度重连（指数退避）
     */
    private void scheduleReconnect() {
        if (manualDisconnect.get()) return;

        int attempt = retryCount.incrementAndGet();
        if (attempt > MAX_RETRY_ATTEMPTS && MAX_RETRY_ATTEMPTS > 0) {
            log.error("[WebSocketClient] 达到最大重连次数(" + MAX_RETRY_ATTEMPTS + ")，停止重连");
            ConnectionListener listener = connectionListener;
            if (listener != null) listener.onDisconnected("Max retry attempts reached");
            return;
        }

        // 指数退避: 1s, 2s, 4s, 8s, 16s, 32s, 60s(max)
        long delay = Math.min(
                INITIAL_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 6)),
                MAX_RETRY_DELAY_MS
        );

        log.info("[WebSocketClient] " + delay + "ms 后尝试第 " + attempt + " 次重连...")

        Thread reconnector = Thread.ofVirtual().name("WS-Client-Reconnect-" + clientId).daemon(true).start(() -> {
            try {
                Thread.sleep(delay);
                if (!manualDisconnect.get() && !connected.get()) {
                    startConnection();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ==================== 停止线程 ====================

    private void stopThreads() {
        if (receiverThread != null) receiverThread.interrupt();
        if (senderThread != null) senderThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
    }
}
