package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆TTL过期服务
 * <p>
 * 每条记忆可设置过期时间(expireAt字段)。
 * 后台线程定期清理过期记忆（每5分钟扫描一次）。
 * 使用MetadataStore标记过期，而不是立即删除（保留审计追踪）。
 * </p>
 */
public class MemoryTtlService {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 默认TTL（天） */
    private static final int DEFAULT_TTL_DAYS = 30;

    /** 默认扫描间隔（毫秒） - 5分钟 */
    private static final long DEFAULT_SCAN_INTERVAL_MS = 300_000;

    /** 默认批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /** 存储依赖 */
    private final MetadataStore metadataStore;

    /** 默认TTL（天） */
    private final int defaultTtlDays;

    /** 扫描间隔（毫秒） */
    private final long scanIntervalMs;

    /** 批次大小 */
    private final int batchSize;

    /** 用户/Agent默认TTL映射 */
    private final Map<String, Integer> userTtlOverrides = new ConcurrentHashMap<>();

    /** 扫描线程 */
    private Thread scanThread;

    /** 扫描状态 */
    private volatile boolean running = false;

    /** 统计计数器 */
    private final AtomicLong scannedCount = new AtomicLong(0);
    private final AtomicLong expiredCount = new AtomicLong(0);
    private final AtomicLong cleanedCount = new AtomicLong(0);

    /**
     * 构造函数（使用默认参数）
     *
     * @param metadataStore 元数据存储
     */
    public MemoryTtlService(MetadataStore metadataStore) {
        this(metadataStore, DEFAULT_TTL_DAYS, DEFAULT_SCAN_INTERVAL_MS, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义参数构造函数
     *
     * @param metadataStore 元数据存储
     * @param defaultTtlDays 默认TTL（天）
     * @param scanIntervalMs 扫描间隔（毫秒）
     * @param batchSize      批次大小
     */
    public MemoryTtlService(MetadataStore metadataStore, int defaultTtlDays,
                             long scanIntervalMs, int batchSize) {
        this.metadataStore = metadataStore;
        this.defaultTtlDays = defaultTtlDays;
        this.scanIntervalMs = scanIntervalMs;
        this.batchSize = batchSize;
    }

    /**
     * 为记忆设置过期时间
     *
     * @param memoryId 记忆ID
     * @param expireAt 过期时间
     * @return 是否设置成功
     */
    public boolean setExpiration(String memoryId, Instant expireAt) {
        if (metadataStore == null || memoryId == null) {
            return false;
        }

        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("expireAt", expireAt.toString());
            updates.put("updatedAt", Instant.now().toString());

            boolean success = metadataStore.update(METADATA_TABLE, memoryId, updates);
            if (success) {
                System.out.printf("[MemoryTTL] 设置过期时间: id=%s, expireAt=%s%n",
                        memoryId, expireAt);
            }
            return success;
        } catch (Exception e) {
            System.err.println("[MemoryTTL] 设置过期时间失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 为记忆设置TTL（从当前时间开始计算）
     *
     * @param memoryId 记忆ID
     * @param ttlDays  TTL天数
     * @return 是否设置成功
     */
    public boolean setTtl(String memoryId, int ttlDays) {
        Instant expireAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        return setExpiration(memoryId, expireAt);
    }

    /**
     * 设置用户/Agent的默认TTL
     *
     * @param key     用户ID或AgentID（格式: "user:xxx" 或 "agent:xxx"）
     * @param ttlDays TTL天数
     */
    public void setDefaultTtl(String key, int ttlDays) {
        userTtlOverrides.put(key, ttlDays);
        System.out.printf("[MemoryTTL] 设置默认TTL: key=%s, ttlDays=%d%n", key, ttlDays);
    }

    /**
     * 获取指定用户的TTL天数
     *
     * @param userId  用户ID
     * @param agentId AgentID（可为null）
     * @return TTL天数
     */
    public int getTtlDays(String userId, String agentId) {
        // 优先使用Agent级TTL
        if (agentId != null) {
            Integer agentTtl = userTtlOverrides.get("agent:" + agentId);
            if (agentTtl != null) return agentTtl;
        }

        // 其次使用用户级TTL
        if (userId != null) {
            Integer userTtl = userTtlOverrides.get("user:" + userId);
            if (userTtl != null) return userTtl;
        }

        // 返回默认TTL
        return defaultTtlDays;
    }

    /**
     * 为新记忆自动设置TTL
     *
     * @param memoryId 记忆ID
     * @param userId   用户ID
     * @param agentId  AgentID
     * @return 过期时间
     */
    public Instant autoSetTtl(String memoryId, String userId, String agentId) {
        int ttlDays = getTtlDays(userId, agentId);
        Instant expireAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        setExpiration(memoryId, expireAt);
        return expireAt;
    }

    /**
     * 检查记忆是否已过期
     *
     * @param memoryId 记忆ID
     * @return 是否已过期
     */
    public boolean isExpired(String memoryId) {
        if (metadataStore == null) {
            return false;
        }

        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", memoryId);
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (records.isEmpty()) return false;

            MetadataRecord record = records.get(0);
            Map<String, Object> data = record.getData();
            if (data == null || !data.containsKey("expireAt")) {
                return false; // 未设置过期时间，永不过期
            }

            String expireAtStr = data.get("expireAt").toString();
            Instant expireAt = Instant.parse(expireAtStr);
            return Instant.now().isAfter(expireAt);
        } catch (Exception e) {
            System.err.println("[MemoryTTL] 检查过期状态失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 启动后台扫描线程
     */
    public void start() {
        if (running) {
            System.out.println("[MemoryTTL] 扫描线程已在运行");
            return;
        }

        running = true;
        scanThread = new Thread(this::scanLoop, "MemoryTTL-Scanner");
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[MemoryTTL] 后台扫描线程启动, 间隔=" + (scanIntervalMs / 1000) + "秒"
                + ", 默认TTL=" + defaultTtlDays + "天");
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
        System.out.println("[MemoryTTL] 后台扫描线程已停止");
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
                scanAndCleanup();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[MemoryTTL] 扫描异常: " + e.getMessage());
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
     * 执行一次过期扫描和清理
     */
    public void scanAndCleanup() {
        if (metadataStore == null) {
            return;
        }

        System.out.println("[MemoryTTL] 开始过期扫描...");
        long startTime = System.currentTimeMillis();
        int totalScanned = 0;
        int totalExpired = 0;
        int totalCleaned = 0;

        Instant now = Instant.now();

        try {
            int offset = 0;
            List<MetadataRecord> batch;

            do {
                // 分批查询（查询所有活跃记忆）
                Map<String, Object> filters = new HashMap<>();
                filters.put("status", "active");
                batch = metadataStore.find(METADATA_TABLE, filters, batchSize, offset);

                for (MetadataRecord record : batch) {
                    totalScanned++;

                    // 检查是否设置了过期时间
                    Map<String, Object> data = record.getData();
                    if (data == null || !data.containsKey("expireAt")) {
                        continue; // 未设置过期时间，跳过
                    }

                    try {
                        String expireAtStr = data.get("expireAt").toString();
                        Instant expireAt = Instant.parse(expireAtStr);

                        if (now.isAfter(expireAt)) {
                            // 已过期，标记为过期（保留审计追踪）
                            markExpired(record);
                            totalExpired++;

                            // 尝试清理（如果允许）
                            if (shouldCleanup(record)) {
                                cleanupExpired(record);
                                totalCleaned++;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[MemoryTTL] 处理记录失败: id=" + record.getId()
                                + ", error=" + e.getMessage());
                    }
                }

                offset += batchSize;
            } while (batch.size() == batchSize);

        } catch (Exception e) {
            System.err.println("[MemoryTTL] 扫描失败: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        scannedCount.addAndGet(totalScanned);
        expiredCount.addAndGet(totalExpired);
        cleanedCount.addAndGet(totalCleaned);

        System.out.printf("[MemoryTTL] 扫描完成: 扫描=%d, 过期=%d, 清理=%d, 耗时=%dms%n",
                totalScanned, totalExpired, totalCleaned, elapsed);
    }

    /**
     * 标记记忆为过期（保留审计追踪）
     */
    private void markExpired(MetadataRecord record) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "expired");
            updates.put("expiredAt", Instant.now().toString());
            updates.put("updatedAt", Instant.now().toString());

            metadataStore.update(METADATA_TABLE, record.getId(), updates);
            System.out.printf("[MemoryTTL] 标记过期: id=%s%n", record.getId());
        } catch (Exception e) {
            System.err.println("[MemoryTTL] 标记过期失败: " + e.getMessage());
        }
    }

    /**
     * 判断是否应该清理过期记忆
     * <p>
     * 保留策略：高重要性记忆保留更长时间
     * </p>
     */
    private boolean shouldCleanup(MetadataRecord record) {
        // 高重要性记忆（>0.8）不自动清理
        if (record.getImportance() > 0.8) {
            return false;
        }

        // 已经过期超过7天的才清理
        Map<String, Object> data = record.getData();
        if (data != null && data.containsKey("expiredAt")) {
            try {
                Instant expiredAt = Instant.parse(data.get("expiredAt").toString());
                return Instant.now().isAfter(expiredAt.plus(7, ChronoUnit.DAYS));
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    /**
     * 清理过期记忆
     */
    private void cleanupExpired(MetadataRecord record) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "archived");
            updates.put("archivedAt", Instant.now().toString());
            updates.put("updatedAt", Instant.now().toString());

            metadataStore.update(METADATA_TABLE, record.getId(), updates);
            System.out.printf("[MemoryTTL] 清理归档: id=%s%n", record.getId());
        } catch (Exception e) {
            System.err.println("[MemoryTTL] 清理失败: " + e.getMessage());
        }
    }

    /**
     * 获取过期统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("scannedCount", scannedCount.get());
        stats.put("expiredCount", expiredCount.get());
        stats.put("cleanedCount", cleanedCount.get());
        stats.put("defaultTtlDays", defaultTtlDays);
        stats.put("scanIntervalMs", scanIntervalMs);
        stats.put("running", running);
        stats.put("userTtlOverrides", new HashMap<>(userTtlOverrides));
        return stats;
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        scannedCount.set(0);
        expiredCount.set(0);
        cleanedCount.set(0);
    }
}
