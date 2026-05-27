package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆压缩服务
 * <p>
 * 提供以下功能:
 * <ul>
 *   <li>相似记忆合并: 向量相似度>0.95的记忆合并为一条</li>
 *   <li>历史记忆归档: 超过90天的记忆标记为'archived'</li>
 *   <li>压缩统计: 扫描数、合并数、归档数</li>
 *   <li>后台线程每周执行一次压缩</li>
 * </ul>
 * </p>
 */
public class MemoryCompressionService {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 向量集合名 */
    private static final String VECTOR_COLLECTION = "memories";

    /** 默认压缩阈值 (向量相似度) */
    private static final double DEFAULT_COMPRESS_THRESHOLD = 0.95;

    /** 默认归档天数 */
    private static final int DEFAULT_ARCHIVE_DAYS = 90;

    /** 默认扫描间隔 (毫秒) - 7天 */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    /** 默认批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /** 存储依赖 */
    private final MetadataStore metadataStore;
    private final VectorStore vectorStore;

    /** BM25文本相似度评分器 */
    private final Bm25Scorer bm25Scorer;

    /** 压缩阈值 */
    private final double compressThreshold;

    /** 归档天数 */
    private final int archiveDays;

    /** 扫描间隔 (毫秒) */
    private final long scanIntervalMs;

    /** 批次大小 */
    private final int batchSize;

    /** 后台扫描线程 */
    private Thread scanThread;

    /** 扫描状态 */
    private volatile boolean running = false;

    /** 统计计数器 */
    private final AtomicLong scannedCount = new AtomicLong(0);
    private final AtomicLong mergedCount = new AtomicLong(0);
    private final AtomicLong archivedCount = new AtomicLong(0);

    /**
     * 构造函数 (使用默认参数)
     *
     * @param metadataStore 元数据存储
     * @param vectorStore   向量存储
     */
    public MemoryCompressionService(MetadataStore metadataStore, VectorStore vectorStore) {
        this(metadataStore, vectorStore, DEFAULT_COMPRESS_THRESHOLD,
                DEFAULT_ARCHIVE_DAYS, DEFAULT_SCAN_INTERVAL_MS, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义参数构造函数
     *
     * @param metadataStore     元数据存储
     * @param vectorStore       向量存储
     * @param compressThreshold 压缩阈值 (0.0 ~ 1.0)
     * @param archiveDays       归档天数
     * @param scanIntervalMs    扫描间隔 (毫秒)
     * @param batchSize         批次大小
     */
    public MemoryCompressionService(MetadataStore metadataStore, VectorStore vectorStore,
                                     double compressThreshold, int archiveDays,
                                     long scanIntervalMs, int batchSize) {
        this.metadataStore = metadataStore;
        this.vectorStore = vectorStore;
        this.compressThreshold = compressThreshold;
        this.archiveDays = archiveDays;
        this.scanIntervalMs = scanIntervalMs;
        this.batchSize = batchSize;
        this.bm25Scorer = new Bm25Scorer();
    }

    /**
     * 启动后台压缩线程
     */
    public void start() {
        if (running) {
            System.out.println("[MemoryCompression] 压缩线程已在运行");
            return;
        }

        running = true;
        scanThread = new Thread(this::scanLoop, "MemoryCompression-Scanner");
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[MemoryCompression] 后台压缩线程启动, 间隔=" + (scanIntervalMs / 1000 / 60 / 60) + "小时");
    }

    /**
     * 停止后台压缩线程
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
        System.out.println("[MemoryCompression] 后台压缩线程已停止");
    }

    /**
     * 后台扫描循环
     */
    private void scanLoop() {
        while (running) {
            try {
                // 等待指定间隔
                Thread.sleep(scanIntervalMs);

                // 执行压缩
                compress();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MemoryCompression] 压缩异常: " + e.getMessage());
                // 异常后继续运行
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
     * 执行一次完整压缩 (合并 + 归档)
     *
     * @return 压缩统计信息
     */
    public Map<String, Object> compress() {
        if (metadataStore == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("error", "元数据存储不可用");
            return empty;
        }

        System.out.println("[MemoryCompression] 开始压缩...");
        long startTime = System.currentTimeMillis();

        int totalScanned = 0;
        int totalMerged = 0;
        int totalArchived = 0;

        try {
            // 1. 执行相似记忆合并
            Map<String, Object> mergeResult = mergeSimilarMemories();
            totalScanned += (int) mergeResult.getOrDefault("scanned", 0);
            totalMerged += (int) mergeResult.getOrDefault("merged", 0);

            // 2. 执行历史记忆归档
            Map<String, Object> archiveResult = archiveOldMemories();
            totalArchived += (int) archiveResult.getOrDefault("archived", 0);

        } catch (Exception e) {
            System.err.println("[MemoryCompression] 压缩失败: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        scannedCount.addAndGet(totalScanned);
        mergedCount.addAndGet(totalMerged);
        archivedCount.addAndGet(totalArchived);

        Map<String, Object> result = new HashMap<>();
        result.put("scanned", totalScanned);
        result.put("merged", totalMerged);
        result.put("archived", totalArchived);
        result.put("elapsedMs", elapsed);

        System.out.printf("[MemoryCompression] 压缩完成: 扫描=%d, 合并=%d, 归档=%d, 耗时=%dms%n",
                totalScanned, totalMerged, totalArchived, elapsed);

        return result;
    }

    /**
     * 合并相似记忆
     * <p>
     * 查找向量相似度>阈值的记忆对，保留最新的内容，合并所有标签和权重。
     * </p>
     *
     * @return 合并统计信息
     */
    private Map<String, Object> mergeSimilarMemories() {
        int totalScanned = 0;
        int totalMerged = 0;

        try {
            // 查询所有活跃记忆
            Map<String, Object> filters = new HashMap<>();
            filters.put("status", "ACTIVE");
            List<MetadataRecord> activeMemories = metadataStore.find(METADATA_TABLE, filters, 10000, 0);

            if (activeMemories.size() < 2) {
                Map<String, Object> result = new HashMap<>();
                result.put("scanned", activeMemories.size());
                result.put("merged", 0);
                return result;
            }

            // 用于跟踪已合并的记忆ID
            Set<String> mergedIds = new HashSet<>();

            // 比较每一对记忆
            for (int i = 0; i < activeMemories.size(); i++) {
                MetadataRecord memA = activeMemories.get(i);
                if (mergedIds.contains(memA.getId())) continue;

                for (int j = i + 1; j < activeMemories.size(); j++) {
                    MetadataRecord memB = activeMemories.get(j);
                    if (mergedIds.contains(memB.getId())) continue;

                    totalScanned++;

                    // 计算综合相似度
                    double similarity = calculateSimilarity(memA, memB);

                    if (similarity > compressThreshold) {
                        // 执行合并
                        boolean merged = mergePair(memA, memB, similarity);
                        if (merged) {
                            // 保留较新的记忆，标记较旧的为已合并
                            MetadataRecord kept = memA.getCreatedAt().isAfter(memB.getCreatedAt()) ? memA : memB;
                            MetadataRecord discarded = kept == memA ? memB : memA;
                            mergedIds.add(discarded.getId());
                            totalMerged++;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[MemoryCompression] 合并相似记忆失败: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("scanned", totalScanned);
        result.put("merged", totalMerged);
        return result;
    }

    /**
     * 计算两个记忆的综合相似度
     */
    private double calculateSimilarity(MetadataRecord memA, MetadataRecord memB) {
        // BM25文本相似度
        double textSimilarity = bm25Scorer.similarity(memA.getContent(), memB.getContent());

        // 向量相似度
        double vectorSimilarity = calculateVectorSimilarity(memA.getId(), memB.getId());

        // 取较高值
        return Math.max(textSimilarity, vectorSimilarity);
    }

    /**
     * 计算两个记忆的向量相似度
     */
    private double calculateVectorSimilarity(String idA, String idB) {
        if (vectorStore == null) return 0.0;

        try {
            List<com.memoryplatform.model.VectorRecord> records = vectorStore.get(VECTOR_COLLECTION, List.of(idA, idB));
            if (records.size() < 2) return 0.0;

            float[] vecA = records.get(0).getVector();
            float[] vecB = records.get(1).getVector();

            if (vecA == null || vecB == null || vecA.length != vecB.length) return 0.0;

            // 余弦相似度
            double dotProduct = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < vecA.length; i++) {
                dotProduct += vecA[i] * vecB[i];
                normA += vecA[i] * vecA[i];
                normB += vecB[i] * vecB[i];
            }

            if (normA == 0 || normB == 0) return 0.0;
            return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        } catch (Exception e) {
            System.err.println("[MemoryCompression] 向量相似度计算失败: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * 合并一对相似记忆
     * <p>
     * 合并策略:
     * <ul>
     *   <li>保留最新记忆的内容</li>
     *   <li>合并所有标签</li>
     *   <li>取最高的重要性权重</li>
     *   <li>记录压缩来源</li>
     * </ul>
     * </p>
     */
    private boolean mergePair(MetadataRecord memA, MetadataRecord memB, double similarity) {
        try {
            // 确定保留哪个 (保留最新的)
            MetadataRecord kept = memA.getCreatedAt().isAfter(memB.getCreatedAt()) ? memA : memB;
            MetadataRecord discarded = kept == memA ? memB : memA;

            // 构建更新
            Map<String, Object> updates = new HashMap<>();
            updates.put("updatedAt", Instant.now().toString());

            // 合并重要性 (取最大值)
            double maxImportance = Math.max(kept.getImportance(), discarded.getImportance());
            updates.put("importance", maxImportance);

            // 记录压缩来源
            updates.put("status", "ACTIVE");
            updates.put("compressedFrom", Arrays.asList(discarded.getId()));
            updates.put("compressedAt", Instant.now().toString());
            updates.put("compressedSimilarity", similarity);

            // 合并标签 (如果有)
            if (kept.getData() != null && kept.getData().containsKey("tags")) {
                Set<String> tags = new HashSet<>();
                Object existingTags = kept.getData().get("tags");
                if (existingTags instanceof List) {
                    tags.addAll((List<String>) existingTags);
                }
                if (discarded.getData() != null && discarded.getData().containsKey("tags")) {
                    Object discardedTags = discarded.getData().get("tags");
                    if (discardedTags instanceof List) {
                        tags.addAll((List<String>) discardedTags);
                    }
                }
                updates.put("tags", new ArrayList<>(tags));
            }

            // 更新被保留的记忆
            metadataStore.update(METADATA_TABLE, kept.getId(), updates);

            // 标记被合并的记忆为 COMPRESSED
            Map<String, Object> discardedUpdates = new HashMap<>();
            discardedUpdates.put("status", "COMPRESSED");
            discardedUpdates.put("compressedInto", kept.getId());
            discardedUpdates.put("compressedAt", Instant.now().toString());
            discardedUpdates.put("compressedSimilarity", similarity);
            metadataStore.update(METADATA_TABLE, discarded.getId(), discardedUpdates);

            System.out.printf("[MemoryCompression] 合并记忆: %s → %s, 相似度=%.4f%n",
                    discarded.getId(), kept.getId(), similarity);

            return true;
        } catch (Exception e) {
            System.err.println("[MemoryCompression] 合并记忆失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 归档旧记忆
     * <p>
     * 将超过指定天数的记忆标记为 ARCHIVED 状态。
     * 归档记忆不参与常规检索 (除非显式请求)。
     * 归档记忆保留完整的元数据。
     * </p>
     *
     * @return 归档统计信息
     */
    private Map<String, Object> archiveOldMemories() {
        int totalArchived = 0;

        try {
            Instant cutoff = Instant.now().minus(archiveDays, ChronoUnit.DAYS);

            // 查询活跃记忆中创建时间早于截止日期的
            Map<String, Object> filters = new HashMap<>();
            filters.put("status", "ACTIVE");

            int offset = 0;
            List<MetadataRecord> batch;
            List<MetadataRecord> toArchive = new ArrayList<>();

            do {
                batch = metadataStore.find(METADATA_TABLE, filters, batchSize, offset);
                for (MetadataRecord record : batch) {
                    if (record.getCreatedAt() != null && record.getCreatedAt().isBefore(cutoff)) {
                        toArchive.add(record);
                    }
                }
                offset += batchSize;
            } while (batch.size() == batchSize);

            // 批量归档
            for (MetadataRecord record : toArchive) {
                try {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("status", "ARCHIVED");
                    updates.put("archivedAt", Instant.now().toString());
                    updates.put("updatedAt", Instant.now().toString());
                    updates.put("archiveReason", "超过" + archiveDays + "天未更新");

                    metadataStore.update(METADATA_TABLE, record.getId(), updates);
                    totalArchived++;
                } catch (Exception e) {
                    System.err.println("[MemoryCompression] 归档记忆失败: " + record.getId() + ", " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("[MemoryCompression] 归档旧记忆失败: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("archived", totalArchived);
        result.put("archiveDays", archiveDays);
        return result;
    }

    /**
     * 获取归档记忆
     *
     * @param limit  返回数量
     * @param offset 偏移量
     * @return 归档记忆列表
     */
    public List<Map<String, Object>> getArchivedMemories(int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (metadataStore == null) return result;

        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("status", "ARCHIVED");

            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, limit, offset);

            for (MetadataRecord record : records) {
                Map<String, Object> data = new HashMap<>();
                data.put("id", record.getId());
                data.put("text", record.getContent());
                data.put("userId", record.getUserId());
                data.put("agentId", record.getAgentId());
                data.put("importance", record.getImportance());
                data.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
                data.put("updatedAt", record.getUpdatedAt() != null ? record.getUpdatedAt().toString() : null);
                data.put("status", "ARCHIVED");
                if (record.getData() != null) {
                    data.put("metadata", record.getData());
                }
                result.add(data);
            }
        } catch (Exception e) {
            System.err.println("[MemoryCompression] 获取归档记忆失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取压缩统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("scannedCount", scannedCount.get());
        stats.put("mergedCount", mergedCount.get());
        stats.put("archivedCount", archivedCount.get());
        stats.put("compressThreshold", compressThreshold);
        stats.put("archiveDays", archiveDays);
        stats.put("scanIntervalMs", scanIntervalMs);
        stats.put("running", running);
        return stats;
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        scannedCount.set(0);
        mergedCount.set(0);
        archivedCount.set(0);
    }
}
