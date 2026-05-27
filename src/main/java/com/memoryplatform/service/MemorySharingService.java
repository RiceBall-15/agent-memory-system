package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多Agent协作记忆共享服务
 * <p>
 * 支持三种共享模式：
 * <ul>
 *   <li>READ_ONLY: 只读共享(其他Agent只能检索)</li>
 *   <li>READ_WRITE: 读写共享(其他Agent可以更新)</li>
 *   <li>FULL: 完全共享(包括删除)</li>
 * </ul>
 * <p>
 * 共享关系存储在MetadataStore的"memory_shares"表中。
 * 共享记忆不参与去重和TTL清理（由共享源Agent管理）。
 *
 * @author MemoryPlatform
 * @since 1.0
 */
public class MemorySharingService {

    /** 共享关系表名 */
    private static final String SHARES_TABLE = "memory_shares";

    /**
     * 共享模式枚举
     */
    public enum ShareMode {
        /** 只读共享 - 其他Agent只能检索 */
        READ_ONLY("READ_ONLY"),
        /** 读写共享 - 其他Agent可以更新 */
        READ_WRITE("READ_WRITE"),
        /** 完全共享 - 包括删除权限 */
        FULL("FULL");

        private final String value;

        ShareMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * 从字符串解析共享模式
         */
        public static ShareMode fromString(String mode) {
            if (mode == null) return READ_ONLY;
            switch (mode.toUpperCase()) {
                case "READ_WRITE": return READ_WRITE;
                case "FULL": return FULL;
                default: return READ_ONLY;
            }
        }

        /**
         * 检查是否允许读操作
         */
        public boolean canRead() {
            return true; // 所有模式都允许读
        }

        /**
         * 检查是否允许写操作
         */
        public boolean canWrite() {
            return this == READ_WRITE || this == FULL;
        }

        /**
         * 检查是否允许删除操作
         */
        public boolean canDelete() {
            return this == FULL;
        }
    }

    /** 存储依赖 */
    private final MetadataStore metadataStore;

    /** 内存缓存: memoryId -> Map<targetAgentId, ShareRecord> */
    private final ConcurrentHashMap<String, Map<String, ShareRecord>> shareCache = new ConcurrentHashMap<>();

    /**
     * 共享记录内部类
     */
    public static class ShareRecord {
        private final String memoryId;
        private final String sourceAgentId;
        private final String targetAgentId;
        private final ShareMode mode;
        private final Instant sharedAt;

        public ShareRecord(String memoryId, String sourceAgentId, String targetAgentId,
                           ShareMode mode, Instant sharedAt) {
            this.memoryId = memoryId;
            this.sourceAgentId = sourceAgentId;
            this.targetAgentId = targetAgentId;
            this.mode = mode;
            this.sharedAt = sharedAt;
        }

        public String getMemoryId() { return memoryId; }
        public String getSourceAgentId() { return sourceAgentId; }
        public String getTargetAgentId() { return targetAgentId; }
        public ShareMode getMode() { return mode; }
        public Instant getSharedAt() { return sharedAt; }
    }

    /**
     * 构造函数
     *
     * @param metadataStore 元数据存储
     */
    public MemorySharingService(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
        System.out.println("[MemorySharing] init: metadataStore="
                + (metadataStore != null && metadataStore.healthCheck()));
    }

    /**
     * 共享记忆给目标Agent
     *
     * @param memoryId      记忆ID
     * @param sourceAgentId 源Agent ID（共享发起者）
     * @param targetAgentId 目标Agent ID（接收者）
     * @param mode          共享模式
     * @return 共享记录，失败返回null
     */
    public ShareRecord shareMemory(String memoryId, String sourceAgentId,
                                   String targetAgentId, ShareMode mode) {
        if (memoryId == null || sourceAgentId == null || targetAgentId == null) {
            System.err.println("[MemorySharing] 共享参数不能为空");
            return null;
        }

        if (sourceAgentId.equals(targetAgentId)) {
            System.err.println("[MemorySharing] 不能共享给自己");
            return null;
        }

        if (mode == null) {
            mode = ShareMode.READ_ONLY;
        }

        try {
            Instant now = Instant.now();
            ShareRecord record = new ShareRecord(memoryId, sourceAgentId, targetAgentId, mode, now);

            // 存储到MetadataStore
            if (metadataStore != null) {
                MetadataRecord metaRecord = new MetadataRecord();
                metaRecord.setId(memoryId + "::" + targetAgentId);
                metaRecord.setContent("memory_share");
                metaRecord.setUserId(sourceAgentId);
                metaRecord.setAgentId(targetAgentId);
                metaRecord.setImportance(0);

                Map<String, Object> data = new HashMap<>();
                data.put("memoryId", memoryId);
                data.put("sourceAgentId", sourceAgentId);
                data.put("targetAgentId", targetAgentId);
                data.put("shareMode", mode.getValue());
                data.put("sharedAt", now.toString());
                metaRecord.setData(data);

                metadataStore.insert(SHARES_TABLE, metaRecord);
            }

            // 更新内存缓存
            shareCache.computeIfAbsent(memoryId, k -> new ConcurrentHashMap<>())
                    .put(targetAgentId, record);

            System.out.printf("[MemorySharing] 共享记忆: memoryId=%s, source=%s, target=%s, mode=%s%n",
                    memoryId, sourceAgentId, targetAgentId, mode.getValue());

            return record;
        } catch (Exception e) {
            System.err.println("[MemorySharing] 共享记忆失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 取消共享
     *
     * @param memoryId      记忆ID
     * @param sourceAgentId 源Agent ID（验证权限）
     * @param targetAgentId 目标Agent ID
     * @return 是否取消成功
     */
    public boolean unshareMemory(String memoryId, String sourceAgentId, String targetAgentId) {
        if (memoryId == null || sourceAgentId == null || targetAgentId == null) {
            return false;
        }

        try {
            // 验证权限：只有源Agent可以取消共享
            Map<String, ShareRecord> shares = shareCache.get(memoryId);
            if (shares != null) {
                ShareRecord existing = shares.get(targetAgentId);
                if (existing != null && !existing.getSourceAgentId().equals(sourceAgentId)) {
                    System.err.println("[MemorySharing] 无权取消共享: source=" + sourceAgentId
                            + ", owner=" + existing.getSourceAgentId());
                    return false;
                }
            }

            // 从MetadataStore删除
            if (metadataStore != null) {
                String shareId = memoryId + "::" + targetAgentId;
                metadataStore.delete(SHARES_TABLE, List.of(shareId));
            }

            // 从内存缓存移除
            if (shares != null) {
                shares.remove(targetAgentId);
                if (shares.isEmpty()) {
                    shareCache.remove(memoryId);
                }
            }

            System.out.printf("[MemorySharing] 取消共享: memoryId=%s, target=%s%n",
                    memoryId, targetAgentId);
            return true;
        } catch (Exception e) {
            System.err.println("[MemorySharing] 取消共享失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取共享给指定Agent的记忆列表
     *
     * @param agentId Agent ID（接收者）
     * @return 共享记忆列表
     */
    public List<ShareRecord> getSharedMemories(String agentId) {
        List<ShareRecord> results = new ArrayList<>();

        if (agentId == null) {
            return results;
        }

        // 从内存缓存查找
        for (Map.Entry<String, Map<String, ShareRecord>> entry : shareCache.entrySet()) {
            ShareRecord record = entry.getValue().get(agentId);
            if (record != null) {
                results.add(record);
            }
        }

        // 如果缓存为空，从MetadataStore加载
        if (results.isEmpty() && metadataStore != null) {
            try {
                Map<String, Object> filters = new HashMap<>();
                filters.put("agentId", agentId);
                List<MetadataRecord> records = metadataStore.find(SHARES_TABLE, filters, 1000, 0);

                for (MetadataRecord record : records) {
                    Map<String, Object> data = record.getData();
                    if (data != null) {
                        String memId = data.get("memoryId") != null ?
                                data.get("memoryId").toString() : record.getId().split("::")[0];
                        String sourceAgent = data.get("sourceAgentId") != null ?
                                data.get("sourceAgentId").toString() : record.getUserId();
                        ShareMode mode = ShareMode.fromString(
                                data.get("shareMode") != null ? data.get("shareMode").toString() : null);
                        Instant sharedAt = data.get("sharedAt") != null ?
                                Instant.parse(data.get("sharedAt").toString()) : record.getCreatedAt();

                        ShareRecord shareRecord = new ShareRecord(memId, sourceAgent, agentId, mode, sharedAt);
                        results.add(shareRecord);

                        // 回填缓存
                        shareCache.computeIfAbsent(memId, k -> new ConcurrentHashMap<>())
                                .put(agentId, shareRecord);
                    }
                }
            } catch (Exception e) {
                System.err.println("[MemorySharing] 查询共享记忆失败: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * 获取Agent共享出去的记忆列表
     *
     * @param agentId Agent ID（共享发起者）
     * @return 共享出去的记忆列表
     */
    public List<ShareRecord> getSharedByMe(String agentId) {
        List<ShareRecord> results = new ArrayList<>();

        if (agentId == null) {
            return results;
        }

        // 从内存缓存查找
        for (Map.Entry<String, Map<String, ShareRecord>> entry : shareCache.entrySet()) {
            for (ShareRecord record : entry.getValue().values()) {
                if (agentId.equals(record.getSourceAgentId())) {
                    results.add(record);
                }
            }
        }

        // 如果缓存为空，从MetadataStore加载
        if (results.isEmpty() && metadataStore != null) {
            try {
                Map<String, Object> filters = new HashMap<>();
                filters.put("userId", agentId); // userId存储sourceAgentId
                List<MetadataRecord> records = metadataStore.find(SHARES_TABLE, filters, 1000, 0);

                for (MetadataRecord record : records) {
                    Map<String, Object> data = record.getData();
                    if (data != null) {
                        String memId = data.get("memoryId") != null ?
                                data.get("memoryId").toString() : record.getId().split("::")[0];
                        String targetAgent = data.get("targetAgentId") != null ?
                                data.get("targetAgentId").toString() : record.getAgentId();
                        ShareMode mode = ShareMode.fromString(
                                data.get("shareMode") != null ? data.get("shareMode").toString() : null);
                        Instant sharedAt = data.get("sharedAt") != null ?
                                Instant.parse(data.get("sharedAt").toString()) : record.getCreatedAt();

                        ShareRecord shareRecord = new ShareRecord(memId, agentId, targetAgent, mode, sharedAt);
                        results.add(shareRecord);

                        // 回填缓存
                        shareCache.computeIfAbsent(memId, k -> new ConcurrentHashMap<>())
                                .put(targetAgent, shareRecord);
                    }
                }
            } catch (Exception e) {
                System.err.println("[MemorySharing] 查询共享出去的记忆失败: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * 检查Agent对指定记忆的操作权限
     *
     * @param memoryId 记忆ID
     * @param agentId  Agent ID
     * @param action   操作类型 ("read", "write", "delete")
     * @return 是否有权限
     */
    public boolean checkPermission(String memoryId, String agentId, String action) {
        if (memoryId == null || agentId == null) {
            return false;
        }

        Map<String, ShareRecord> shares = shareCache.get(memoryId);
        if (shares == null) {
            return false;
        }

        ShareRecord record = shares.get(agentId);
        if (record == null) {
            return false;
        }

        ShareMode mode = record.getMode();
        switch (action.toLowerCase()) {
            case "read":   return mode.canRead();
            case "write":  return mode.canWrite();
            case "delete": return mode.canDelete();
            default:       return false;
        }
    }

    /**
     * 获取记忆的所有共享目标
     *
     * @param memoryId 记忆ID
     * @return 共享目标映射 (agentId -> ShareRecord)
     */
    public Map<String, ShareRecord> getShareTargets(String memoryId) {
        Map<String, ShareRecord> targets = shareCache.get(memoryId);
        return targets != null ? new HashMap<>(targets) : new HashMap<>();
    }

    /**
     * 判断记忆是否为共享记忆（不参与去重和TTL清理）
     *
     * @param memoryId 记忆ID
     * @return 是否为共享记忆
     */
    public boolean isSharedMemory(String memoryId) {
        if (memoryId == null) return false;

        Map<String, ShareRecord> shares = shareCache.get(memoryId);
        if (shares != null && !shares.isEmpty()) {
            return true;
        }

        // 从MetadataStore确认
        if (metadataStore != null) {
            try {
                Map<String, Object> filters = new HashMap<>();
                filters.put("memoryId", memoryId);
                long count = metadataStore.count(SHARES_TABLE, filters);
                return count > 0;
            } catch (Exception e) {
                // 忽略异常
            }
        }

        return false;
    }

    /**
     * 获取共享统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        int totalShares = 0;
        Map<String, Integer> modeDistribution = new HashMap<>();

        for (Map.Entry<String, Map<String, ShareRecord>> entry : shareCache.entrySet()) {
            for (ShareRecord record : entry.getValue().values()) {
                totalShares++;
                String mode = record.getMode().getValue();
                modeDistribution.merge(mode, 1, Integer::sum);
            }
        }

        stats.put("totalShares", totalShares);
        stats.put("sharedMemoryCount", shareCache.size());
        stats.put("modeDistribution", modeDistribution);
        return stats;
    }
}
