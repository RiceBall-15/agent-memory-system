package com.memoryplatform.model;

/**
 * 实体类型枚举
 */
public enum EntityType {
    PERSON("人名", 0.9),
    ORG("组织", 0.85),
    PRODUCT("产品", 0.8),
    LOCATION("地点", 0.85),
    DATE("日期", 0.9),
    PREFERENCE("偏好", 0.75),
    SKILL("技能", 0.8),
    PROJECT("项目", 0.8),
    TOPIC("话题", 0.7),
    EMOTION("情感", 0.6);

    private final String displayName;
    private final double defaultConfidence;

    EntityType(String displayName, double defaultConfidence) {
        this.displayName = displayName;
        this.defaultConfidence = defaultConfidence;
    }

    public String getDisplayName() { return displayName; }
    public double getDefaultConfidence() { return defaultConfidence; }
}
