package com.memoryplatform.webhook;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhook配置对象
 * <p>
 * 定义Webhook的连接参数，包括目标URL、安全密钥、监听事件类型等。
 * 使用Builder模式构建，支持链式调用。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * WebhookConfig config = WebhookConfig.builder()
 *     .url("https://example.com/webhook")
 *     .secret("my-secret-key")
 *     .events(List.of("MEMORY_CREATED", "MEMORY_UPDATED"))
 *     .enabled(true)
 *     .retryCount(3)
 *     .timeout(5000)
 *     .build();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class WebhookConfig {

    /** 配置ID（自动生成） */
    private String id;

    /** Webhook目标URL */
    private String url;

    /** HMAC签名密钥（可选，用于验证Webhook来源） */
    private String secret;

    /** 监听的事件类型列表 */
    private List<String> events;

    /** 是否启用 */
    private boolean enabled;

    /** 失败后重试次数（默认3次） */
    private int retryCount;

    /** HTTP连接超时时间（毫秒，默认5000） */
    private int timeout;

    /** 创建时间 */
    private long createdAt;

    /** 更新时间 */
    private long updatedAt;

    /** 描述信息 */
    private String description;

    /** Webhook名称 */
    private String name;

    /** 最大重试次数上限 */
    public static final int MAX_RETRY_COUNT = 10;

    /** 默认超时时间（毫秒） */
    public static final int DEFAULT_TIMEOUT = 5000;

    /** 默认重试次数 */
    public static final int DEFAULT_RETRY_COUNT = 3;

    private WebhookConfig() {
        this.events = new ArrayList<>();
        this.enabled = true;
        this.retryCount = DEFAULT_RETRY_COUNT;
        this.timeout = DEFAULT_TIMEOUT;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getUrl() { return url; }
    public String getSecret() { return secret; }
    public List<String> getEvents() { return events; }
    public boolean isEnabled() { return enabled; }
    public int getRetryCount() { return retryCount; }
    public int getTimeout() { return timeout; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public String getDescription() { return description; }
    public String getName() { return name; }

    // ==================== Setters ====================

    public void setId(String id) { this.id = id; }
    public void setUrl(String url) { this.url = url; this.updatedAt = System.currentTimeMillis(); }
    public void setSecret(String secret) { this.secret = secret; this.updatedAt = System.currentTimeMillis(); }
    public void setEvents(List<String> events) { this.events = events; this.updatedAt = System.currentTimeMillis(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = System.currentTimeMillis(); }
    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, Math.min(retryCount, MAX_RETRY_COUNT));
        this.updatedAt = System.currentTimeMillis();
    }
    public void setTimeout(int timeout) {
        this.timeout = Math.max(1000, Math.min(timeout, 30000));
        this.updatedAt = System.currentTimeMillis();
    }
    public void setDescription(String description) { this.description = description; this.updatedAt = System.currentTimeMillis(); }
    public void setName(String name) { this.name = name; this.updatedAt = System.currentTimeMillis(); }

    /**
     * 检查是否监听指定事件类型
     *
     * @param eventType 事件类型
     * @return true如果监听该事件
     */
    public boolean listensTo(String eventType) {
        if (events == null || events.isEmpty()) {
            return true; // 空列表表示监听所有事件
        }
        return events.contains("*") || events.contains(eventType);
    }

    /**
     * 转换为Map（用于JSON序列化）
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("url", url);
        map.put("secret", secret != null ? "***" : null); // 脱敏
        map.put("events", events);
        map.put("enabled", enabled);
        map.put("retryCount", retryCount);
        map.put("timeout", timeout);
        map.put("description", description);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        return map;
    }

    @Override
    public String toString() {
        return "WebhookConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", enabled=" + enabled +
                ", events=" + events +
                ", retryCount=" + retryCount +
                ", timeout=" + timeout +
                '}';
    }

    // ==================== Builder ====================

    /**
     * 创建Builder实例
     *
     * @return 新的Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从已有配置创建Builder
     *
     * @param existing 已有配置
     * @return 包含已有配置值的Builder
     */
    public static Builder builder(WebhookConfig existing) {
        Builder builder = new Builder();
        if (existing != null) {
            builder.id = existing.id;
            builder.name = existing.name;
            builder.url = existing.url;
            builder.secret = existing.secret;
            builder.events = existing.events != null ? new ArrayList<>(existing.events) : new ArrayList<>();
            builder.enabled = existing.enabled;
            builder.retryCount = existing.retryCount;
            builder.timeout = existing.timeout;
            builder.description = existing.description;
        }
        return builder;
    }

    public static class Builder {
        private String id;
        private String name;
        private String url;
        private String secret;
        private List<String> events = new ArrayList<>();
        private boolean enabled = true;
        private int retryCount = DEFAULT_RETRY_COUNT;
        private int timeout = DEFAULT_TIMEOUT;
        private String description;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder secret(String secret) { this.secret = secret; return this; }
        public Builder events(List<String> events) { this.events = events != null ? new ArrayList<>(events) : new ArrayList<>(); return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = Math.max(0, Math.min(retryCount, MAX_RETRY_COUNT)); return this; }
        public Builder timeout(int timeout) { this.timeout = Math.max(1000, Math.min(timeout, 30000)); return this; }
        public Builder description(String description) { this.description = description; return this; }

        /**
         * 添加事件类型
         */
        public Builder addEvent(String event) {
            this.events.add(event);
            return this;
        }

        /**
         * 构建WebhookConfig实例
         *
         * @return WebhookConfig实例
         * @throws IllegalStateException 如果必需字段缺失
         */
        public WebhookConfig build() {
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("Webhook URL is required");
            }
            WebhookConfig config = new WebhookConfig();
            config.id = this.id;
            config.name = this.name;
            config.url = this.url;
            config.secret = this.secret;
            config.events = new ArrayList<>(this.events);
            config.enabled = this.enabled;
            config.retryCount = this.retryCount;
            config.timeout = this.timeout;
            config.description = this.description;
            return config;
        }
    }
}
