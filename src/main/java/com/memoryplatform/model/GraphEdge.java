package com.memoryplatform.model;

import java.util.Map;

/**
 * 图边 - 图数据库关系单元
 */
public class GraphEdge {
    private final String id;
    private final String sourceId;
    private final String targetId;
    private final String type;
    private final double weight;
    private final Map<String, Object> properties;

    private GraphEdge(Builder builder) {
        this.id = builder.id;
        this.sourceId = builder.sourceId;
        this.targetId = builder.targetId;
        this.type = builder.type;
        this.weight = builder.weight;
        this.properties = builder.properties;
    }

    public String getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public String getType() { return type; }
    public double getWeight() { return weight; }
    public Map<String, Object> getProperties() { return properties; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private String sourceId;
        private String targetId;
        private String type;
        private double weight = 1.0;
        private Map<String, Object> properties;

        public Builder id(String id) { this.id = id; return this; }
        public Builder sourceId(String sourceId) { this.sourceId = sourceId; return this; }
        public Builder targetId(String targetId) { this.targetId = targetId; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder weight(double weight) { this.weight = weight; return this; }
        public Builder properties(Map<String, Object> properties) { this.properties = properties; return this; }

        public GraphEdge build() {
            if (id == null || sourceId == null || targetId == null || type == null) {
                throw new IllegalStateException("id, sourceId, targetId, type are required");
            }
            return new GraphEdge(this);
        }
    }
}
