package com.memoryplatform.model;

import java.time.Instant;
import java.util.Map;

/**
 * 图节点 - 图数据库存储单元
 */
public class GraphNode {
    private final String id;
    private final String label;
    private final String content;
    private final String type;
    private final String userId;
    private final String agentId;
    private final Map<String, Object> properties;
    private final Instant createdAt;

    private GraphNode(Builder builder) {
        this.id = builder.id;
        this.label = builder.label;
        this.content = builder.content;
        this.type = builder.type;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.properties = builder.properties;
        this.createdAt = builder.createdAt;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getContent() { return content; }
    public String getType() { return type; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public Map<String, Object> getProperties() { return properties; }
    public Instant getCreatedAt() { return createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String label;
        private String content;
        private String type;
        private String userId;
        private String agentId;
        private Map<String, Object> properties;
        private Instant createdAt = Instant.now();

        public Builder id(String id) { this.id = id; return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder properties(Map<String, Object> properties) { this.properties = properties; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public GraphNode build() {
            if (id == null || label == null) {
                throw new IllegalStateException("id and label are required");
            }
            return new GraphNode(this);
        }
    }
}
