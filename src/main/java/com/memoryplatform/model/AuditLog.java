package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志 - 记录所有CRUD操作
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @JsonProperty("log_id")
    private String logId;

    @JsonProperty("action")
    private String action;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("memory_id")
    private String memoryId;

    @JsonProperty("details")
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    @JsonProperty("timestamp")
    @Builder.Default
    private Instant timestamp = Instant.now();

    @JsonProperty("source_ip")
    private String sourceIp;
}
