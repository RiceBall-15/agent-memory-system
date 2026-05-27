package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 搜索查询 - 检索请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchQuery {

    @JsonProperty("text")
    private String text;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("top_k")
    @Builder.Default
    private int topK = 10;

    @JsonProperty("threshold")
    @Builder.Default
    private double threshold = 0.5;

    @JsonProperty("filters")
    private Map<String, Object> filters;
}
