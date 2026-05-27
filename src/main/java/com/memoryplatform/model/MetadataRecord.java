package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 元数据记录 - 关系数据库存储单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataRecord {

    @JsonProperty("id")
    private String id;

    @JsonProperty("table")
    private String table;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("importance")
    private double importance;

    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @JsonProperty("updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
