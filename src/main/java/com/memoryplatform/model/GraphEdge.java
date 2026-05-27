package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 图边 - 图数据库关系单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    @JsonProperty("id")
    private String id;

    @JsonProperty("source_id")
    private String sourceId;

    @JsonProperty("target_id")
    private String targetId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("weight")
    @Builder.Default
    private double weight = 1.0;

    @JsonProperty("properties")
    private Map<String, Object> properties;
}
