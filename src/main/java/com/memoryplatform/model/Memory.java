package com.memoryplatform.model;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

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
    private final Instant expireAt;
    private final String deduplicatedFrom;
    // 新增：衰减与共享字段
    private final double decayWeight;
    private final Instant lastAccessTime;
    private final String sharedFrom;
    private final String sharedMode;
    private final String status;
    private final List<String> compressedFrom;
    private final Instant archivedAt;

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
        this.expireAt = builder.expireAt;
        this.deduplicatedFrom = builder.deduplicatedFrom;
        this.decayWeight = builder.decayWeight;
        this.lastAccessTime = builder.lastAccessTime;
        this.sharedFrom = builder.sharedFrom;
        this.sharedMode = builder.sharedMode;
        this.status = builder.status;
        this.compressedFrom = builder.compressedFrom;
        this.archivedAt = builder.archivedAt;
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
    public Instant getExpireAt() { return expireAt; }
    public String getDeduplicatedFrom() { return deduplicatedFrom; }
    public double getDecayWeight() { return decayWeight; }
    public Instant getLastAccessTime() { return lastAccessTime; }
    public String getSharedFrom() { return sharedFrom; }
    public String getSharedMode() { return sharedMode; }
    public String getStatus() { return status; }
    public List<String> getCompressedFrom() { return compressedFrom; }
    public Instant getArchivedAt() { return archivedAt; }

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
        private Instant expireAt;
        private String deduplicatedFrom;
        private double decayWeight = 1.0;
        private Instant lastAccessTime;
        private String sharedFrom;
        private String sharedMode;
        private String status = "ACTIVE";
        private List<String> compressedFrom;
        private Instant archivedAt;

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
        public Builder expireAt(Instant expireAt) { this.expireAt = expireAt; return this; }
        public Builder deduplicatedFrom(String deduplicatedFrom) { this.deduplicatedFrom = deduplicatedFrom; return this; }
        public Builder decayWeight(double decayWeight) { this.decayWeight = decayWeight; return this; }
        public Builder lastAccessTime(Instant lastAccessTime) { this.lastAccessTime = lastAccessTime; return this; }
        public Builder sharedFrom(String sharedFrom) { this.sharedFrom = sharedFrom; return this; }
        public Builder sharedMode(String sharedMode) { this.sharedMode = sharedMode; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder compressedFrom(List<String> compressedFrom) { this.compressedFrom = compressedFrom; return this; }
        public Builder archivedAt(Instant archivedAt) { this.archivedAt = archivedAt; return this; }

        public Memory build() {
            if (id == null || text == null || userId == null) {
                throw new IllegalStateException("id, text, userId are required");
            }
            return new Memory(this);
        }
    }
}
