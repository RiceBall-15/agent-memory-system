package com.memoryplatform.model;

import java.util.Map;

/**
 * 搜索查询 - 检索请求参数
 */
public class SearchQuery {
    private final String text;
    private final String userId;
    private final String agentId;
    private final int topK;
    private final double threshold;
    private final Map<String, Object> filters;

    private SearchQuery(Builder builder) {
        this.text = builder.text;
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.topK = builder.topK;
        this.threshold = builder.threshold;
        this.filters = builder.filters;
    }

    public String getText() { return text; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public int getTopK() { return topK; }
    public double getThreshold() { return threshold; }
    public Map<String, Object> getFilters() { return filters; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String text;
        private String userId;
        private String agentId;
        private int topK = 10;
        private double threshold = 0.5;
        private Map<String, Object> filters;

        public Builder text(String text) { this.text = text; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder topK(int topK) { this.topK = topK; return this; }
        public Builder threshold(double threshold) { this.threshold = threshold; return this; }
        public Builder filters(Map<String, Object> filters) { this.filters = filters; return this; }

        public SearchQuery build() {
            if (text == null || userId == null) {
                throw new IllegalStateException("text and userId are required");
            }
            return new SearchQuery(this);
        }
    }
}
