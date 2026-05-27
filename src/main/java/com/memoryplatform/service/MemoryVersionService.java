package com.memoryplatform.service;

import com.memoryplatform.model.AuditLog;
import com.memoryplatform.model.MemoryVersion;
import com.memoryplatform.storage.MetadataStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆版本管理服务
 * <p>
 * 提供版本控制、版本查询、版本差异比较和版本回滚功能。
 * 每次更新记忆时自动创建新版本，保留最近 {@value #DEFAULT_MAX_VERSIONS} 个版本。
 * </p>
 *
 * <h3>存储策略</h3>
 * <ul>
 *   <li>版本列表存储在MetadataStore的 {@code memory_versions} 表中</li>
 *   <li>每个记忆的版本列表作为一条记录的 data 字段</li>
 *   <li>使用内存缓存加速读取</li>
 * </ul>
 */
public class MemoryVersionService {

    /** 版本存储表名 */
    private static final String VERSIONS_TABLE = "memory_versions";

    /** 默认最大保留版本数 */
    private static final int DEFAULT_MAX_VERSIONS = 50;

    /** 最大保留版本数（可配置） */
    private final int maxVersions;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /** 内存版本缓存: memoryId -> 版本列表 */
    private final ConcurrentHashMap<String, List<MemoryVersion>> versionCache = new ConcurrentHashMap<>();

    /**
     * 创建版本管理服务
     *
     * @param metadataStore 元数据存储
     */
    public MemoryVersionService(MetadataStore metadataStore) {
        this(metadataStore, DEFAULT_MAX_VERSIONS);
    }

    /**
     * 创建版本管理服务
     *
     * @param metadataStore 元数据存储
     * @param maxVersions   最大保留版本数
     */
    public MemoryVersionService(MetadataStore metadataStore, int maxVersions) {
        this.metadataStore = metadataStore;
        this.maxVersions = maxVersions > 0 ? maxVersions : DEFAULT_MAX_VERSIONS;
        System.out.println("[MemoryVersionService] 初始化完成, maxVersions=" + this.maxVersions);
    }

    /**
     * 创建新版本
     * <p>
     * 在以下场景调用：
     * <ul>
     *   <li>记忆创建时（CREATE）</li>
     *   <li>记忆更新时（UPDATE）</li>
     *   <li>记忆删除前（DELETE）</li>
     *   <li>记忆合并时（MERGE）</li>
     * </ul>
     * </p>
     *
     * @param memoryId  记忆ID
     * @param content   当前内容快照
     * @param importance 重要度
     * @param changeType 变更类型
     * @param changedBy  变更人（userId 或 agentId）
     * @return 创建的版本
     */
    public MemoryVersion createVersion(String memoryId, String content,
                                        double importance,
                                        MemoryVersion.ChangeType changeType,
                                        String changedBy) {
        if (memoryId == null || memoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId不能为空");
        }

        // 获取当前版本列表
        List<MemoryVersion> versions = getVersionsInternal(memoryId);

        // 计算新版本号
        int newVersionNumber = versions.isEmpty() ? 1 : versions.get(0).getVersion() + 1;

        // 创建版本ID
        String versionId = memoryId + "_v" + newVersionNumber;

        // 构建版本快照
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("content", content);
        snapshot.put("importance", importance);

        MemoryVersion version = MemoryVersion.builder()
                .versionId(versionId)
                .memoryId(memoryId)
                .version(newVersionNumber)
                .content(content)
                .importance(importance)
                .changeType(changeType)
                .changedBy(changedBy)
                .createdAt(Instant.now())
                .snapshot(snapshot)
                .build();

        // 插入到列表头部（最新版本在前）
        versions.add(0, version);

        // 超过限制则清理旧版本
        if (versions.size() > maxVersions) {
            versions = new ArrayList<>(versions.subList(0, maxVersions));
        }

        // 更新缓存
        versionCache.put(memoryId, versions);

        // 持久化到MetadataStore
        persistVersions(memoryId, versions);

        System.out.println("[MemoryVersionService] 创建版本: " + versionId
                + ", type=" + changeType + ", by=" + changedBy);

        return version;
    }

    /**
     * 获取记忆的所有版本（按版本号降序）
     *
     * @param memoryId 记忆ID
     * @return 版本列表（最新在前）
     */
    public List<MemoryVersion> getVersions(String memoryId) {
        return Collections.unmodifiableList(getVersionsInternal(memoryId));
    }

    /**
     * 获取指定版本
     *
     * @param memoryId 记忆ID
     * @param version  版本号
     * @return 版本对象，不存在返回null
     */
    public MemoryVersion getVersion(String memoryId, int version) {
        List<MemoryVersion> versions = getVersionsInternal(memoryId);
        for (MemoryVersion v : versions) {
            if (v.getVersion() == version) {
                return v;
            }
        }
        return null;
    }

    /**
     * 获取两个版本之间的差异
     *
     * @param memoryId 记忆ID
     * @param v1       版本1号
     * @param v2       版本2号
     * @return 差异映射，包含字段变更详情
     */
    public Map<String, Object> getDiff(String memoryId, int v1, int v2) {
        MemoryVersion ver1 = getVersion(memoryId, v1);
        MemoryVersion ver2 = getVersion(memoryId, v2);

        if (ver1 == null || ver2 == null) {
            return Map.of("error", "版本不存在",
                    "v1_found", ver1 != null,
                    "v2_found", ver2 != null);
        }

        Map<String, Object> diff = new HashMap<>();
        diff.put("memoryId", memoryId);
        diff.put("fromVersion", v1);
        diff.put("toVersion", v2);

        // 内容差异
        String content1 = ver1.getContent();
        String content2 = ver2.getContent();
        if (!Objects.equals(content1, content2)) {
            diff.put("contentChanged", true);
            diff.put("contentBefore", content1);
            diff.put("contentAfter", content2);
        } else {
            diff.put("contentChanged", false);
        }

        // 重要度差异
        double imp1 = ver1.getImportance();
        double imp2 = ver2.getImportance();
        if (Double.compare(imp1, imp2) != 0) {
            diff.put("importanceChanged", true);
            diff.put("importanceBefore", imp1);
            diff.put("importanceAfter", imp2);
        } else {
            diff.put("importanceChanged", false);
        }

        // 时间线
        diff.put("v1CreatedAt", ver1.getCreatedAt());
        diff.put("v2CreatedAt", ver2.getCreatedAt());
        diff.put("v1ChangedBy", ver1.getChangedBy());
        diff.put("v2ChangedBy", ver2.getChangedBy());

        return diff;
    }

    /**
     * 回滚到指定版本
     * <p>
     * 创建一个新的UPDATE版本，其内容为目标版本的内容。
     * 返回回滚后的新版本。
     * </p>
     *
     * @param memoryId  记忆ID
     * @param version   目标版本号
     * @param rollbackBy 回滚操作人
     * @return 回滚后创建的新版本
     */
    public MemoryVersion rollbackTo(String memoryId, int version, String rollbackBy) {
        MemoryVersion target = getVersion(memoryId, version);
        if (target == null) {
            throw new IllegalArgumentException("目标版本不存在: " + memoryId + " v" + version);
        }

        System.out.println("[MemoryVersionService] 回滚: " + memoryId
                + " → v" + version + " (by " + rollbackBy + ")");

        return createVersion(
                memoryId,
                target.getContent(),
                target.getImportance(),
                MemoryVersion.ChangeType.UPDATE,
                rollbackBy
        );
    }

    /**
     * 获取版本统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("maxVersions", maxVersions);
        stats.put("cachedMemoryIds", versionCache.size());

        long totalVersions = versionCache.values().stream()
                .mapToInt(List::size)
                .sum();
        stats.put("totalCachedVersions", totalVersions);

        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 获取版本列表（内部方法，先查缓存再查存储）
     */
    private List<MemoryVersion> getVersionsInternal(String memoryId) {
        // 1. 查缓存
        List<MemoryVersion> cached = versionCache.get(memoryId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        // 2. 从MetadataStore加载
        List<MemoryVersion> loaded = loadVersionsFromStore(memoryId);
        if (!loaded.isEmpty()) {
            versionCache.put(memoryId, loaded);
        }
        return new ArrayList<>(loaded);
    }

    /**
     * 从MetadataStore加载版本列表
     */
    @SuppressWarnings("unchecked")
    private List<MemoryVersion> loadVersionsFromStore(String memoryId) {
        if (metadataStore == null) return new ArrayList<>();

        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", "versions_" + memoryId);
            List<com.memoryplatform.model.MetadataRecord> records =
                    metadataStore.find(VERSIONS_TABLE, filters, 1, 0);

            if (records.isEmpty()) return new ArrayList<>();

            com.memoryplatform.model.MetadataRecord record = records.get(0);
            Map<String, Object> data = record.getData();
            if (data == null || !data.containsKey("versions")) return new ArrayList<>();

            Object versionsObj = data.get("versions");
            if (!(versionsObj instanceof List)) return new ArrayList<>();

            List<Map<String, Object>> versionMaps = (List<Map<String, Object>>) versionsObj;
            List<MemoryVersion> versions = new ArrayList<>();

            for (Map<String, Object> vm : versionMaps) {
                MemoryVersion.ChangeType ct = MemoryVersion.ChangeType.valueOf(
                        vm.getOrDefault("changeType", "UPDATE").toString());

                Instant createdAt = null;
                if (vm.get("createdAt") != null) {
                    try {
                        createdAt = Instant.parse(vm.get("createdAt").toString());
                    } catch (Exception e) {
                        createdAt = Instant.now();
                    }
                }

                List<String> tags = new ArrayList<>();
                if (vm.get("tags") instanceof List) {
                    tags.addAll((List<String>) vm.get("tags"));
                }

                Map<String, Object> snapshot = new HashMap<>();
                if (vm.get("snapshot") instanceof Map) {
                    snapshot.putAll((Map<String, Object>) vm.get("snapshot"));
                }

                MemoryVersion version = MemoryVersion.builder()
                        .versionId(vm.getOrDefault("versionId", "").toString())
                        .memoryId(memoryId)
                        .version(((Number) vm.getOrDefault("version", 0)).intValue())
                        .content(vm.getOrDefault("content", "").toString())
                        .importance(((Number) vm.getOrDefault("importance", 0.5)).doubleValue())
                        .changeType(ct)
                        .changedBy(vm.getOrDefault("changedBy", "").toString())
                        .createdAt(createdAt)
                        .tags(tags)
                        .snapshot(snapshot)
                        .build();

                versions.add(version);
            }

            return versions;
        } catch (Exception e) {
            System.err.println("[MemoryVersionService] 加载版本失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 持久化版本列表到MetadataStore
     */
    private void persistVersions(String memoryId, List<MemoryVersion> versions) {
        if (metadataStore == null) return;

        try {
            // 转换版本列表为Map列表
            List<Map<String, Object>> versionMaps = new ArrayList<>();
            for (MemoryVersion v : versions) {
                Map<String, Object> vm = new HashMap<>();
                vm.put("versionId", v.getVersionId());
                vm.put("version", v.getVersion());
                vm.put("content", v.getContent());
                vm.put("importance", v.getImportance());
                vm.put("changeType", v.getChangeType().name());
                vm.put("changedBy", v.getChangedBy());
                vm.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
                vm.put("tags", v.getTags());
                vm.put("snapshot", v.getSnapshot());
                versionMaps.add(vm);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("versions", versionMaps);
            data.put("lastUpdated", Instant.now().toString());

            String recordId = "versions_" + memoryId;
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", recordId);
            List<com.memoryplatform.model.MetadataRecord> existing =
                    metadataStore.find(VERSIONS_TABLE, filters, 1, 0);

            if (existing.isEmpty()) {
                com.memoryplatform.model.MetadataRecord record =
                        new com.memoryplatform.model.MetadataRecord();
                record.setId(recordId);
                record.setTable(VERSIONS_TABLE);
                record.setContent("versions");
                record.setData(data);
                metadataStore.insert(VERSIONS_TABLE, record);
            } else {
                Map<String, Object> updates = new HashMap<>();
                updates.put("data", data);
                metadataStore.update(VERSIONS_TABLE, recordId, updates);
            }
        } catch (Exception e) {
            System.err.println("[MemoryVersionService] 持久化版本失败: " + e.getMessage());
        }
    }
}
