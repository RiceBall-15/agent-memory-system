package com.memoryplatform.model;

import java.time.Instant;
import java.util.List;

/**
 * 记忆 - 核心业务对象
 */
public class Memory {
    private final String id;
    private final String text;
    private final String userId;
    private final String agentId;
    private final List<Entity> entities;
    private final List<String> linkedMemoryIds;
    private final double importance;
    private final double[] embedding;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Memory(Builder builder) {
        this.id = builder.id;
        this.text = builder.text;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.entities = builder.entities;
        this.linkedMemoryIds = builder.linkedMemoryIds;
        this.importance = builder.importance;
        this.embedding = builder.embedding;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public List<Entity> getEntities() { return entities; }
    public List<String> getLinkedMemoryIds() { return linkedMemoryIds; }
    public double getImportance() { return importance; }
    public double[] getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String text;
        private String userId;
        private String agentId;
        private List<Entity> entities;
        private List<String> linkedMemoryIds;
        private double importance = 0.5;
        private double[] embedding;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();

        public Builder id(String id) { this.id = id; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder entities(List<Entity> entities) { this.entities = entities; return this; }
        public Builder linkedMemoryIds(List<String> ids) { this.linkedMemoryIds = ids; return this; }
        public Builder importance(double importance) { this.importance = importance; return this; }
        public Builder embedding(double[] embedding) { this.embedding = embedding; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Memory build() {
            if (id == null || text == null || userId == null) {
                throw new IllegalStateException("id, text, userId are required");
            }
            return new Memory(this);
        }
    }
}
