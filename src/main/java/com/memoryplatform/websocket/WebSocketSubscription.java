package com.memoryplatform.websocket;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * WebSocket订阅管理
 * <p>
 * 定义订阅过滤规则，支持按事件类型和用户/Agent进行过滤。
 * </p>
 *
 * <h3>订阅频道类型</h3>
 * <ul>
 *   <li>{@code MEMORY_EVENTS} - 仅接收记忆相关事件（创建/更新/删除/搜索）</li>
 *   <li>{@code STATS_EVENTS}  - 仅接收统计信息更新</li>
 *   <li>{@code ALL}          - 接收所有事件</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 订阅所有事件
 * WebSocketSubscription sub = WebSocketSubscription.all("client-1");
 *
 * // 仅订阅记忆事件，过滤特定用户
 * WebSocketSubscription sub = WebSocketSubscription.builder("client-2")
 *     .channel(Channel.MEMORY_EVENTS)
 *     .userId("user-123")
 *     .build();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebSocketSubscription {

    // ==================== 订阅频道枚举 ====================

    /**
     * 订阅频道类型
     */
    public enum Channel {
        /** 记忆相关事件: MEMORY_CREATED, MEMORY_UPDATED, MEMORY_DELETED, MEMORY_SEARCHED */
        MEMORY_EVENTS,
        /** 统计信息事件: STATS_UPDATE */
        STATS_EVENTS,
        /** 所有事件 */
        ALL
    }

    // ==================== 字段 ====================

    /** 订阅ID（唯一标识） */
    private final String subscriptionId;

    /** 所属客户端连接ID */
    private final String connectionId;

    /** 订阅频道 */
    private final Channel channel;

    /** 过滤的用户ID（null表示不过滤） */
    private final String userId;

    /** 过滤的Agent ID（null表示不过滤） */
    private final String agentId;

    /** 订阅创建时间 */
    private final Instant createdAt;

    /** 该订阅关心的消息类型集合 */
    private final Set<WebSocketMessage.Type> interestedTypes;

    // ==================== 构造函数 ====================

    private WebSocketSubscription(Builder builder) {
        this.subscriptionId = builder.subscriptionId;
        this.connectionId = builder.connectionId;
        this.channel = builder.channel;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.createdAt = Instant.now();
        this.interestedTypes = resolveInterestedTypes(builder.channel);
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建全量订阅（接收所有事件，无过滤）
     *
     * @param connectionId 客户端连接ID
     * @return 订阅实例
     */
    public static WebSocketSubscription all(String connectionId) {
        return builder(connectionId)
                .channel(Channel.ALL)
                .build();
    }

    /**
     * 创建记忆事件订阅
     *
     * @param connectionId 客户端连接ID
     * @return 订阅实例
     */
    public static WebSocketSubscription memoryEvents(String connectionId) {
        return builder(connectionId)
                .channel(Channel.MEMORY_EVENTS)
                .build();
    }

    /**
     * 创建统计事件订阅
     *
     * @param connectionId 客户端连接ID
     * @return 订阅实例
     */
    public static WebSocketSubscription statsEvents(String connectionId) {
        return builder(connectionId)
                .channel(Channel.STATS_EVENTS)
                .build();
    }

    /**
     * 创建构建器
     *
     * @param connectionId 客户端连接ID
     * @return 构建器实例
     */
    public static Builder builder(String connectionId) {
        return new Builder(connectionId);
    }

    // ==================== 匹配逻辑 ====================

    /**
     * 检查消息是否匹配该订阅
     *
     * @param message 待检查的消息
     * @return 如果匹配返回true
     */
    public boolean matches(WebSocketMessage message) {
        if (message == null) return false;

        // 1. 检查消息类型是否在关注列表中
        if (!interestedTypes.contains(message.getType())) {
            return false;
        }

        // 2. 检查用户过滤
        if (userId != null && message.getData().containsKey("userId")) {
            String msgUserId = (String) message.getData().get("userId");
            if (!userId.equals(msgUserId)) {
                return false;
            }
        }

        // 3. 检查Agent过滤
        if (agentId != null && message.getData().containsKey("agentId")) {
            String msgAgentId = (String) message.getData().get("agentId");
            if (!agentId.equals(msgAgentId)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 根据频道类型解析感兴趣的消息类型集合
     */
    private static Set<WebSocketMessage.Type> resolveInterestedTypes(Channel channel) {
        switch (channel) {
            case MEMORY_EVENTS:
                return EnumSet.of(
                        WebSocketMessage.Type.MEMORY_CREATED,
                        WebSocketMessage.Type.MEMORY_UPDATED,
                        WebSocketMessage.Type.MEMORY_DELETED,
                        WebSocketMessage.Type.MEMORY_SEARCHED
                );
            case STATS_EVENTS:
                return EnumSet.of(WebSocketMessage.Type.STATS_UPDATE);
            case ALL:
            default:
                return EnumSet.allOf(WebSocketMessage.Type.class);
        }
    }

    // ==================== Getter ====================

    public String getSubscriptionId() { return subscriptionId; }
    public String getConnectionId() { return connectionId; }
    public Channel getChannel() { return channel; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return String.format("WebSocketSubscription{id=%s, conn=%s, channel=%s, userId=%s, agentId=%s}",
                subscriptionId, connectionId, channel, userId, agentId);
    }

    // ==================== 构建器 ====================

    /**
     * WebSocket订阅构建器
     */
    public static class Builder {
        private final String connectionId;
        private String subscriptionId;
        private Channel channel = Channel.ALL;
        private String userId;
        private String agentId;

        private Builder(String connectionId) {
            this.connectionId = connectionId;
            this.subscriptionId = "sub-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        public Builder subscriptionId(String id) {
            this.subscriptionId = id;
            return this;
        }

        public Builder channel(Channel channel) {
            this.channel = channel;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public WebSocketSubscription build() {
            if (connectionId == null || connectionId.isBlank()) {
                throw new IllegalArgumentException("connectionId cannot be null or empty");
            }
            return new WebSocketSubscription(this);
        }
    }
}
