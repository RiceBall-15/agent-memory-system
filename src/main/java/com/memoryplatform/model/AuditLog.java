package com.memoryplatform.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志 - 记录所有CRUD操作
 */
public class AuditLog {
    private final String logId;
    private final String action;
    private final String userId;
    private final String agentId;
    private final String memoryId;
    private final Map<String, Object> details;
    private final Instant timestamp;
    private final String sourceIp;

    private AuditLog(Builder builder) {
        this.logId = builder.logId;
        this.action = builder.action;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.memoryId = builder.memoryId;
        this.details = builder.details != null ? new HashMap<>(builder.details) : new HashMap<>();
        this.timestamp = builder.timestamp;
        this.sourceIp = builder.sourceIp;
    }

    public String getLogId() { return logId; }
    public String getAction() { return action; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public String getMemoryId() { return memoryId; }
    public Map<String, Object> getDetails() { return details; }
    public Instant getTimestamp() { return timestamp; }
    public String getSourceIp() { return sourceIp; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String logId;
        private String action;
        private String userId;
        private String agentId;
        private String memoryId;
        private Map<String, Object> details;
        private Instant timestamp = Instant.now();
        private String sourceIp;

        public Builder logId(String logId) { this.logId = logId; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder memoryId(String memoryId) { this.memoryId = memoryId; return this; }
        public Builder details(Map<String, Object> details) { this.details = details; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder sourceIp(String sourceIp) { this.sourceIp = sourceIp; return this; }

        public AuditLog build() {
            if (logId == null || action == null) {
                throw new IllegalStateException("logId and action are required");
            }
            return new AuditLog(this);
        }
    }
}
