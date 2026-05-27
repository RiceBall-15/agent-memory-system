package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 向量记录 - 统一的向量存储数据模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorRecord {

    @JsonProperty("id")
    private String id;

    @JsonProperty("collection")
    private String collection;

    @JsonProperty("vector")
    private float[] vector;

    @JsonProperty("text")
    private String text;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("entities")
    private List<Entity> entities;

    @JsonProperty("importance")
    @Builder.Default
    private double importance = 0.5;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @JsonProperty("updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
