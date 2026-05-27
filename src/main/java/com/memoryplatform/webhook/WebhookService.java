package com.memoryplatform.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Webhook服务
 * <p>
 * 负责Webhook配置的CRUD管理、事件触发和异步发送。
 * 核心功能：
 * <ul>
 *   <li>事件触发：当发生指定事件时触发Webhook</li>
 *   <li>事件发送：使用HttpURLConnection发送POST请求</li>
 *   <li>签名验证：使用HMAC-SHA256(secret, payload)生成签名</li>
 *   <li>重试机制：失败后重试3次（可配置），指数退避</li>
 *   <li>异步发送：使用ExecutorService异步执行</li>
 *   <li>事件队列：最大1000个待发送事件</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebhookService {

    /** 事件队列最大容量 */
    public static final int MAX_QUEUE_SIZE = 1000;

    /** 最大历史事件记录数 */
    public static final int MAX_HISTORY_SIZE = 500;

    /** 默认重试次数 */
    private static final int DEFAULT_RETRY_COUNT = 3;

    /** 指数退避基础延迟（毫秒） */
    private static final long BASE_BACKOFF_MS = 1000;

    /** 签名头部名称 */
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

    /** 事件ID头部名称 */
    public static final String EVENT_ID_HEADER = "X-Webhook-Event-Id";

    /** 事件类型头部名称 */
    public static final String EVENT_TYPE_HEADER = "X-Webhook-Event-Type";

    /** 事件时间戳头部名称 */
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";

    /** 配置存储：configId -> WebhookConfig */
    private final Map<String, WebhookConfig> configs = new ConcurrentHashMap<>();

    /** 事件队列（阻塞队列） */
    private final BlockingQueue<WebhookEvent> eventQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    /** 事件历史（最近发送的事件记录） */
    private final List<WebhookEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());

    /** 异步执行器 */
    private final ExecutorService executorService;

    /** 消费线程 */
    private Thread consumerThread;

    /** 是否运行中 */
    private volatile boolean running = false;

    /** 自定义HTTP客户端（可选，用于测试） */
    private HttpClient customHttpClient;

    /** 统计计数器 */
    private final java.util.concurrent.atomic.AtomicLong totalEventsTriggered = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalEventsSent = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalEventsFailed = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 构造WebhookService
     */
    public WebhookService() {
        this.executorService = new ThreadPoolExecutor(
                2, 5,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "Webhook-Sender");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        System.out.println("[WebhookService] 初始化完成");
    }

    /**
     * 启动服务
     */
    public void start() {
        if (running) return;
        running = true;

        consumerThread = new Thread(this::consumeEvents, "Webhook-Consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        System.out.println("[WebhookService] 服务已启动，事件队列容量: " + MAX_QUEUE_SIZE);
    }

    /**
     * 停止服务
     */
    public void stop() {
        running = false;

        // 中断消费线程
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 关闭执行器
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("[WebhookService] 服务已停止");
    }

    // ==================== 配置管理 ====================

    /**
     * 创建Webhook配置
     *
     * @param config Webhook配置
     * @return 创建的配置（包含生成的ID）
     */
    public WebhookConfig createConfig(WebhookConfig config) {
        if (config.getId() == null || config.getId().isBlank()) {
            config.setId(UUID.randomUUID().toString());
        }
        configs.put(config.getId(), config);
        System.out.println("[WebhookService] 创建Webhook配置: " + config);
        return config;
    }

    /**
     * 获取所有Webhook配置
     *
     * @return 配置列表
     */
    public List<WebhookConfig> getAllConfigs() {
        return new ArrayList<>(configs.values());
    }

    /**
     * 获取指定ID的Webhook配置
     *
     * @param id 配置ID
     * @return 配置，不存在返回null
     */
    public WebhookConfig getConfig(String id) {
        return configs.get(id);
    }

    /**
     * 更新Webhook配置
     *
     * @param id     配置ID
     * @param config 新配置
     * @return 更新后的配置，不存在返回null
     */
    public WebhookConfig updateConfig(String id, WebhookConfig config) {
        WebhookConfig existing = configs.get(id);
        if (existing == null) {
            return null;
        }
        config.setId(id);
        configs.put(id, config);
        System.out.println("[WebhookService] 更新Webhook配置: " + config);
        return config;
    }

    /**
     * 删除Webhook配置
     *
     * @param id 配置ID
     * @return 是否删除成功
     */
    public boolean deleteConfig(String id) {
        WebhookConfig removed = configs.remove(id);
        if (removed != null) {
            System.out.println("[WebhookService] 删除Webhook配置: " + id);
            return true;
        }
        return false;
    }

    // ==================== 事件触发 ====================

    /**
     * 触发Webhook事件
     * <p>
     * 将事件添加到队列，由异步消费者处理。
     * </p>
     *
     * @param event Webhook事件
     * @return 是否成功入队
     */
    public boolean triggerEvent(WebhookEvent event) {
        if (event == null) return false;

        totalEventsTriggered.incrementAndGet();

        // 检查队列容量
        if (eventQueue.size() >= MAX_QUEUE_SIZE) {
            System.err.println("[WebhookService] 事件队列已满，丢弃事件: " + event.getEventId());
            return false;
        }

        boolean offered = eventQueue.offer(event);
        if (offered) {
            System.out.println("[WebhookService] 事件已入队: " + event.getEventType() + " (队列大小: " + eventQueue.size() + ")");
        } else {
            System.err.println("[WebhookService] 事件入队失败: " + event.getEventId());
        }
        return offered;
    }

    /**
     * 获取最近的事件历史
     *
     * @param limit 返回数量
     * @return 事件列表
     */
    public List<WebhookEvent> getRecentEvents(int limit) {
        synchronized (eventHistory) {
            int size = eventHistory.size();
            int from = Math.max(0, size - limit);
            return new ArrayList<>(eventHistory.subList(from, size));
        }
    }

    /**
     * 获取指定配置的事件历史
     *
     * @param configId 配置ID
     * @param limit    返回数量
     * @return 事件列表
     */
    public List<WebhookEvent> getEventsForConfig(String configId, int limit) {
        synchronized (eventHistory) {
            List<WebhookEvent> filtered = new ArrayList<>();
            for (int i = eventHistory.size() - 1; i >= 0 && filtered.size() < limit; i--) {
                WebhookEvent e = eventHistory.get(i);
                if (configId.equals(e.getWebhookConfigId())) {
                    filtered.add(e);
                }
            }
            return filtered;
        }
    }

    // ==================== 事件消费与发送 ====================

    /**
     * 事件消费循环
     */
    private void consumeEvents() {
        System.out.println("[WebhookService] 事件消费线程已启动");
        while (running) {
            try {
                WebhookEvent event = eventQueue.poll(1, TimeUnit.SECONDS);
                if (event == null) continue;

                // 找到匹配的配置并发送
                sendEventToConfigs(event);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[WebhookService] 事件消费异常: " + e.getMessage());
            }
        }
        System.out.println("[WebhookService] 事件消费线程已退出");
    }

    /**
     * 向匹配的配置发送事件
     */
    private void sendEventToConfigs(WebhookEvent event) {
        for (WebhookConfig config : configs.values()) {
            if (!config.isEnabled()) continue;
            if (!config.listensTo(event.getEventType())) continue;

            // 为每个配置创建独立的事件副本
            WebhookEvent configEvent = new WebhookEvent(event.getEventType(), event.getPayload());
            configEvent.setWebhookConfigId(config.getId());

            // 异步发送
            executorService.submit(() -> sendWithRetry(config, configEvent));
        }
    }

    /**
     * 带重试机制的发送
     */
    private void sendWithRetry(WebhookConfig config, WebhookEvent event) {
        int maxRetries = config.getRetryCount();
        event.setSendStatus(WebhookEvent.SendStatus.SENDING);

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            event.incrementAttemptCount();

            try {
                boolean success = sendHttpRequest(config, event);
                if (success) {
                    event.setSendStatus(WebhookEvent.SendStatus.SUCCESS);
                    event.setSentAt(System.currentTimeMillis());
                    totalEventsSent.incrementAndGet();
                    recordEvent(event);
                    System.out.println("[WebhookService] Webhook发送成功: " + config.getUrl()
                            + " (事件: " + event.getEventType() + ", 尝试: " + attempt + ")");
                    return;
                }
                event.setLastError("HTTP请求返回非2xx状态码");
            } catch (Exception e) {
                event.setLastError(e.getMessage());
                System.err.println("[WebhookService] Webhook发送失败 (尝试 " + attempt + "/" + (maxRetries + 1) + "): "
                        + config.getUrl() + " - " + e.getMessage());
            }

            // 如果不是最后一次尝试，等待指数退避
            if (attempt <= maxRetries) {
                event.setSendStatus(WebhookEvent.SendStatus.RETRYING);
                long backoff = calculateBackoff(attempt);
                System.out.println("[WebhookService] 等待重试: " + backoff + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // 所有重试失败
        event.setSendStatus(WebhookEvent.SendStatus.FAILED);
        totalEventsFailed.incrementAndGet();
        recordEvent(event);
        System.err.println("[WebhookService] Webhook最终发送失败: " + config.getUrl()
                + " (事件: " + event.getEventType() + ", 错误: " + event.getLastError() + ")");
    }

    /**
     * 计算指数退避延迟
     *
     * @param attempt 当前尝试次数
     * @return 延迟毫秒数
     */
    private long calculateBackoff(int attempt) {
        // 指数退避: 1s, 2s, 4s, 8s, ...
        return BASE_BACKOFF_MS * (1L << (attempt - 1));
    }

    /**
     * 发送HTTP请求
     *
     * @param config Webhook配置
     * @param event  Webhook事件
     * @return 是否成功（HTTP 2xx）
     */
    private boolean sendHttpRequest(WebhookConfig config, WebhookEvent event) throws Exception {
        // 如果设置了自定义HTTP客户端，使用它（用于测试）
        if (customHttpClient != null) {
            return customHttpClient.send(config, event);
        }

        String payload = event.toJsonString();
        String signature = config.getSecret() != null && !config.getSecret().isBlank()
                ? computeHmacSha256(config.getSecret(), payload) : null;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(config.getUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getTimeout());
            conn.setReadTimeout(config.getTimeout() * 2); // 读取超时为连接超时的2倍
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "AgentMemoryPlatform-Webhook/1.0");
            conn.setRequestProperty("Accept", "application/json");

            // 设置自定义头部
            conn.setRequestProperty(EVENT_ID_HEADER, event.getEventId());
            conn.setRequestProperty(EVENT_TYPE_HEADER, event.getEventType());
            conn.setRequestProperty(TIMESTAMP_HEADER, String.valueOf(event.getTimestamp()));

            // 设置HMAC签名
            if (signature != null) {
                conn.setRequestProperty(SIGNATURE_HEADER, "sha256=" + signature);
            }

            // 发送请求体
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 300;

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================== HMAC签名 ====================

    /**
     * 计算HMAC-SHA256签名
     *
     * @param secret  密钥
     * @param payload 载荷
     * @return 十六进制签名字符串
     */
    public static String computeHmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            // 转换为十六进制
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            System.err.println("[WebhookService] HMAC签名计算失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 验证HMAC-SHA256签名
     *
     * @param secret        密钥
     * @param payload       载荷
     * @param expectedSig   期望的签名
     * @return 签名是否匹配
     */
    public static boolean verifyHmacSha256(String secret, String payload, String expectedSig) {
        String computed = computeHmacSha256(secret, payload);
        return computed.equals(expectedSig);
    }

    // ==================== 测试 ====================

    /**
     * 测试Webhook连接
     *
     * @param configId Webhook配置ID
     * @return 测试结果
     */
    public TestResult testWebhook(String configId) {
        WebhookConfig config = configs.get(configId);
        if (config == null) {
            return new TestResult(false, "Webhook配置不存在: " + configId, 0);
        }

        // 创建测试事件
        WebhookEvent testEvent = new WebhookEvent("WEBHOOK_TEST", Map.of(
                "message", "This is a test webhook event",
                "configId", configId,
                "configName", config.getName() != null ? config.getName() : "unnamed"
        ));
        testEvent.setWebhookConfigId(configId);

        long startTime = System.currentTimeMillis();
        try {
            boolean success = sendHttpRequest(config, testEvent);
            long duration = System.currentTimeMillis() - startTime;
            if (success) {
                return new TestResult(true, "Webhook连接测试成功", duration);
            } else {
                return new TestResult(false, "Webhook返回非2xx状态码", duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new TestResult(false, "Webhook连接测试失败: " + e.getMessage(), duration);
        }
    }

    // ==================== 统计 ====================

    /**
     * 获取服务统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("configCount", configs.size());
        stats.put("queueSize", eventQueue.size());
        stats.put("queueCapacity", MAX_QUEUE_SIZE);
        stats.put("totalEventsTriggered", totalEventsTriggered.get());
        stats.put("totalEventsSent", totalEventsSent.get());
        stats.put("totalEventsFailed", totalEventsFailed.get());
        stats.put("historySize", eventHistory.size());
        stats.put("running", running);

        // 按状态统计
        Map<String, Long> statusCounts = new HashMap<>();
        synchronized (eventHistory) {
            for (WebhookEvent e : eventHistory) {
                statusCounts.merge(e.getSendStatus().name(), 1L, Long::sum);
            }
        }
        stats.put("statusCounts", statusCounts);

        return stats;
    }

    // ==================== 辅助方法 ====================

    /**
     * 记录事件到历史
     */
    private void recordEvent(WebhookEvent event) {
        eventHistory.add(event);
        // 限制历史记录大小
        while (eventHistory.size() > MAX_HISTORY_SIZE) {
            eventHistory.remove(0);
        }
    }

    /**
     * 设置自定义HTTP客户端（用于测试）
     */
    public void setCustomHttpClient(HttpClient httpClient) {
        this.customHttpClient = httpClient;
    }

    /**
     * 获取配置数量
     */
    public int getConfigCount() {
        return configs.size();
    }

    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * HTTP客户端接口（可替换为Mock实现用于测试）
     */
    public interface HttpClient {
        boolean send(WebhookConfig config, WebhookEvent event) throws Exception;
    }

    /**
     * Webhook测试结果
     */
    public static class TestResult {
        private final boolean success;
        private final String message;
        private final long durationMs;

        public TestResult(boolean success, String message, long durationMs) {
            this.success = success;
            this.message = message;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public long getDurationMs() { return durationMs; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("success", success);
            map.put("message", message);
            map.put("durationMs", durationMs);
            return map;
        }
    }
}
