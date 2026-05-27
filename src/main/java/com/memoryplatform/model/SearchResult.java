package com.memoryplatform.model;

import java.util.Map;

/**
 * 搜索结果 - 单条检索结果
 */
public class SearchResult {
    private final String id;
    private final String text;
    private final double score;
    private final double semanticScore;
    private final double bm25Score;
    private final double entityBoost;
    private final Map<String, Object> metadata;

    public SearchResult(String id, String text, double score, 
                       double semanticScore, double bm25Score, double entityBoost,
                       Map<String, Object> metadata) {
        this.id = id;
        this.text = text;
        this.score = score;
        this.semanticScore = semanticScore;
        this.bm25Score = bm25Score;
        this.entityBoost = entityBoost;
        this.metadata = metadata;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public double getScore() { return score; }
    public double getSemanticScore() { return semanticScore; }
    public double getBm25Score() { return bm25Score; }
    public double getEntityBoost() { return entityBoost; }
    public Map<String, Object> getMetadata() { return metadata; }
}
