package com.memoryplatform.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 向量记录 - 统一的向量存储数据模型
 */
public class VectorRecord {
    private final String id;
    private final String collection;
    private final float[] vector;
    private final String text;
    private final String userId;
    private final String agentId;
    private final List<Entity> entities;
    private final double importance;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private final Instant updatedAt;

    private VectorRecord(Builder builder) {
        this.id = builder.id;
        this.collection = builder.collection;
        this.vector = builder.vector;
        this.text = builder.text;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.entities = builder.entities;
        this.importance = builder.importance;
        this.metadata = builder.metadata;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    // Getters
    public String getId() { return id; }
    public String getCollection() { return collection; }
    public float[] getVector() { return vector; }
    public String getText() { return text; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public List<Entity> getEntities() { return entities; }
    public double getImportance() { return importance; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String collection;
        private float[] vector;
        private String text;
        private String userId;
        private String agentId;
        private List<Entity> entities;
        private double importance = 0.5;
        private Map<String, Object> metadata;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        public Builder id(String id) { this.id = id; return this; }
        public Builder collection(String collection) { this.collection = collection; return this; }
        public Builder vector(float[] vector) { this.vector = vector; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder entities(List<Entity> entities) { this.entities = entities; return this; }
        public Builder importance(double importance) { this.importance = importance; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public VectorRecord build() {
            if (id == null || text == null || userId == null) {
                throw new IllegalStateException("id, text, userId are required");
            }
            return new VectorRecord(this);
        }
    }
}
