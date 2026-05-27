package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 写入结果 - 记录一次记忆写入操作的完整结果
 * <p>
 * 包含写入状态、各存储层ID、延迟统计和错误信息。
 * 通过 {@link #success} 判断整体是否成功，各ID字段可追踪写入到各存储层的具体位置。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * WriteResult result = WriteResult.builder()
 *     .success(true)
 *     .memoryId("mem-123")
 *     .vectorId("vec-456")
 *     .graphId("gph-789")
 *     .metadataId("mtd-012")
 *     .latencyMs(45)
 *     .build();
 *
 * WriteResult failed = WriteResult.builder()
 *     .success(false)
 *     .error("Connection refused to Milvus")
 *     .latencyMs(5000)
 *     .build();
 * }</pre>
 *
 * @see ConcurrentWriteService
 * @see CircuitBreaker
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriteResult {

    /** 是否写入成功 */
    @JsonProperty("success")
    private boolean success;

    /** 记忆ID */
    @JsonProperty("memory_id")
    private String memoryId;

    /** 向量库写入ID (向量存储层返回的记录ID) */
    @JsonProperty("vector_id")
    private String vectorId;

    /** 图库写入ID (图存储层返回的节点ID) */
    @JsonProperty("graph_id")
    private String graphId;

    /** 元数据库写入ID (元数据存储层返回的记录ID) */
    @JsonProperty("metadata_id")
    private String metadataId;

    /** 错误信息 (失败时填充) */
    @JsonProperty("error")
    private String error;

    /** 写入延迟 (毫秒) */
    @JsonProperty("latency_ms")
    private long latencyMs;

    /** 写入时间戳 */
    @JsonProperty("timestamp")
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 判断是否部分成功 (某些存储层成功, 某些失败)
     * @return 至少有一个ID非空 且 整体标记为失败
     */
    public boolean isPartialSuccess() {
        return !success && (vectorId != null || graphId != null || metadataId != null);
    }

    /**
     * 创建成功结果的Builder
     * @return 成功结果Builder
     */
    public static Builder successBuilder() {
        return builder().success(true);
    }

    /**
     * 创建失败结果的Builder
     * @return 失败结果Builder
     */
    public static Builder failureBuilder() {
        return builder().success(false);
    }
}
