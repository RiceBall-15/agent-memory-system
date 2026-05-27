package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆衰减服务 - 基于时间的指数权重衰减
 * <p>
 * 衰减公式: weight = baseWeight * exp(-lambda * daysSinceLastAccess)
 * <ul>
 *   <li>每次访问(检索/更新)重置衰减时间(lastAccessTime)</li>
 *   <li>后台线程每天执行一次衰减计算</li>
 *   <li>衰减后的权重影响检索排序</li>
 *   <li>最小权重minWeight防止完全衰减(默认0.1)</li>
 * </ul>
 *
 * <h3>默认参数</h3>
 * <ul>
 *   <li>lambda(衰减率): 0.01 → 约70天半衰期</li>
 *   <li>baseWeight(基础权重): 1.0</li>
 *   <li>minWeight(最小权重): 0.1</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @since 1.0
 */
public class MemoryDecayService {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 默认衰减率 lambda (约70天半衰期) */
    private static final double DEFAULT_LAMBDA = 0.01;

    /** 默认基础权重 */
    private static final double DEFAULT_BASE_WEIGHT = 1.0;

    /** 默认最小权重（防止完全衰减） */
    private static final double DEFAULT_MIN_WEIGHT = 0.1;

    /** 默认扫描间隔（毫秒） - 24小时 */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 86_400_000L;

    /** 默认批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 200;

    /** 存储依赖 */
    private final MetadataStore metadataStore;

    /** 衰减率 lambda */
    private final double lambda;

    /** 基础权重 */
    private final double baseWeight;

    /** 最小权重 */
    private final double minWeight;

    /** 扫描间隔（毫秒） */
    private final long scanIntervalMs;

    /** 批次大小 */
    private final int batchSize;

    /** 扫描线程 */
    private Thread scanThread;

    /** 扫描状态 */
    private volatile boolean running = false;

    /** 统计计数器 */
    private final AtomicLong decayCalculations = new AtomicLong(0);
    private final AtomicLong weightChanges = new AtomicLong(0);

    /**
     * 构造函数（使用默认参数）
     *
     * @param metadataStore 元数据存储
     */
    public MemoryDecayService(MetadataStore metadataStore) {
        this(metadataStore, DEFAULT_LAMBDA, DEFAULT_BASE_WEIGHT, DEFAULT_MIN_WEIGHT,
                DEFAULT_SCAN_INTERVAL_MS, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义参数构造函数
     *
     * @param metadataStore  元数据存储
     * @param lambda         衰减率 (越大衰减越快)
     * @param baseWeight     基础权重
     * @param minWeight      最小权重
     * @param scanIntervalMs 扫描间隔（毫秒）
     * @param batchSize      批次大小
     */
    public MemoryDecayService(MetadataStore metadataStore, double lambda, double baseWeight,
                               double minWeight, long scanIntervalMs, int batchSize) {
        this.metadataStore = metadataStore;
        this.lambda = lambda;
        this.baseWeight = baseWeight;
        this.minWeight = minWeight;
        this.scanIntervalMs = scanIntervalMs;
        this.batchSize = batchSize;

        System.out.println("[MemoryDecay] init: lambda=" + lambda
                + ", baseWeight=" + baseWeight
                + ", minWeight=" + minWeight
                + ", scanInterval=" + (scanIntervalMs / 3600_000) + "h");
    }

    /**
     * 计算记忆的衰减权重
     *
     * @param memoryId       记忆ID
     * @param lastAccessTime 最后访问时间
     * @param importance     原始importance值(作为baseWeight的备选)
     * @return 衰减后的权重
     */
    public double calculateDecayWeight(String memoryId, Instant lastAccessTime, double importance) {
        if (lastAccessTime == null) {
            lastAccessTime = Instant.now();
        }

        // 计算距最后访问的天数
        long daysSinceAccess = ChronoUnit.DAYS.between(lastAccessTime, Instant.now());
        if (daysSinceAccess < 0) daysSinceAccess = 0;

        // 使用importance作为实际baseWeight（如果>0），否则使用默认baseWeight
        double actualBase = (importance > 0) ? importance : baseWeight;

        // 指数衰减: weight = baseWeight * exp(-lambda * days)
        double weight = actualBase * Math.exp(-lambda * daysSinceAccess);

        // 应用最小权重保护
        weight = Math.max(weight, minWeight);

        // 确保不超过1.0
        weight = Math.min(weight, 1.0);

        return weight;
    }

    /**
     * 计算衰减权重（简化版，使用默认baseWeight）
     *
     * @param lastAccessTime 最后访问时间
     * @return 衰减后的权重
     */
    public double calculateDecayWeight(Instant lastAccessTime) {
        return calculateDecayWeight(null, lastAccessTime, baseWeight);
    }

    /**
     * 获取记忆的衰减权重（从MetadataStore读取）
     *
     * @param memoryId 记忆ID
     * @return 衰减后的权重
     */
    public double getDecayWeight(String memoryId) {
        if (metadataStore == null || memoryId == null) {
            return baseWeight;
        }

        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", memoryId);
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (records.isEmpty()) {
                return baseWeight;
            }

            MetadataRecord record = records.get(0);
            Instant lastAccessTime = record.getUpdatedAt(); // 使用updatedAt作为lastAccessTime
            double importance = record.getImportance();

            // 如果已存储decayWeight，优先使用缓存值
            Map<String, Object> data = record.getData();
            if (data != null && data.containsKey("decayWeight")) {
                try {
                    double cachedWeight = Double.parseDouble(data.get("decayWeight").toString());
                    // 重新计算以确保时效性（缓存可能过期）
                    double freshWeight = calculateDecayWeight(memoryId, lastAccessTime, importance);
                    // 使用缓存值和新鲜值的加权平均（平滑更新）
                    return (cachedWeight + freshWeight) / 2.0;
                } catch (NumberFormatException e) {
                    // 缓存损坏，使用计算值
                }
            }

            return calculateDecayWeight(memoryId, lastAccessTime, importance);
        } catch (Exception e) {
            System.err.println("[MemoryDecay] 获取衰减权重失败: " + e.getMessage());
            return baseWeight;
        }
    }

    /**
     * 重置记忆的访问时间（检索/更新时调用）
     *
     * @param memoryId 记忆ID
     * @return 是否重置成功
     */
    public boolean resetAccessTime(String memoryId) {
        if (metadataStore == null || memoryId == null) {
            return false;
        }

        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("lastAccessTime", Instant.now().toString());
            updates.put("updatedAt", Instant.now().toString());

            // 重置后重新计算权重
            double newWeight = calculateDecayWeight(memoryId, Instant.now(), baseWeight);
            updates.put("decayWeight", String.valueOf(newWeight));

            boolean success = metadataStore.update(METADATA_TABLE, memoryId, updates);
            if (success) {
                System.out.printf("[MemoryDecay] 重置访问时间: id=%s, newWeight=%.4f%n",
                        memoryId, newWeight);
            }
            return success;
        } catch (Exception e) {
            System.err.println("[MemoryDecay] 重置访问时间失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启动后台衰减计算线程
     */
    public void start() {
        if (running) {
            System.out.println("[MemoryDecay] 衰减计算线程已在运行");
            return;
        }

        running = true;
        scanThread = new Thread(this::scanLoop, "MemoryDecay-Calculator");
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[MemoryDecay] 后台衰减计算线程启动, 间隔=" + (scanIntervalMs / 3600_000) + "小时");
    }

    /**
     * 停止后台衰减计算线程
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
        System.out.println("[MemoryDecay] 后台衰减计算线程已停止");
    }

    /**
     * 后台扫描循环
     */
    private void scanLoop() {
        while (running) {
            try {
                Thread.sleep(scanIntervalMs);
                calculateAllDecayWeights();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MemoryDecay] 衰减计算异常: " + e.getMessage());
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
     * 执行一次全量衰减计算
     */
    public void calculateAllDecayWeights() {
        if (metadataStore == null) {
            return;
        }

        System.out.println("[MemoryDecay] 开始衰减计算...");
        long startTime = System.currentTimeMillis();
        int totalCalculated = 0;
        int totalChanged = 0;

        try {
            int offset = 0;
            List<MetadataRecord> batch;

            do {
                // 分批查询活跃记忆
                Map<String, Object> filters = new HashMap<>();
                filters.put("status", "active");
                batch = metadataStore.find(METADATA_TABLE, filters, batchSize, offset);

                for (MetadataRecord record : batch) {
                    try {
                        totalCalculated++;

                        // 获取lastAccessTime，如果没有则使用updatedAt
                        Instant lastAccessTime = null;
                        Map<String, Object> data = record.getData();
                        if (data != null && data.containsKey("lastAccessTime")) {
                            lastAccessTime = Instant.parse(data.get("lastAccessTime").toString());
                        }
                        if (lastAccessTime == null) {
                            lastAccessTime = record.getUpdatedAt();
                        }
                        if (lastAccessTime == null) {
                            lastAccessTime = record.getCreatedAt();
                        }

                        double importance = record.getImportance();
                        double newWeight = calculateDecayWeight(record.getId(), lastAccessTime, importance);

                        // 检查权重是否有显著变化（>0.01）
                        double oldWeight = baseWeight; // 默认权重
                        if (data != null && data.containsKey("decayWeight")) {
                            try {
                                oldWeight = Double.parseDouble(data.get("decayWeight").toString());
                            } catch (NumberFormatException e) {
                                // 使用默认值
                            }
                        }

                        if (Math.abs(newWeight - oldWeight) > 0.01) {
                            // 权重有显著变化，更新元数据
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("decayWeight", String.valueOf(newWeight));
                            updates.put("lastDecayCalculation", Instant.now().toString());
                            metadataStore.update(METADATA_TABLE, record.getId(), updates);
                            totalChanged++;
                        }

                    } catch (Exception e) {
                        System.err.println("[MemoryDecay] 计算记忆衰减失败: id="
                                + record.getId() + ", error=" + e.getMessage());
                    }
                }

                offset += batchSize;
            } while (batch.size() == batchSize);

        } catch (Exception e) {
            System.err.println("[MemoryDecay] 衰减计算扫描失败: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        decayCalculations.addAndGet(totalCalculated);
        weightChanges.addAndGet(totalChanged);

        System.out.printf("[MemoryDecay] 衰减计算完成: 计算=%d, 变化=%d, 耗时=%dms%n",
                totalCalculated, totalChanged, elapsed);
    }

    /**
     * 获取衰减统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("decayCalculations", decayCalculations.get());
        stats.put("weightChanges", weightChanges.get());
        stats.put("lambda", lambda);
        stats.put("baseWeight", baseWeight);
        stats.put("minWeight", minWeight);
        stats.put("scanIntervalMs", scanIntervalMs);
        stats.put("running", running);
        stats.put("halfLifeDays", Math.log(2) / lambda); // 半衰期
        return stats;
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        decayCalculations.set(0);
        weightChanges.set(0);
    }
}
