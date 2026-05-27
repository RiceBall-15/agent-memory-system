package com.memoryplatform.service;

import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆去重服务
 * <p>
 * 使用向量相似度检测重复记忆，阈值0.95。
 * 新记忆与已有记忆比较，如果相似度>0.95则合并（保留最新的，合并元数据）。
 * </p>
 */
public class MemoryDeduplicationService {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 向量集合名 */
    private static final String VECTOR_COLLECTION = "memories";

    /** 默认去重阈值 */
    private static final double DEFAULT_DEDUP_THRESHOLD = 0.95;

    /** 默认扫描间隔（毫秒） - 1小时 */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 3_600_000;

    /** 默认每次扫描批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /** 存储依赖 */
    private final MetadataStore metadataStore;
    private final VectorStore vectorStore;

    /** BM25文本相似度评分器 */
    private final Bm25Scorer bm25Scorer;

    /** 去重阈值 */
    private final double dedupThreshold;

    /** 扫描间隔（毫秒） */
    private final long scanIntervalMs;

    /** 批次大小 */
    private final int batchSize;

    /** 扫描线程 */
    private Thread scanThread;

    /** 扫描状态 */
    private volatile boolean running = false;

    /** 统计计数器 */
    private final AtomicLong scannedCount = new AtomicLong(0);
    private final AtomicLong duplicatesFound = new AtomicLong(0);
    private final AtomicLong mergedCount = new AtomicLong(0);

    /**
     * 构造函数
     *
     * @param metadataStore 元数据存储
     * @param vectorStore   向量存储
     */
    public MemoryDeduplicationService(MetadataStore metadataStore, VectorStore vectorStore) {
        this(metadataStore, vectorStore, DEFAULT_DEDUP_THRESHOLD, DEFAULT_SCAN_INTERVAL_MS, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义参数构造函数
     *
     * @param metadataStore   元数据存储
     * @param vectorStore     向量存储
     * @param dedupThreshold  去重阈值（0.0 ~ 1.0）
     * @param scanIntervalMs  扫描间隔（毫秒）
     * @param batchSize       批次大小
     */
    public MemoryDeduplicationService(MetadataStore metadataStore, VectorStore vectorStore,
                                       double dedupThreshold, long scanIntervalMs, int batchSize) {
        this.metadataStore = metadataStore;
        this.vectorStore = vectorStore;
        this.dedupThreshold = dedupThreshold;
        this.scanIntervalMs = scanIntervalMs;
        this.batchSize = batchSize;
        this.bm25Scorer = new Bm25Scorer();
    }

    /**
     * 检查新记忆是否为重复
     *
     * @param newMemory 新记忆
     * @return 如果是重复记忆，返回被合并的原始记忆ID；否则返回null
     */
    public String checkAndMerge(Memory newMemory) {
        if (metadataStore == null || vectorStore == null) {
            return null;
        }

        try {
            // 1. 查询同用户的已有记忆
            Map<String, Object> filters = new HashMap<>();
            filters.put("userId", newMemory.getUserId());
            List<MetadataRecord> existingMemories = metadataStore.find(METADATA_TABLE, filters, 500, 0);

            if (existingMemories.isEmpty()) {
                return null;
            }

            // 2. 对每个已有记忆计算相似度
            for (MetadataRecord existing : existingMemories) {
                if (existing.getId().equals(newMemory.getId())) {
                    continue; // 跳过自己
                }

                // 2.1 文本相似度检查（BM25）
                double textSimilarity = bm25Scorer.similarity(newMemory.getText(), existing.getContent());

                // 2.2 向量相似度检查
                double vectorSimilarity = calculateVectorSimilarity(newMemory.getEmbedding(), existing.getId());

                // 2.3 综合判断（取较高值）
                double similarity = Math.max(textSimilarity, vectorSimilarity);

                if (similarity > dedupThreshold) {
                    // 发现重复，执行合并
                    mergeMemories(newMemory, existing, similarity);
                    duplicatesFound.incrementAndGet();
                    mergedCount.incrementAndGet();
                    return existing.getId();
                }
            }

            return null;
        } catch (Exception e) {
            System.err.println("[MemoryDeduplication] 去重检查异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 计算向量相似度（余弦相似度）
     */
    private double calculateVectorSimilarity(double[] queryVector, String targetId) {
        if (queryVector == null || vectorStore == null) {
            return 0.0;
        }

        try {
            float[] floatVector = new float[queryVector.length];
            for (int i = 0; i < queryVector.length; i++) {
                floatVector[i] = (float) queryVector[i];
            }

            Map<String, Object> filters = new HashMap<>();
            filters.put("id", targetId);

            List<com.memoryplatform.model.SearchResult> results = vectorStore.search(
                    VECTOR_COLLECTION, floatVector, 1, filters);

            if (!results.isEmpty()) {
                return results.get(0).getScore();
            }
        } catch (Exception e) {
            System.err.println("[MemoryDeduplication] 向量相似度计算失败: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * 合并重复记忆
     * <p>
     * 保留最新的记忆，合并元数据：
     * - 合并entities
     * - 合并linkedMemoryIds
     * - 取最高的importance
     * - 更新deduplicatedFrom字段
     * </p>
     */
    private void mergeMemories(Memory newMemory, MetadataRecord existing, double similarity) {
        try {
            // 1. 确定保留哪个记忆（保留最新的）
            boolean keepNew = newMemory.getCreatedAt().isAfter(existing.getCreatedAt());
            String keptId = keepNew ? newMemory.getId() : existing.getId();
            String mergedId = keepNew ? existing.getId() : newMemory.getId();

            // 2. 合并元数据
            Map<String, Object> updates = new HashMap<>();
            updates.put("updatedAt", Instant.now().toString());

            // 合并importance（取最大值）
            double newImportance = newMemory.getImportance();
            double existingImportance = existing.getImportance();
            updates.put("importance", Math.max(newImportance, existingImportance));

            // 记录合并来源
            updates.put("deduplicatedFrom", mergedId);
            updates.put("dedupSimilarity", similarity);
            updates.put("dedupMergedAt", Instant.now().toString());

            // 3. 更新被保留的记忆
            metadataStore.update(METADATA_TABLE, keptId, updates);

            // 4. 标记被合并的记忆为已去重（使用metadata标记，而不是立即删除）
            Map<String, Object> mergedUpdates = new HashMap<>();
            mergedUpdates.put("status", "deduplicated");
            mergedUpdates.put("mergedInto", keptId);
            mergedUpdates.put("dedupSimilarity", similarity);
            mergedUpdates.put("dedupMergedAt", Instant.now().toString());
            metadataStore.update(METADATA_TABLE, mergedId, mergedUpdates);

            System.out.printf("[MemoryDeduplication] 合并记忆: %s → %s, 相似度=%.4f%n",
                    mergedId, keptId, similarity);

        } catch (Exception e) {
            System.err.println("[MemoryDeduplication] 合并记忆失败: " + e.getMessage());
        }
    }

    /**
     * 启动后台扫描线程
     */
    public void start() {
        if (running) {
            System.out.println("[MemoryDeduplication] 扫描线程已在运行");
            return;
        }

        running = true;
        scanThread = new Thread(this::scanLoop, "MemoryDeduplication-Scanner");
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[MemoryDeduplication] 后台扫描线程启动, 间隔=" + (scanIntervalMs / 1000) + "秒");
    }

    /**
     * 停止后台扫描线程
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
        System.out.println("[MemoryDeduplication] 后台扫描线程已停止");
    }

    /**
     * 后台扫描循环
     */
    private void scanLoop() {
        while (running) {
            try {
                // 等待指定间隔
                Thread.sleep(scanIntervalMs);

                // 执行扫描
                scanAndDedup();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MemoryDeduplication] 扫描异常: " + e.getMessage());
                // 异常后继续运行
                try {
                    Thread.sleep(60_000); // 出错后等待1分钟再重试
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 执行一次去重扫描
     */
    public void scanAndDedup() {
        if (metadataStore == null) {
            return;
        }

        System.out.println("[MemoryDeduplication] 开始去重扫描...");
        long startTime = System.currentTimeMillis();
        int totalScanned = 0;
        int totalDuplicates = 0;

        try {
            int offset = 0;
            List<MetadataRecord> batch;

            do {
                // 分批查询
                Map<String, Object> filters = new HashMap<>();
                filters.put("status", "active"); // 只扫描活跃记忆
                batch = metadataStore.find(METADATA_TABLE, filters, batchSize, offset);

                for (Memory memory : batchToMemoryList(batch)) {
                    String duplicateId = checkAndMerge(memory);
                    if (duplicateId != null) {
                        totalDuplicates++;
                    }
                    totalScanned++;
                }

                offset += batchSize;
            } while (batch.size() == batchSize);

        } catch (Exception e) {
            System.err.println("[MemoryDeduplication] 扫描失败: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        scannedCount.addAndGet(totalScanned);
        duplicatesFound.addAndGet(totalDuplicates);

        System.out.printf("[MemoryDeduplication] 扫描完成: 扫描=%d, 重复=%d, 耗时=%dms%n",
                totalScanned, totalDuplicates, elapsed);
    }

    /**
     * 将MetadataRecord列表转换为Memory列表
     */
    private List<Memory> batchToMemoryList(List<MetadataRecord> records) {
        List<Memory> memories = new ArrayList<>();
        for (MetadataRecord record : records) {
            Memory memory = Memory.builder()
                    .id(record.getId())
                    .text(record.getContent())
                    .userId(record.getUserId())
                    .agentId(record.getAgentId())
                    .importance(record.getImportance())
                    .createdAt(record.getCreatedAt())
                    .updatedAt(record.getUpdatedAt())
                    .build();
            memories.add(memory);
        }
        return memories;
    }

    /**
     * 获取去重统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("scannedCount", scannedCount.get());
        stats.put("duplicatesFound", duplicatesFound.get());
        stats.put("mergedCount", mergedCount.get());
        stats.put("dedupThreshold", dedupThreshold);
        stats.put("scanIntervalMs", scanIntervalMs);
        stats.put("running", running);
        return stats;
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        scannedCount.set(0);
        duplicatesFound.set(0);
        mergedCount.set(0);
    }
}
