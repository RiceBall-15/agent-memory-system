package com.memoryplatform.model;

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
 * WriteResult result = WriteResult.successBuilder()
 *     .memoryId("mem-123")
 *     .vectorId("vec-456")
 *     .graphId("gph-789")
 *     .metadataId("mtd-012")
 *     .latencyMs(45)
 *     .build();
 *
 * WriteResult failed = WriteResult.failureBuilder()
 *     .error("Connection refused to Milvus")
 *     .latencyMs(5000)
 *     .build();
 * }</pre>
 *
 * @see ConcurrentWriteService
 * @see CircuitBreaker
 */
public class WriteResult {

    /** 是否写入成功 */
    private final boolean success;

    /** 记忆ID */
    private final String memoryId;

    /** 向量库写入ID (向量存储层返回的记录ID) */
    private final String vectorId;

    /** 图库写入ID (图存储层返回的节点ID) */
    private final String graphId;

    /** 元数据库写入ID (元数据存储层返回的记录ID) */
    private final String metadataId;

    /** 错误信息 (失败时填充) */
    private final String error;

    /** 写入延迟 (毫秒) */
    private final long latencyMs;

    /** 写入时间戳 */
    private final Instant timestamp;

    private WriteResult(Builder builder) {
        this.success = builder.success;
        this.memoryId = builder.memoryId;
        this.vectorId = builder.vectorId;
        this.graphId = builder.graphId;
        this.metadataId = builder.metadataId;
        this.error = builder.error;
        this.latencyMs = builder.latencyMs;
        this.timestamp = builder.timestamp;
    }

    // ============ Getters ============

    public boolean isSuccess() { return success; }
    public String getMemoryId() { return memoryId; }
    public String getVectorId() { return vectorId; }
    public String getGraphId() { return graphId; }
    public String getMetadataId() { return metadataId; }
    public String getError() { return error; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getTimestamp() { return timestamp; }

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
        return new Builder(true);
    }

    /**
     * 创建失败结果的Builder
     * @return 失败结果Builder
     */
    public static Builder failureBuilder() {
        return new Builder(false);
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("WriteResult{success, mem=%s, vec=%s, gph=%s, mtd=%s, %dms}",
                    memoryId, vectorId, graphId, metadataId, latencyMs);
        } else {
            return String.format("WriteResult{FAILED, mem=%s, error='%s', %dms}",
                    memoryId, error, latencyMs);
        }
    }

    /**
     * WriteResult构建器
     * <p>
     * 通过 {@link #successBuilder()} 或 {@link #failureBuilder()} 获取实例。
     * </p>
     */
    public static class Builder {
        private boolean success;
        private String memoryId;
        private String vectorId;
        private String graphId;
        private String metadataId;
        private String error;
        private long latencyMs;
        private Instant timestamp = Instant.now();

        Builder(boolean success) {
            this.success = success;
        }

        public Builder memoryId(String memoryId) { this.memoryId = memoryId; return this; }
        public Builder vectorId(String vectorId) { this.vectorId = vectorId; return this; }
        public Builder graphId(String graphId) { this.graphId = graphId; return this; }
        public Builder metadataId(String metadataId) { this.metadataId = metadataId; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        public WriteResult build() {
            return new WriteResult(this);
        }
    }
}
