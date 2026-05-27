package com.memoryplatform.websocket;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket消息模型
 * <p>
 * 定义WebSocket通信的消息格式和类型枚举。所有消息都遵循统一格式：
 * <pre>{@code
 * {
 *   "type": "MEMORY_CREATED",
 *   "data": {...},
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * }</pre>
 * </p>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebSocketMessage {

    // ==================== 消息类型枚举 ====================

    /**
     * 消息类型枚举
     */
    public enum Type {
        /** 记忆创建事件 */
        MEMORY_CREATED,
        /** 记忆更新事件 */
        MEMORY_UPDATED,
        /** 记忆删除事件 */
        MEMORY_DELETED,
        /** 记忆搜索事件 */
        MEMORY_SEARCHED,
        /** 统计信息更新 */
        STATS_UPDATE,
        /** 心跳/存活检测 */
        HEARTBEAT,
        /** 订阅确认 */
        SUBSCRIPTION_ACK,
        /** 错误消息 */
        ERROR,
        /** 通用信息 */
        INFO
    }

    // ==================== 字段 ====================

    /** 消息类型 */
    private final Type type;

    /** 消息数据（任意JSON结构） */
    private final Map<String, Object> data;

    /** 消息时间戳 */
    private final String timestamp;

    // ==================== 构造函数 ====================

    /**
     * 创建WebSocket消息
     *
     * @param type 消息类型
     * @param data 消息数据
     */
    public WebSocketMessage(Type type, Map<String, Object> data) {
        this.type = type;
        this.data = data != null ? data : new HashMap<>();
        this.timestamp = Instant.now().toString();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建记忆创建事件消息
     *
     * @param memoryId 记忆ID
     * @param userId   用户ID
     * @param agentId  Agent ID
     * @return 消息实例
     */
    public static WebSocketMessage memoryCreated(String memoryId, String userId, String agentId) {
        Map<String, Object> data = new HashMap<>();
        data.put("memoryId", memoryId);
        data.put("userId", userId);
        data.put("agentId", agentId);
        return new WebSocketMessage(Type.MEMORY_CREATED, data);
    }

    /**
     * 创建记忆更新事件消息
     *
     * @param memoryId 记忆ID
     * @param fields   更新的字段列表
     * @return 消息实例
     */
    public static WebSocketMessage memoryUpdated(String memoryId, Object fields) {
        Map<String, Object> data = new HashMap<>();
        data.put("memoryId", memoryId);
        data.put("fields", fields);
        return new WebSocketMessage(Type.MEMORY_UPDATED, data);
    }

    /**
     * 创建记忆删除事件消息
     *
     * @param memoryId 记忆ID
     * @return 消息实例
     */
    public static WebSocketMessage memoryDeleted(String memoryId) {
        Map<String, Object> data = new HashMap<>();
        data.put("memoryId", memoryId);
        return new WebSocketMessage(Type.MEMORY_DELETED, data);
    }

    /**
     * 创建记忆搜索事件消息
     *
     * @param query       搜索关键词
     * @param resultCount 结果数量
     * @return 消息实例
     */
    public static WebSocketMessage memorySearched(String query, int resultCount) {
        Map<String, Object> data = new HashMap<>();
        data.put("query", query);
        data.put("resultCount", resultCount);
        return new WebSocketMessage(Type.MEMORY_SEARCHED, data);
    }

    /**
     * 创建统计信息更新消息
     *
     * @param stats 统计数据
     * @return 消息实例
     */
    public static WebSocketMessage statsUpdate(Map<String, Object> stats) {
        return new WebSocketMessage(Type.STATS_UPDATE, stats);
    }

    /**
     * 创建心跳消息
     *
     * @return 消息实例
     */
    public static WebSocketMessage heartbeat() {
        return new WebSocketMessage(Type.HEARTBEAT, new HashMap<>());
    }

    /**
     * 创建订阅确认消息
     *
     * @param subscriptionId 订阅ID
     * @param channel        订阅频道
     * @return 消息实例
     */
    public static WebSocketMessage subscriptionAck(String subscriptionId, String channel) {
        Map<String, Object> data = new HashMap<>();
        data.put("subscriptionId", subscriptionId);
        data.put("channel", channel);
        data.put("status", "subscribed");
        return new WebSocketMessage(Type.SUBSCRIPTION_ACK, data);
    }

    /**
     * 创建错误消息
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @return 消息实例
     */
    public static WebSocketMessage error(int errorCode, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("code", errorCode);
        data.put("message", message);
        return new WebSocketMessage(Type.ERROR, data);
    }

    // ==================== 序列化 ====================

    /**
     * 转换为JSON字符串
     *
     * @return JSON字符串
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(type.name()).append("\"");
        sb.append(",\"data\":").append(mapToJson(data));
        sb.append(",\"timestamp\":\"").append(timestamp).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 简易Map转JSON（避免依赖外部库）
     */
    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) val;
                sb.append(mapToJson(nested));
            } else {
                sb.append("\"").append(escapeJson(val.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== Getter ====================

    public Type getType() { return type; }
    public Map<String, Object> getData() { return data; }
    public String getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return toJson();
    }
}
