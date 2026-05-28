package com.memoryplatform.webhook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebhookService 单元测试
 * <p>
 * 测试Webhook服务的核心功能：配置CRUD、HMAC签名、事件触发、重试机制、异步队列。
 * 不依赖外部HTTP服务，使用Mock HttpClient模拟网络请求。
 * </p>
 */
class WebhookServiceTest {

    private WebhookService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        service = new WebhookService(executor);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        executor.shutdownNow();
    }

    // ==================== 配置管理 CRUD ====================

    @Test
    void testCreateConfig_BasicCreation() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .secret("my-secret")
                .events(List.of("MEMORY_CREATED", "MEMORY_UPDATED"))
                .enabled(true)
                .retryCount(3)
                .timeout(5000)
                .name("test-webhook")
                .description("测试Webhook")
                .build();

        WebhookConfig created = service.createConfig(config);

        assertNotNull(created);
        assertNotNull(created.getId(), "应自动生成ID");
        assertEquals("https://example.com/webhook", created.getUrl());
        assertEquals("my-secret", created.getSecret());
        assertTrue(created.isEnabled());
        assertEquals(2, created.getEvents().size());
    }

    @Test
    void testCreateConfig_UsesProvidedId() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("custom-id-123")
                .build();

        WebhookConfig created = service.createConfig(config);

        assertEquals("custom-id-123", created.getId());
    }

    @Test
    void testGetAllConfigs_ReturnsAllCreated() {
        service.createConfig(WebhookConfig.builder()
                .url("https://example.com/hook1").build());
        service.createConfig(WebhookConfig.builder()
                .url("https://example.com/hook2").build());

        List<WebhookConfig> configs = service.getAllConfigs();

        assertEquals(2, configs.size());
    }

    @Test
    void testGetConfig_ReturnsCorrectConfig() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-1")
                .name("my-hook")
                .build();
        service.createConfig(config);

        WebhookConfig retrieved = service.getConfig("cfg-1");

        assertNotNull(retrieved);
        assertEquals("my-hook", retrieved.getName());
    }

    @Test
    void testGetConfig_NonExistent_ReturnsNull() {
        assertNull(service.getConfig("non-existent"));
    }

    @Test
    void testUpdateConfig_UpdatesFields() {
        WebhookConfig original = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-1")
                .name("old-name")
                .build();
        service.createConfig(original);

        WebhookConfig updated = WebhookConfig.builder()
                .url("https://new-url.com/webhook")
                .name("new-name")
                .enabled(false)
                .build();

        WebhookConfig result = service.updateConfig("cfg-1", updated);

        assertNotNull(result);
        assertEquals("cfg-1", result.getId(), "ID应保持不变");
        assertEquals("new-name", result.getName());
        assertEquals("https://new-url.com/webhook", result.getUrl());
        assertFalse(result.isEnabled());
    }

    @Test
    void testUpdateConfig_NonExistent_ReturnsNull() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook").build();

        assertNull(service.updateConfig("non-existent", config));
    }

    @Test
    void testDeleteConfig_Existing_ReturnsTrue() {
        service.createConfig(WebhookConfig.builder()
                .url("https://example.com/webhook").id("cfg-1").build());

        assertTrue(service.deleteConfig("cfg-1"));
        assertNull(service.getConfig("cfg-1"));
    }

    @Test
    void testDeleteConfig_NonExistent_ReturnsFalse() {
        assertFalse(service.deleteConfig("non-existent"));
    }

    // ==================== HMAC-SHA256 签名 ====================

    @Test
    void testComputeHmacSha256_ProducesValidHexSignature() {
        String secret = "my-secret-key";
        String payload = "{\"eventType\":\"MEMORY_CREATED\"}";

        String signature = WebhookService.computeHmacSha256(secret, payload);

        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // HMAC-SHA256输出应为64个十六进制字符
        assertEquals(64, signature.length());
        assertTrue(signature.matches("[0-9a-f]{64}"), "签名应为64位十六进制字符串");
    }

    @Test
    void testComputeHmacSha256_DeterministicForSameInput() {
        String secret = "test-secret";
        String payload = "test-payload";

        String sig1 = WebhookService.computeHmacSha256(secret, payload);
        String sig2 = WebhookService.computeHmacSha256(secret, payload);

        assertEquals(sig1, sig2, "相同输入应产生相同签名");
    }

    @Test
    void testComputeHmacSha256_DifferentSecretProducesDifferentSignature() {
        String payload = "test-payload";

        String sig1 = WebhookService.computeHmacSha256("secret-1", payload);
        String sig2 = WebhookService.computeHmacSha256("secret-2", payload);

        assertNotEquals(sig1, sig2, "不同密钥应产生不同签名");
    }

    @Test
    void testVerifyHmacSha256_ValidSignatureReturnsTrue() {
        String secret = "my-secret";
        String payload = "some-data";

        String signature = WebhookService.computeHmacSha256(secret, payload);

        assertTrue(WebhookService.verifyHmacSha256(secret, payload, signature));
    }

    @Test
    void testVerifyHmacSha256_InvalidSignatureReturnsFalse() {
        String secret = "my-secret";
        String payload = "some-data";

        assertFalse(WebhookService.verifyHmacSha256(secret, payload, "invalid-signature"));
    }

    // ==================== 事件触发 ====================

    @Test
    void testTriggerEvent_BasicQueuing() {
        WebhookEvent event = new WebhookEvent("MEMORY_CREATED",
                Map.of("memoryId", "mem1", "userId", "user1"));

        boolean result = service.triggerEvent(event);

        assertTrue(result, "事件应成功入队");
        assertEquals(1, service.getQueueSize());
    }

    @Test
    void testTriggerEvent_NullEvent_ReturnsFalse() {
        assertFalse(service.triggerEvent(null));
    }

    @Test
    void testTriggerEvent_AllEventTypesSupported() {
        // 测试所有事件类型都能正常创建并入队
        for (String eventType : WebhookEvent.ALL_EVENT_TYPES) {
            WebhookEvent event = new WebhookEvent(eventType, Map.of("test", true));
            assertTrue(service.triggerEvent(event),
                    "事件类型 " + eventType + " 应能成功入队");
        }
    }

    @Test
    void testEventFactoryMethods() {
        WebhookEvent created = WebhookEvent.memoryCreated("mem1", "user1", "agent1");
        assertEquals(WebhookEvent.MEMORY_CREATED, created.getEventType());
        assertEquals("mem1", created.getPayload().get("memoryId"));

        WebhookEvent updated = WebhookEvent.memoryUpdated("mem1", Set.of("text", "importance"));
        assertEquals(WebhookEvent.MEMORY_UPDATED, updated.getEventType());

        WebhookEvent deleted = WebhookEvent.memoryDeleted("mem1");
        assertEquals(WebhookEvent.MEMORY_DELETED, deleted.getEventType());

        WebhookEvent shared = WebhookEvent.memoryShared("mem1", "agent2", "READONLY");
        assertEquals(WebhookEvent.MEMORY_SHARED, shared.getEventType());

        WebhookEvent expired = WebhookEvent.memoryExpired("mem1", "user1");
        assertEquals(WebhookEvent.MEMORY_EXPIRED, expired.getEventType());
    }

    // ==================== 事件消费与发送 ====================

    @Test
    void testEventConsumption_SendsToMatchingConfigs() throws Exception {
        // 设置Mock HTTP客户端
        List<WebhookEvent> capturedEvents = new ArrayList<>();
        service.setCustomHttpClient((config, event) -> {
            capturedEvents.add(event);
            return true;
        });

        // 创建配置，监听MEMORY_CREATED事件
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-1")
                .events(List.of(WebhookEvent.MEMORY_CREATED))
                .build();
        service.createConfig(config);

        // 启动服务
        service.start();

        // 触发事件
        WebhookEvent event = WebhookEvent.memoryCreated("mem1", "user1", "agent1");
        service.triggerEvent(event);

        // 等待事件被消费和发送
        Thread.sleep(2000);

        assertFalse(capturedEvents.isEmpty(), "应有事件被发送");
        assertEquals(WebhookEvent.MEMORY_CREATED, capturedEvents.get(0).getEventType());
    }

    @Test
    void testEventConsumption_IgnoresDisabledConfigs() throws Exception {
        List<WebhookEvent> capturedEvents = new ArrayList<>();
        service.setCustomHttpClient((config, event) -> {
            capturedEvents.add(event);
            return true;
        });

        // 创建已禁用的配置
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-disabled")
                .enabled(false)
                .build();
        service.createConfig(config);

        service.start();
        service.triggerEvent(WebhookEvent.memoryCreated("mem1", "user1", "agent1"));

        Thread.sleep(2000);

        assertTrue(capturedEvents.isEmpty(), "已禁用的配置不应收到事件");
    }

    @Test
    void testEventConsumption_IgnoresNonMatchingEventTypes() throws Exception {
        List<WebhookEvent> capturedEvents = new ArrayList<>();
        service.setCustomHttpClient((config, event) -> {
            capturedEvents.add(event);
            return true;
        });

        // 配置只监听MEMORY_DELETED
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-1")
                .events(List.of(WebhookEvent.MEMORY_DELETED))
                .build();
        service.createConfig(config);

        service.start();
        // 触发MEMORY_CREATED（不匹配）
        service.triggerEvent(WebhookEvent.memoryCreated("mem1", "user1", "agent1"));

        Thread.sleep(2000);

        assertTrue(capturedEvents.isEmpty(), "不匹配的事件类型不应被发送");
    }

    @Test
    void testEventConsumption_WildcardListensToAll() throws Exception {
        List<WebhookEvent> capturedEvents = new ArrayList<>();
        service.setCustomHttpClient((config, event) -> {
            capturedEvents.add(event);
            return true;
        });

        // 配置监听所有事件
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-wildcard")
                .events(List.of("*"))
                .build();
        service.createConfig(config);

        service.start();
        service.triggerEvent(WebhookEvent.memoryUpdated("mem1", Set.of("text")));

        Thread.sleep(2000);

        assertFalse(capturedEvents.isEmpty(), "通配符配置应接收所有事件");
    }

    // ==================== 重试机制 ====================

    @Test
    void testRetryMechanism_RetriesOnFailure() throws Exception {
        List<WebhookEvent> capturedEvents = new ArrayList<>();
        int[] attemptCount = {0};

        service.setCustomHttpClient((config, event) -> {
            attemptCount[0]++;
            capturedEvents.add(event);
            // 第一次失败，第二次成功
            return attemptCount[0] >= 2;
        });

        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-retry")
                .retryCount(3)
                .timeout(1000)
                .build();
        service.createConfig(config);

        service.start();
        service.triggerEvent(WebhookEvent.memoryCreated("mem1", "user1", "agent1"));

        // 等待重试完成（重试有指数退避）
        Thread.sleep(5000);

        assertEquals(2, attemptCount[0], "应尝试2次（1次失败 + 1次成功）");
        assertFalse(capturedEvents.isEmpty(), "事件应最终发送成功");
        assertEquals(WebhookEvent.SendStatus.SUCCESS,
                capturedEvents.get(capturedEvents.size() - 1).getSendStatus());
    }

    @Test
    void testRetryMechanism_ExhaustsRetries() throws Exception {
        service.setCustomHttpClient((config, event) -> false); // 始终失败

        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-fail")
                .retryCount(1) // 只重试1次
                .timeout(1000)
                .build();
        service.createConfig(config);

        service.start();
        service.triggerEvent(WebhookEvent.memoryDeleted("mem1"));

        // 等待重试完成
        Thread.sleep(4000);

        // 检查事件历史
        List<WebhookEvent> history = service.getRecentEvents(10);
        assertFalse(history.isEmpty(), "应有事件记录");
        // 应该有FAILED状态的记录
        boolean hasFailed = history.stream()
                .anyMatch(e -> e.getSendStatus() == WebhookEvent.SendStatus.FAILED);
        assertTrue(hasFailed, "重试耗尽后应记录FAILED状态");
    }

    // ==================== WebhookConfig listensTo ====================

    @Test
    void testListensTo_EmptyEventsListMatchesAll() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .events(new ArrayList<>()) // 空列表
                .build();

        assertTrue(config.listensTo("MEMORY_CREATED"),
                "空事件列表应监听所有事件");
    }

    @Test
    void testListensTo_SpecificEventType() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .events(List.of("MEMORY_CREATED", "MEMORY_UPDATED"))
                .build();

        assertTrue(config.listensTo("MEMORY_CREATED"));
        assertTrue(config.listensTo("MEMORY_UPDATED"));
        assertFalse(config.listensTo("MEMORY_DELETED"));
    }

    // ==================== 统计 ====================

    @Test
    void testGetStats_ReturnsCorrectInformation() {
        service.createConfig(WebhookConfig.builder()
                .url("https://example.com/hook1").build());
        service.createConfig(WebhookConfig.builder()
                .url("https://example.com/hook2").build());

        service.triggerEvent(new WebhookEvent("MEMORY_CREATED", Map.of()));

        Map<String, Object> stats = service.getStats();

        assertNotNull(stats);
        assertEquals(2, stats.get("configCount"));
        assertEquals(1, stats.get("queueSize"));
        assertEquals(WebhookService.MAX_QUEUE_SIZE, stats.get("queueCapacity"));
        assertEquals(1L, stats.get("totalEventsTriggered"));
    }

    // ==================== WebhookEvent 属性 ====================

    @Test
    void testWebhookEvent_PropertiesAndSerialization() {
        WebhookEvent event = WebhookEvent.memoryCreated("mem1", "user1", "agent1");

        // 基本属性
        assertNotNull(event.getEventId());
        assertEquals(WebhookEvent.MEMORY_CREATED, event.getEventType());
        assertEquals("mem1", event.getPayload().get("memoryId"));
        assertTrue(event.getTimestamp() > 0);
        assertNotNull(event.getTimestampISO());
        assertEquals(WebhookEvent.SendStatus.PENDING, event.getSendStatus());
        assertEquals(0, event.getAttemptCount());

        // 转换为Map
        Map<String, Object> map = event.toMap();
        assertNotNull(map);
        assertEquals(event.getEventId(), map.get("eventId"));
        assertEquals(WebhookEvent.MEMORY_CREATED, map.get("eventType"));

        // JSON字符串
        String json = event.toJsonString();
        assertNotNull(json);
        assertTrue(json.contains("MEMORY_CREATED"));
        assertTrue(json.contains("mem1"));
    }

    @Test
    void testWebhookEvent_AttemptCountIncrement() {
        WebhookEvent event = new WebhookEvent("TEST", Map.of());

        assertEquals(0, event.getAttemptCount());
        event.incrementAttemptCount();
        assertEquals(1, event.getAttemptCount());
        event.incrementAttemptCount();
        assertEquals(2, event.getAttemptCount());
    }

    // ==================== WebhookConfig Builder 验证 ====================

    @Test
    void testWebhookConfig_BuilderRequiresUrl() {
        assertThrows(IllegalStateException.class, () ->
                WebhookConfig.builder().build(),
                "缺少URL时应抛出异常"
        );
    }

    @Test
    void testWebhookConfig_BuilderFromExisting() {
        WebhookConfig original = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .name("original")
                .secret("secret")
                .events(List.of("MEMORY_CREATED"))
                .retryCount(5)
                .build();

        WebhookConfig copy = WebhookConfig.builder(original)
                .name("copy")
                .build();

        assertEquals("copy", copy.getName());
        assertEquals("https://example.com/webhook", copy.getUrl());
        assertEquals(5, copy.getRetryCount());
    }

    // ==================== 事件历史 ====================

    @Test
    void testGetRecentEvents_ReturnsLimitedResults() {
        service.setCustomHttpClient((config, event) -> true);

        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .id("cfg-history")
                .build();
        service.createConfig(config);

        service.start();

        // 触发多个事件
        for (int i = 0; i < 5; i++) {
            service.triggerEvent(new WebhookEvent("MEMORY_CREATED", Map.of("i", i)));
        }

        // 等待处理
        Thread.sleep(3000);

        List<WebhookEvent> recent = service.getRecentEvents(3);
        assertTrue(recent.size() <= 3, "应返回不超过3条记录");
    }

    @Test
    void testWebhookConfig_RetryCountLimits() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .retryCount(15) // 超过MAX_RETRY_COUNT(10)
                .build();

        assertEquals(WebhookConfig.MAX_RETRY_COUNT, config.getRetryCount(),
                "重试次数应被限制在MAX_RETRY_COUNT以内");
    }

    @Test
    void testWebhookConfig_TimeoutLimits() {
        WebhookConfig config = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .timeout(100) // 低于最小值1000
                .build();

        assertEquals(1000, config.getTimeout(),
                "超时时间应被限制在最小值1000ms");

        WebhookConfig config2 = WebhookConfig.builder()
                .url("https://example.com/webhook")
                .timeout(60000) // 超过最大值30000
                .build();

        assertEquals(30000, config2.getTimeout(),
                "超时时间应被限制在最大值30000ms");
    }
}
