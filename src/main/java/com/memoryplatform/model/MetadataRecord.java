package com.memoryplatform.model;

import java.time.Instant;
import java.util.Map;

/**
 * 元数据记录 - 关系数据库存储单元
 */
public class MetadataRecord {
    private String id;
    private String table;
    private String userId;
    private String agentId;
    private String content;
    private double importance;
    private Map<String, Object> data;
    private Instant createdAt;
    private Instant updatedAt;

    public MetadataRecord() {}

    public MetadataRecord(String id, String table, String userId, String agentId,
                         String content, double importance, Map<String, Object> data) {
        this.id = id;
        this.table = table;
        this.userId = userId;
        this.agentId = agentId;
        this.content = content;
        this.importance = importance;
        this.data = data;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
