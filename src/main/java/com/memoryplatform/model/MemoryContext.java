package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆上下文 - 检索到的相关记忆及其评分
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryContext {

    @JsonProperty("memories")
    private List<Memory> memories;

    @JsonProperty("scores")
    @Builder.Default
    private Map<String, Double> scores = new HashMap<>();

    @JsonProperty("window_size")
    @Builder.Default
    private int windowSize = 10;

    @JsonProperty("total_relevance")
    @Builder.Default
    private double totalRelevance = 0.0;
}
