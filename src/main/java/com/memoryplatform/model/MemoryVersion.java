package com.memoryplatform.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆版本 - 记录记忆内容的历史变更
 */
public class MemoryVersion {
    private final String versionId;
    private final String memoryId;
    private final int version;
    private final String content;
    private final List<String> tags;
    private final double importance;
    private final Instant createdAt;
    private final String changedBy;
    private final ChangeType changeType;
    private final Map<String, Object> snapshot;

    public enum ChangeType {
        CREATE, UPDATE, DELETE, MERGE
    }

    private MemoryVersion(Builder builder) {
        this.versionId = builder.versionId;
        this.memoryId = builder.memoryId;
        this.version = builder.version;
        this.content = builder.content;
        this.tags = builder.tags != null ? List.copyOf(builder.tags) : List.of();
        this.importance = builder.importance;
        this.createdAt = builder.createdAt;
        this.changedBy = builder.changedBy;
        this.changeType = builder.changeType;
        this.snapshot = builder.snapshot != null ? Map.copyOf(builder.snapshot) : Map.of();
    }

    public String getVersionId() { return versionId; }
    public String getMemoryId() { return memoryId; }
    public int getVersion() { return version; }
    public String getContent() { return content; }
    public List<String> getTags() { return tags; }
    public double getImportance() { return importance; }
    public Instant getCreatedAt() { return createdAt; }
    public String getChangedBy() { return changedBy; }
    public ChangeType getChangeType() { return changeType; }
    public Map<String, Object> getSnapshot() { return snapshot; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String versionId;
        private String memoryId;
        private int version;
        private String content;
        private List<String> tags;
        private double importance = 0.5;
        private Instant createdAt = Instant.now();
        private String changedBy;
        private ChangeType changeType;
        private Map<String, Object> snapshot;

        public Builder versionId(String versionId) { this.versionId = versionId; return this; }
        public Builder memoryId(String memoryId) { this.memoryId = memoryId; return this; }
        public Builder version(int version) { this.version = version; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder importance(double importance) { this.importance = importance; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder changedBy(String changedBy) { this.changedBy = changedBy; return this; }
        public Builder changeType(ChangeType changeType) { this.changeType = changeType; return this; }
        public Builder snapshot(Map<String, Object> snapshot) { this.snapshot = snapshot; return this; }

        public MemoryVersion build() {
            if (versionId == null || memoryId == null) {
                throw new IllegalStateException("versionId and memoryId are required");
            }
            return new MemoryVersion(this);
        }
    }
}
