package com.memoryplatform.model;

/**
 * 实体 - 记忆中的结构化实体
 */
public class Entity {
    private final String name;
    private final EntityType type;
    private final double confidence;
    private final String normalizedId;

    public Entity(String name, EntityType type, double confidence) {
        this.name = name;
        this.type = type;
        this.confidence = confidence;
        this.normalizedId = type.name().toLowerCase() + ":" + name.toLowerCase().trim();
    }

    public String getName() { return name; }
    public EntityType getType() { return type; }
    public double getConfidence() { return confidence; }
    public String getNormalizedId() { return normalizedId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Entity entity = (Entity) o;
        return normalizedId.equals(entity.normalizedId);
    }

    @Override
    public int hashCode() {
        return normalizedId.hashCode();
    }

    @Override
    public String toString() {
        return "Entity{name='" + name + "', type=" + type + ", confidence=" + confidence + "}";
    }
}
