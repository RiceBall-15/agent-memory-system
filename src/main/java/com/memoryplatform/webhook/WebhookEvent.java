package com.memoryplatform.webhook;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook事件对象
 * <p>
 * 表示一次Webhook事件，包含事件类型、载荷数据和时间戳。
 * 由WebhookService在触发事件时创建。
 * </p>
 *
 * <h3>支持的事件类型</h3>
 * <ul>
 *   <li>{@code MEMORY_CREATED} - 记忆创建</li>
 *   <li>{@code MEMORY_UPDATED} - 记忆更新</li>
 *   <li>{@code MEMORY_DELETED} - 记忆删除</li>
 *   <li>{@code MEMORY_SHARED} - 记忆共享</li>
 *   <li>{@code MEMORY_EXPIRED} - 记忆过期</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebhookEvent {

    /** 事件类型常量 */
    public static final String MEMORY_CREATED = "MEMORY_CREATED";
    public static final String MEMORY_UPDATED = "MEMORY_UPDATED";
    public static final String MEMORY_DELETED = "MEMORY_DELETED";
    public static final String MEMORY_SHARED = "MEMORY_SHARED";
    public static final String MEMORY_EXPIRED = "MEMORY_EXPIRED";

    /** 所有支持的事件类型 */
    public static final String[] ALL_EVENT_TYPES = {
            MEMORY_CREATED, MEMORY_UPDATED, MEMORY_DELETED, MEMORY_SHARED, MEMORY_EXPIRED
    };

    /** 事件ID */
    private final String eventId;

    /** 事件类型 */
    private final String eventType;

    /** 事件载荷 */
    private final Map<String, Object> payload;

    /** 事件时间戳（Unix毫秒） */
    private final long timestamp;

    /** 事件发生时间（ISO字符串） */
    private final String timestampISO;

    /** 目标Webhook配置ID（用于记录） */
    private String webhookConfigId;

    /** 发送状态 */
    private SendStatus sendStatus;

    /** 发送尝试次数 */
    private int attemptCount;

    /** 最后一次发送的错误信息 */
    private String lastError;

    /** 发送成功的时间戳 */
    private long sentAt;

    /**
     * 构造事件
     *
     * @param eventType 事件类型
     * @param payload   事件载荷
     */
    public WebhookEvent(String eventType, Map<String, Object> payload) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.payload = payload != null ? new HashMap<>(payload) : new HashMap<>();
        this.timestamp = System.currentTimeMillis();
        this.timestampISO = Instant.ofEpochMilli(this.timestamp).toString();
        this.sendStatus = SendStatus.PENDING;
        this.attemptCount = 0;
    }

    /**
     * 创建MEMORY_CREATED事件
     *
     * @param memoryId 记忆ID
     * @param userId   用户ID
     * @param agentId  Agent ID
     * @return 事件实例
     */
    public static WebhookEvent memoryCreated(String memoryId, String userId, String agentId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memoryId);
        payload.put("userId", userId);
        payload.put("agentId", agentId);
        return new WebhookEvent(MEMORY_CREATED, payload);
    }

    /**
     * 创建MEMORY_UPDATED事件
     *
     * @param memoryId 记忆ID
     * @param fields   更新的字段列表
     * @return 事件实例
     */
    public static WebhookEvent memoryUpdated(String memoryId, java.util.Set<String> fields) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memoryId);
        payload.put("updatedFields", fields != null ? new java.util.ArrayList<>(fields) : java.util.Collections.emptyList());
        return new WebhookEvent(MEMORY_UPDATED, payload);
    }

    /**
     * 创建MEMORY_DELETED事件
     *
     * @param memoryId 记忆ID
     * @return 事件实例
     */
    public static WebhookEvent memoryDeleted(String memoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memoryId);
        return new WebhookEvent(MEMORY_DELETED, payload);
    }

    /**
     * 创建MEMORY_SHARED事件
     *
     * @param memoryId       记忆ID
     * @param targetAgentId  目标Agent ID
     * @param mode           共享模式
     * @return 事件实例
     */
    public static WebhookEvent memoryShared(String memoryId, String targetAgentId, String mode) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memoryId);
        payload.put("targetAgentId", targetAgentId);
        payload.put("mode", mode);
        return new WebhookEvent(MEMORY_SHARED, payload);
    }

    /**
     * 创建MEMORY_EXPIRED事件
     *
     * @param memoryId 记忆ID
     * @param userId   用户ID
     * @return 事件实例
     */
    public static WebhookEvent memoryExpired(String memoryId, String userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memoryId", memoryId);
        payload.put("userId", userId);
        return new WebhookEvent(MEMORY_EXPIRED, payload);
    }

    // ==================== Getters & Setters ====================

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public long getTimestamp() { return timestamp; }
    public String getTimestampISO() { return timestampISO; }
    public String getWebhookConfigId() { return webhookConfigId; }
    public void setWebhookConfigId(String webhookConfigId) { this.webhookConfigId = webhookConfigId; }
    public SendStatus getSendStatus() { return sendStatus; }
    public void setSendStatus(SendStatus sendStatus) { this.sendStatus = sendStatus; }
    public int getAttemptCount() { return attemptCount; }
    public void incrementAttemptCount() { this.attemptCount++; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public long getSentAt() { return sentAt; }
    public void setSentAt(long sentAt) { this.sentAt = sentAt; }

    /**
     * 转换为Map（用于JSON序列化和发送载荷）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("eventType", eventType);
        map.put("timestamp", timestamp);
        map.put("timestampISO", timestampISO);
        map.put("payload", payload);
        return map;
    }

    /**
     * 获取发送载荷的JSON字符串（用于签名和发送）
     */
    public String toJsonString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"eventId\":\"").append(escapeJson(eventId)).append("\",");
        sb.append("\"eventType\":\"").append(escapeJson(eventType)).append("\",");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"timestampISO\":\"").append(escapeJson(timestampISO)).append("\",");
        sb.append("\"payload\":").append(mapToJson(payload));
        sb.append("}");
        return sb.toString();
    }

    /**
     * JSON转义字符串
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Map转JSON字符串（简单实现）
     */
    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number) {
                sb.append(value);
            } else if (value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof java.util.List) {
                sb.append("[");
                boolean innerFirst = true;
                for (Object item : (java.util.List<?>) value) {
                    if (!innerFirst) sb.append(",");
                    innerFirst = false;
                    sb.append("\"").append(escapeJson(String.valueOf(item))).append("\"");
                }
                sb.append("]");
            } else if (value instanceof java.util.Set) {
                sb.append("[");
                boolean innerFirst = true;
                for (Object item : (java.util.Set<?>) value) {
                    if (!innerFirst) sb.append(",");
                    innerFirst = false;
                    sb.append("\"").append(escapeJson(String.valueOf(item))).append("\"");
                }
                sb.append("]");
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "WebhookEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", timestamp=" + timestampISO +
                ", status=" + sendStatus +
                ", attempts=" + attemptCount +
                '}';
    }

    /**
     * 发送状态枚举
     */
    public enum SendStatus {
        PENDING, SENDING, SUCCESS, FAILED, RETRYING
    }
}
