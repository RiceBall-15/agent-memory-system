package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

/**
 * 实体 - 记忆中的结构化实体
 * <p>
 * 使用 @Getter + @Builder，自定义 equals/hashCode/toString 基于 normalizedId。
 * normalizedId 通过静态工厂方法 of() 自动计算。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "normalizedId")
@ToString(of = {"name", "type", "confidence"})
public class Entity {

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private EntityType type;

    @JsonProperty("confidence")
    private double confidence;

    @JsonProperty("normalized_id")
    private String normalizedId;

    /**
     * 兼容旧代码的3参数构造器，自动计算 normalizedId。
     */
    public Entity(String name, EntityType type, double confidence) {
        this.name = name;
        this.type = type;
        this.confidence = confidence;
        this.normalizedId = type.name().toLowerCase() + ":" + name.toLowerCase().trim();
    }

    /**
     * 静态工厂方法，自动计算 normalizedId。
     */
    public static Entity of(String name, EntityType type, double confidence) {
        return new Entity(name, type, confidence);
    }
}
