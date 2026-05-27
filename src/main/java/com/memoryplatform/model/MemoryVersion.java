package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 记忆版本 - 记录记忆内容的历史变更
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryVersion {

    @JsonProperty("version_id")
    private String versionId;

    @JsonProperty("memory_id")
    private String memoryId;

    @JsonProperty("version")
    private int version;

    @JsonProperty("content")
    private String content;

    @JsonProperty("tags")
    @Builder.Default
    private List<String> tags = List.of();

    @JsonProperty("importance")
    @Builder.Default
    private double importance = 0.5;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @JsonProperty("changed_by")
    private String changedBy;

    @JsonProperty("change_type")
    private ChangeType changeType;

    @JsonProperty("snapshot")
    @Builder.Default
    private Map<String, Object> snapshot = Map.of();

    public enum ChangeType {
        CREATE, UPDATE, DELETE, MERGE
    }
}
