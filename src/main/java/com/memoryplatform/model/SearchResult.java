package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 搜索结果 - 单条检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    @JsonProperty("id")
    private String id;

    @JsonProperty("text")
    private String text;

    @JsonProperty("score")
    private double score;

    @JsonProperty("semantic_score")
    private double semanticScore;

    @JsonProperty("bm25_score")
    private double bm25Score;

    @JsonProperty("entity_boost")
    private double entityBoost;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;
}
