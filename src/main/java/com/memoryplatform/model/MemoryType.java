package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 记忆类型枚举 - 定义记忆的分类体系
 *
 * <p>基于认知科学的记忆分类理论，结合 Mem0、Letta、Cognee 等主流框架的设计：</p>
 * <ul>
 *   <li><b>SEMANTIC</b> (语义记忆): 事实、知识、概念 — 关于世界的通用知识</li>
 *   <li><b>EPISODIC</b> (情景记忆): 对话经历、事件 — 与特定时间和地点相关的个人经历</li>
 *   <li><b>PROCEDURAL</b> (程序记忆): 操作步骤、技能 — 如何执行某项任务的知识</li>
 *   <li><b>WORKING</b> (工作记忆): 当前上下文 — 短期内活跃的工作信息</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @since 2.1
 */
@Schema(
    description = "记忆类型枚举",
    enumAsRef = true
)
public enum MemoryType {

    @Schema(description = "语义记忆：事实、知识、概念 — 关于世界的通用知识")
    SEMANTIC("语义记忆：事实、知识、概念"),

    @Schema(description = "情景记忆：对话经历、事件 — 与特定时间和地点相关的个人经历")
    EPISODIC("情景记忆：对话经历、事件"),

    @Schema(description = "程序记忆：操作步骤、技能 — 如何执行某项任务的知识")
    PROCEDURAL("程序记忆：操作步骤、技能"),

    @Schema(description = "工作记忆：当前上下文 — 短期内活跃的工作信息")
    WORKING("工作记忆：当前上下文");

    private final String description;

    MemoryType(String description) {
        this.description = description;
    }

    @JsonValue
    public String getValue() {
        return name();
    }

    @JsonCreator
    public static MemoryType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /**
     * 默认记忆类型为 SEMANTIC，确保向后兼容
     */
    public static final MemoryType DEFAULT = SEMANTIC;

    public String getDescription() {
        return description;
    }
}
