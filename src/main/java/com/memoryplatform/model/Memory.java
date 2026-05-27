package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆 - 核心业务对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Memory {

    @JsonProperty("id")
    private String id;

    @JsonProperty("text")
    private String text;

    @Schema(description = "记忆类型：SEMANTIC(语义)、EPISODIC(情景)、PROCEDURAL(程序)、WORKING(工作)", 
            defaultValue = "SEMANTIC")
    @JsonProperty("memory_type")
    @Builder.Default
    private MemoryType memoryType = MemoryType.DEFAULT;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("entities")
    @Builder.Default
    private List<Entity> entities = new ArrayList<>();

    @JsonProperty("linked_memory_ids")
    @Builder.Default
    private List<String> linkedMemoryIds = new ArrayList<>();

    @JsonProperty("importance")
    @Builder.Default
    private double importance = 0.5;

    @JsonProperty("embedding")
    private double[] embedding;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @JsonProperty("updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @JsonProperty("expire_at")
    private Instant expireAt;

    @JsonProperty("deduplicated_from")
    private String deduplicatedFrom;

    // 衰减与共享字段
    @JsonProperty("decay_weight")
    @Builder.Default
    private double decayWeight = 1.0;

    @JsonProperty("last_access_time")
    private Instant lastAccessTime;

    @JsonProperty("shared_from")
    private String sharedFrom;

    @JsonProperty("shared_mode")
    private String sharedMode;

    @JsonProperty("status")
    @Builder.Default
    private String status = "ACTIVE";

    @JsonProperty("compressed_from")
    @Builder.Default
    private List<String> compressedFrom = new ArrayList<>();

    @JsonProperty("archived_at")
    private Instant archivedAt;
}
