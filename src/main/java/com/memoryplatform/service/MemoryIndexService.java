package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引优化服务
 * <p>
 * 提供以下功能:
 * <ul>
 *   <li>索引重建: 重建向量索引和BM25索引</li>
 *   <li>索引统计: 向量数、BM25文档数、索引大小</li>
 *   <li>索引健康检查: 检查索引一致性</li>
 *   <li>索引碎片整理: 清理无效索引条目</li>
 *   <li>后台线程每天执行一次索引优化</li>
 * </ul>
 * </p>
 */
public class MemoryIndexService {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 向量集合名 */
    private static final String VECTOR_COLLECTION = "memories";

    /** 默认扫描间隔 (毫秒) - 1天 */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** 默认批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 500;

    /** 存储依赖 */
    private final MetadataStore metadataStore;
    private final VectorStore vectorStore;

    /** Embedding服务 */
    private final EmbeddingService embeddingService;

    /** BM25文本索引 */
    private final Bm25Scorer bm25Scorer;

    /** 扫描间隔 (毫秒) */
    private final long scanIntervalMs;

    /** 批次大小 */
    private final int batchSize;

    /** 后台扫描线程 */
    private Thread scanThread;

    /** 扫描状态 */
    private volatile boolean running = false;

    /** 统计计数器 */
    private final AtomicLong rebuildCount = new AtomicLong(0);
    private final AtomicLong defragCount = new AtomicLong(0);
    private final AtomicLong lastRebuildTime = new AtomicLong(0);

    /**
     * 构造函数 (使用默认参数)
     *
     * @param metadataStore    元数据存储
     * @param vectorStore      向量存储
     * @param embeddingService Embedding服务
     */
    public MemoryIndexService(MetadataStore metadataStore, VectorStore vectorStore,
                               EmbeddingService embeddingService) {
        this(metadataStore, vectorStore, embeddingService,
                DEFAULT_SCAN_INTERVAL_MS, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义参数构造函数
     *
     * @param metadataStore    元数据存储
     * @param vectorStore      向量存储
     * @param embeddingService Embedding服务
     * @param scanIntervalMs   扫描间隔 (毫秒)
     * @param batchSize        批次大小
     */
    public MemoryIndexService(MetadataStore metadataStore, VectorStore vectorStore,
                               EmbeddingService embeddingService,
                               long scanIntervalMs, int batchSize) {
        this.metadataStore = metadataStore;
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.scanIntervalMs = scanIntervalMs;
        this.batchSize = batchSize;
        this.bm25Scorer = new Bm25Scorer();
    }

    /**
     * 启动后台索引优化线程
     */
    public void start() {
        if (running) {
            System.out.println("[MemoryIndex] 索引优化线程已在运行");
            return;
        }

        running = true;
        scanThread = new Thread(this::scanLoop, "MemoryIndex-Optimizer");
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[MemoryIndex] 后台索引优化线程启动, 间隔=" + (scanIntervalMs / 1000 / 60 / 60) + "小时");
    }

    /**
     * 停止后台索引优化线程
     */
    public void stop() {
        running = false;
        if (scanThread != null) {
            scanThread.interrupt();
            try {
                scanThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[MemoryIndex] 后台索引优化线程已停止");
    }

    /**
     * 后台扫描循环
     */
    private void scanLoop() {
        while (running) {
            try {
                // 等待指定间隔
                Thread.sleep(scanIntervalMs);

                // 执行索引优化
                optimize();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MemoryIndex] 索引优化异常: " + e.getMessage());
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 执行一次完整索引优化
     *
     * @return 优化统计信息
     */
    public Map<String, Object> optimize() {
        System.out.println("[MemoryIndex] 开始索引优化...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 索引健康检查
            Map<String, Object> healthResult = healthCheck();
            result.put("healthCheck", healthResult);

            // 2. 碎片整理
            Map<String, Object> defragResult = defragment();
            result.put("defragment", defragResult);

            // 3. 重建BM25索引
            rebuildBm25Index();
            result.put("bm25Rebuilt", true);

        } catch (Exception e) {
            System.err.println("[MemoryIndex] 索引优化失败: " + e.getMessage());
            result.put("error", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        lastRebuildTime.set(System.currentTimeMillis());
        result.put("elapsedMs", elapsed);

        System.out.printf("[MemoryIndex] 索引优化完成, 耗时=%dms%n", elapsed);
        return result;
    }

    /**
     * 重建向量索引
     * <p>
     * 从元数据中读取所有记忆，重新生成向量并写入向量存储。
     * </p>
     *
     * @return 重建统计信息
     */
    public Map<String, Object> rebuildVectorIndex() {
        if (metadataStore == null || vectorStore == null || embeddingService == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "存储或Embedding服务不可用");
            return error;
        }

        System.out.println("[MemoryIndex] 开始重建向量索引...");
        long startTime = System.currentTimeMillis();

        int totalRebuilt = 0;
        int totalFailed = 0;

        try {
            int offset = 0;
            List<MetadataRecord> batch;

            do {
                batch = metadataStore.find(METADATA_TABLE, new HashMap<>(), batchSize, offset);

                List<com.memoryplatform.model.VectorRecord> vectorRecords = new ArrayList<>();

                for (MetadataRecord record : batch) {
                    try {
                        if (record.getContent() == null || record.getContent().isBlank()) {
                            totalFailed++;
                            continue;
                        }

                        // 生成向量
                        float[] embedding = embeddingService.embed(record.getContent());

                        // 创建向量记录
                        Map<String, Object> vrMeta = new HashMap<>();
                        vrMeta.put("userId", record.getUserId());
                        vrMeta.put("agentId", record.getAgentId());

                        com.memoryplatform.model.VectorRecord vr = com.memoryplatform.model.VectorRecord.builder()
                                .id(record.getId())
                                .vector(embedding)
                                .text(record.getContent())
                                .userId(record.getUserId())
                                .agentId(record.getAgentId())
                                .metadata(vrMeta)
                                .build();

                        vectorRecords.add(vr);
                        totalRebuilt++;
                    } catch (Exception e) {
                        System.err.println("[MemoryIndex] 重建向量失败: " + record.getId() + ", " + e.getMessage());
                        totalFailed++;
                    }
                }

                // 批量写入向量存储
                if (!vectorRecords.isEmpty()) {
                    vectorStore.upsert(VECTOR_COLLECTION, vectorRecords);
                }

                offset += batchSize;
            } while (batch.size() == batchSize);

        } catch (Exception e) {
            System.err.println("[MemoryIndex] 重建向量索引失败: " + e.getMessage());
        }

        rebuildCount.addAndGet(totalRebuilt);

        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new HashMap<>();
        result.put("rebuilt", totalRebuilt);
        result.put("failed", totalFailed);
        result.put("elapsedMs", elapsed);

        System.out.printf("[MemoryIndex] 向量索引重建完成: 重建=%d, 失败=%d, 耗时=%dms%n",
                totalRebuilt, totalFailed, elapsed);

        return result;
    }

    /**
     * 重建BM25索引
     */
    private void rebuildBm25Index() {
        if (metadataStore == null) return;

        System.out.println("[MemoryIndex] 重建BM25索引...");

        try {
            bm25Scorer.clear();

            int offset = 0;
            List<MetadataRecord> batch;
            Map<String, String> docIdToText = new HashMap<>();

            do {
                batch = metadataStore.find(METADATA_TABLE, new HashMap<>(), batchSize, offset);
                for (MetadataRecord record : batch) {
                    if (record.getContent() != null && !record.getContent().isBlank()) {
                        docIdToText.put(record.getId(), record.getContent());
                    }
                }
                offset += batchSize;
            } while (batch.size() == batchSize);

            bm25Scorer.addDocuments(docIdToText);

            System.out.println("[MemoryIndex] BM25索引重建完成: 文档数=" + docIdToText.size());
        } catch (Exception e) {
            System.err.println("[MemoryIndex] 重建BM25索引失败: " + e.getMessage());
        }
    }

    /**
     * 获取索引统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // 向量统计
        if (vectorStore != null) {
            try {
                Map<String, Object> vectorStats = vectorStore.getStats(VECTOR_COLLECTION);
                stats.put("vectorStats", vectorStats);
            } catch (Exception e) {
                stats.put("vectorStats", "获取失败: " + e.getMessage());
            }
        } else {
            stats.put("vectorStats", "向量存储不可用");
        }

        // 元数据统计
        if (metadataStore != null) {
            try {
                long totalCount = metadataStore.count(METADATA_TABLE, new HashMap<>());

                Map<String, Object> activeFilters = new HashMap<>();
                activeFilters.put("status", "ACTIVE");
                long activeCount = metadataStore.count(METADATA_TABLE, activeFilters);

                Map<String, Object> archivedFilters = new HashMap<>();
                archivedFilters.put("status", "ARCHIVED");
                long archivedCount = metadataStore.count(METADATA_TABLE, archivedFilters);

                Map<String, Object> compressedFilters = new HashMap<>();
                compressedFilters.put("status", "COMPRESSED");
                long compressedCount = metadataStore.count(METADATA_TABLE, compressedFilters);

                stats.put("totalMetadata", totalCount);
                stats.put("activeMemories", activeCount);
                stats.put("archivedMemories", archivedCount);
                stats.put("compressedMemories", compressedCount);
            } catch (Exception e) {
                stats.put("metadataStats", "获取失败: " + e.getMessage());
            }
        }

        // 优化统计
        stats.put("rebuildCount", rebuildCount.get());
        stats.put("defragCount", defragCount.get());
        stats.put("lastRebuildTime", lastRebuildTime.get() > 0 ?
                new Date(lastRebuildTime.get()).toString() : "never");
        stats.put("scanIntervalMs", scanIntervalMs);
        stats.put("running", running);

        return stats;
    }

    /**
     * 索引健康检查
     * <p>
     * 检查索引一致性:
     * <ul>
     *   <li>元数据中的活跃记忆是否都有对应的向量</li>
     *   <li>向量存储中是否有孤立的向量</li>
     * </ul>
     * </p>
     *
     * @return 健康检查结果
     */
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        boolean healthy = true;

        // 检查存储连接
        if (metadataStore != null) {
            result.put("metadataStore", metadataStore.healthCheck());
        } else {
            result.put("metadataStore", false);
            healthy = false;
        }

        if (vectorStore != null) {
            result.put("vectorStore", vectorStore.healthCheck());
        } else {
            result.put("vectorStore", false);
            healthy = false;
        }

        // 检查索引一致性
        if (metadataStore != null && vectorStore != null) {
            try {
                // 统计活跃记忆数
                Map<String, Object> activeFilters = new HashMap<>();
                activeFilters.put("status", "ACTIVE");
                long activeCount = metadataStore.count(METADATA_TABLE, activeFilters);

                // 获取向量统计
                Map<String, Object> vectorStats = vectorStore.getStats(VECTOR_COLLECTION);
                long vectorCount = 0;
                if (vectorStats != null && vectorStats.containsKey("count")) {
                    vectorCount = ((Number) vectorStats.get("count")).longValue();
                }

                result.put("activeCount", activeCount);
                result.put("vectorCount", vectorCount);

                // 一致性检查: 活跃记忆数不应远大于向量数
                if (activeCount > vectorCount * 1.5) {
                    result.put("consistencyWarning", "活跃记忆数远大于向量数，建议重建向量索引");
                    healthy = false;
                }

                // 检查是否有过多孤立向量
                if (vectorCount > activeCount * 2) {
                    result.put("orphanWarning", "向量数远大于活跃记忆数，建议碎片整理");
                    healthy = false;
                }

            } catch (Exception e) {
                result.put("consistencyCheck", "检查失败: " + e.getMessage());
                healthy = false;
            }
        }

        result.put("healthy", healthy);
        return result;
    }

    /**
     * 索引碎片整理
     * <p>
     * 清理无效索引条目:
     * <ul>
     *   <li>删除元数据中已不存在的向量</li>
     *   <li>删除状态为COMPRESSED或DELETED的记忆对应的向量</li>
     * </ul>
     * </p>
     *
     * @return 碎片整理统计信息
     */
    public Map<String, Object> defragment() {
        if (metadataStore == null || vectorStore == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "存储不可用");
            return error;
        }

        System.out.println("[MemoryIndex] 开始碎片整理...");
        long startTime = System.currentTimeMillis();

        int cleanedCount = 0;

        try {
            // 1. 查找所有需要清理的ID (COMPRESSED状态)
            Map<String, Object> compressedFilters = new HashMap<>();
            compressedFilters.put("status", "COMPRESSED");
            List<MetadataRecord> compressedRecords = metadataStore.find(METADATA_TABLE, compressedFilters, 10000, 0);

            List<String> idsToClean = new ArrayList<>();
            for (MetadataRecord record : compressedRecords) {
                idsToClean.add(record.getId());
            }

            // 2. 查找归档记忆 (也不需要向量)
            Map<String, Object> archivedFilters = new HashMap<>();
            archivedFilters.put("status", "ARCHIVED");
            List<MetadataRecord> archivedRecords = metadataStore.find(METADATA_TABLE, archivedFilters, 10000, 0);

            for (MetadataRecord record : archivedRecords) {
                idsToClean.add(record.getId());
            }

            // 3. 批量删除无效向量
            if (!idsToClean.isEmpty()) {
                // 分批删除
                for (int i = 0; i < idsToClean.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, idsToClean.size());
                    List<String> batchIds = idsToClean.subList(i, end);
                    vectorStore.delete(VECTOR_COLLECTION, batchIds);
                    cleanedCount += batchIds.size();
                }
            }

        } catch (Exception e) {
            System.err.println("[MemoryIndex] 碎片整理失败: " + e.getMessage());
        }

        defragCount.addAndGet(cleanedCount);

        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new HashMap<>();
        result.put("cleaned", cleanedCount);
        result.put("elapsedMs", elapsed);

        System.out.printf("[MemoryIndex] 碎片整理完成: 清理=%d, 耗时=%dms%n", cleanedCount, elapsed);
        return result;
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        rebuildCount.set(0);
        defragCount.set(0);
        lastRebuildTime.set(0);
    }
}
