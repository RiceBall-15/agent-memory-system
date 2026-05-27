package com.memoryplatform.service;

import com.memoryplatform.model.AuditLog;
import com.memoryplatform.storage.MetadataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jakarta.annotation.PostConstruct;

/**
 * 审计日志服务
 * <p>
 * 记录所有CRUD操作，提供日志查询和自动清理功能。
 * 保留最近 {@value #MAX_LOGS} 条日志。
 * </p>
 *
 * <h3>存储策略</h3>
 * <ul>
 *   <li>使用内存队列存储最近日志（高效写入）</li>
 *   <li>定期持久化到MetadataStore的 {@code audit_logs} 表</li>
 *   <li>支持按memoryId、userId、时间范围查询</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    /** 日志存储表名 */
    private static final String LOGS_TABLE = "audit_logs";

    /** 最大保留日志数 */
    private static final int MAX_LOGS = 10000;

    /** 最大查询返回数 */
    private static final int MAX_QUERY_LIMIT = 500;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /** 内存日志队列（FIFO，最新在尾部） */
    private final ConcurrentLinkedDeque<AuditLog> logQueue = new ConcurrentLinkedDeque<>();

    /** 读写锁（保护日志清理） */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 日志计数器 */
    private volatile long logCounter = 0;

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[AuditLogService] 初始化完成, maxLogs={}", MAX_LOGS);
    }

    /**
     * 记录操作日志
     *
     * @param action   操作类型 (CREATE/UPDATE/DELETE/QUERY/ROLLBACK/MERGE)
     * @param userId   用户ID（可为null）
     * @param agentId  Agent ID（可为null）
     * @param memoryId 记忆ID（可为null）
     * @param details  详细信息
     * @param sourceIp 来源IP（可为null）
     * @return 创建的日志对象
     */
    public AuditLog log(String action, String userId, String agentId,
                         String memoryId, Map<String, Object> details,
                         String sourceIp) {
        String logId = "audit_" + System.currentTimeMillis() + "_" + (++logCounter);

        AuditLog log = AuditLog.builder()
                .logId(logId)
                .action(action)
                .userId(userId)
                .agentId(agentId)
                .memoryId(memoryId)
                .details(details)
                .timestamp(Instant.now())
                .sourceIp(sourceIp)
                .build();

        lock.writeLock().lock();
        try {
            logQueue.addLast(log);

            // 超过限制，移除最旧的日志
            while (logQueue.size() > MAX_LOGS) {
                AuditLog removed = logQueue.pollFirst();
                if (removed != null) {
                    logCounter--;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        return log;
    }

    /**
     * 便捷方法：记录CREATE操作
     */
    public AuditLog logCreate(String userId, String agentId, String memoryId,
                               Map<String, Object> details, String sourceIp) {
        return log("CREATE", userId, agentId, memoryId, details, sourceIp);
    }

    /**
     * 便捷方法：记录UPDATE操作
     */
    public AuditLog logUpdate(String userId, String agentId, String memoryId,
                               Map<String, Object> details, String sourceIp) {
        return log("UPDATE", userId, agentId, memoryId, details, sourceIp);
    }

    /**
     * 便捷方法：记录DELETE操作
     */
    public AuditLog logDelete(String userId, String agentId, String memoryId,
                               Map<String, Object> details, String sourceIp) {
        return log("DELETE", userId, agentId, memoryId, details, sourceIp);
    }

    /**
     * 便捷方法：记录ROLLBACK操作
     */
    public AuditLog logRollback(String userId, String agentId, String memoryId,
                                 Map<String, Object> details, String sourceIp) {
        return log("ROLLBACK", userId, agentId, memoryId, details, sourceIp);
    }

    /**
     * 获取记忆的操作日志
     *
     * @param memoryId 记忆ID
     * @return 日志列表（最新在前）
     */
    public List<AuditLog> getLogs(String memoryId) {
        List<AuditLog> result = new ArrayList<>();

        lock.readLock().lock();
        try {
            // 从最新往旧遍历
            Iterator<AuditLog> it = logQueue.descendingIterator();
            while (it.hasNext()) {
                AuditLog log = it.next();
                if (memoryId.equals(log.getMemoryId())) {
                    result.add(log);
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    /**
     * 按用户和时间范围查询日志
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 日志列表（最新在前）
     */
    public List<AuditLog> getLogs(String userId, Instant startTime, Instant endTime) {
        List<AuditLog> result = new ArrayList<>();

        lock.readLock().lock();
        try {
            Iterator<AuditLog> it = logQueue.descendingIterator();
            while (it.hasNext() && result.size() < MAX_QUERY_LIMIT) {
                AuditLog log = it.next();
                if (userId != null && !userId.equals(log.getUserId())) {
                    continue;
                }
                if (startTime != null && log.getTimestamp().isBefore(startTime)) {
                    continue;
                }
                if (endTime != null && log.getTimestamp().isAfter(endTime)) {
                    continue;
                }
                result.add(log);
            }
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    /**
     * 获取最近N条日志
     *
     * @param limit 返回数量（最大 {@value #MAX_QUERY_LIMIT}）
     * @return 日志列表（最新在前）
     */
    public List<AuditLog> getRecentLogs(int limit) {
        limit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        List<AuditLog> result = new ArrayList<>();

        lock.readLock().lock();
        try {
            Iterator<AuditLog> it = logQueue.descendingIterator();
            int count = 0;
            while (it.hasNext() && count < limit) {
                result.add(it.next());
                count++;
            }
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    /**
     * 获取日志统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalLogs", logQueue.size());
        stats.put("maxLogs", MAX_LOGS);

        // 统计各操作类型
        Map<String, Long> actionCounts = new HashMap<>();
        lock.readLock().lock();
        try {
            for (AuditLog log : logQueue) {
                actionCounts.merge(log.getAction(), 1L, Long::sum);
            }
        } finally {
            lock.readLock().unlock();
        }
        stats.put("actionCounts", actionCounts);

        return stats;
    }

    /**
     * 清理旧日志（保留最近N天）
     *
     * @param daysToKeep 保留天数
     * @return 清理的日志数量
     */
    public int cleanup(int daysToKeep) {
        Instant cutoff = Instant.now().minus(daysToKeep, ChronoUnit.DAYS);
        int removed = 0;

        lock.writeLock().lock();
        try {
            Iterator<AuditLog> it = logQueue.iterator();
            while (it.hasNext()) {
                AuditLog log = it.next();
                if (log.getTimestamp().isBefore(cutoff)) {
                    it.remove();
                    removed++;
                } else {
                    break; // 队列是按时间有序的
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (removed > 0) {
            log.info("[AuditLogService] 清理旧日志: {} 条", removed);
        }

        return removed;
    }

    /**
     * 将内存中的日志持久化到MetadataStore
     * <p>
     * 适合在定时任务中调用
     * </p>
     *
     * @return 持久化的日志数量
     */
    public int flushToStore() {
        if (metadataStore == null) return 0;

        List<AuditLog> toFlush;
        lock.readLock().lock();
        try {
            toFlush = new ArrayList<>(logQueue);
        } finally {
            lock.readLock().unlock();
        }

        if (toFlush.isEmpty()) return 0;

        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> logMaps = new ArrayList<>();
            for (AuditLog log : toFlush) {
                Map<String, Object> lm = new HashMap<>();
                lm.put("logId", log.getLogId());
                lm.put("action", log.getAction());
                lm.put("userId", log.getUserId());
                lm.put("agentId", log.getAgentId());
                lm.put("memoryId", log.getMemoryId());
                lm.put("details", log.getDetails());
                lm.put("timestamp", log.getTimestamp() != null ? log.getTimestamp().toString() : null);
                lm.put("sourceIp", log.getSourceIp());
                logMaps.add(lm);
            }
            data.put("logs", logMaps);
            data.put("flushedAt", Instant.now().toString());
            data.put("count", toFlush.size());

            String recordId = "audit_logs_latest";
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", recordId);
            List<com.memoryplatform.model.MetadataRecord> existing =
                    metadataStore.find(LOGS_TABLE, filters, 1, 0);

            if (existing.isEmpty()) {
                com.memoryplatform.model.MetadataRecord record =
                        new com.memoryplatform.model.MetadataRecord();
                record.setId(recordId);
                record.setTable(LOGS_TABLE);
                record.setContent("audit_logs");
                record.setData(data);
                metadataStore.insert(LOGS_TABLE, record);
            } else {
                Map<String, Object> updates = new HashMap<>();
                updates.put("data", data);
                metadataStore.update(LOGS_TABLE, recordId, updates);
            }

            log.info("[AuditLogService] 持久化完成: {} 条日志", toFlush.size());
            return toFlush.size();
        } catch (Exception e) {
            log.error("[AuditLogService] 持久化失败: {}", e.getMessage(), e);
            return 0;
        }
    }
}
