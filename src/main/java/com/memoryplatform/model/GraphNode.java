package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 图节点 - 图数据库存储单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    @JsonProperty("id")
    private String id;

    @JsonProperty("label")
    private String label;

    @JsonProperty("content")
    private String content;

    @JsonProperty("type")
    private String type;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("properties")
    private Map<String, Object> properties;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
