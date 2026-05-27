package com.memoryplatform.server;

import java.util.*;

/**
 * API版本枚举
 * <p>
 * 定义API的版本号和各版本支持的功能特性。
 * 支持版本检测（从URL路径或Accept头）、版本兼容和版本降级。
 * </p>
 *
 * <h3>版本路线图</h3>
 * <ul>
 *   <li>V1: 基础API - 创建/读取/更新/删除/搜索</li>
 *   <li>V2: 增强API - 去重/TTL/版本管理/审计日志/Webhook（包含V1全部功能）</li>
 * </ul>
 *
 * <h3>版本检测策略</h3>
 * <ol>
 *   <li>URL路径前缀: /api/v1/*, /api/v2/*</li>
 *   <li>Accept头: application/vnd.memoryplatform.v1+json</li>
 *   <li>请求参数: ?api_version=v2</li>
 * </ol>
 *
 * @author MemoryPlatform
 * @version 2.0
 * @since 2.0
 */
public enum ApiVersion {

    /**
     * V1 - 基础API版本
     * <p>
     * 提供核心的CRUD和搜索功能，适合基本的记忆管理场景。
     * </p>
     */
    V1("v1", "1.0.0", 1,
        Set.of(
            "memory.create",
            "memory.read",
            "memory.update",
            "memory.delete",
            "memory.list",
            "memory.search",
            "search.hybrid",
            "search.vector",
            "search.graph",
            "health.check"
        )),

    /**
     * V2 - 增强API版本
     * <p>
     * 在V1基础上增加去重、TTL过期、版本管理、审计日志和Webhook等企业级功能。
     * V2自动包含V1的所有路由和功能。
     * </p>
     */
    V2("v2", "2.0.0", 2,
        Set.of(
            // V1所有功能
            "memory.create",
            "memory.read",
            "memory.update",
            "memory.delete",
            "memory.list",
            "memory.search",
            "search.hybrid",
            "search.vector",
            "search.graph",
            "health.check",
            // V2新增功能
            "memory.deduplication",
            "memory.ttl",
            "memory.versioning",
            "memory.audit_log",
            "memory.webhook",
            "memory.batch",
            "memory.export",
            "memory.import",
            "memory.archive",
            "memory.sharing",
            "memory.context",
            "memory.compression",
            "memory.reindex",
            "analytics.overview",
            "analytics.timeline",
            "analytics.categories",
            "analytics.tags",
            "analytics.agents",
            "analytics.quality",
            "admin.stats",
            "admin.cache",
            "admin.storage_health",
            "admin.maintenance"
        ));

    /** 版本前缀（用于URL路径） */
    private final String prefix;

    /** 语义化版本号 */
    private final String semanticVersion;

    /** 版本序号（用于比较） */
    private final int order;

    /** 该版本支持的功能特性集合 */
    private final Set<String> features;

    /** Accept头中的版本媒体类型 */
    private final String mediaType;

    /** 默认版本（最新稳定版） */
    private static final ApiVersion DEFAULT = V2;

    /** 版本映射缓存 */
    private static final Map<String, ApiVersion> PREFIX_MAP = new HashMap<>();
    private static final Map<String, ApiVersion> VERSION_MAP = new HashMap<>();

    static {
        for (ApiVersion v : values()) {
            PREFIX_MAP.put(v.prefix, v);
            VERSION_MAP.put(v.semanticVersion, v);
        }
    }

    ApiVersion(String prefix, String semanticVersion, int order, Set<String> features) {
        this.prefix = prefix;
        this.semanticVersion = semanticVersion;
        this.order = order;
        this.features = Collections.unmodifiableSet(features);
        this.mediaType = "application/vnd.memoryplatform." + prefix + "+json";
    }

    /**
     * 获取版本前缀（如 "v1", "v2"）
     *
     * @return 版本前缀
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * 获取API路径前缀（如 "/api/v1"）
     *
     * @return API路径前缀
     */
    public String getApiPathPrefix() {
        return "/api/" + prefix;
    }

    /**
     * 获取语义化版本号
     *
     * @return 版本号字符串
     */
    public String getSemanticVersion() {
        return semanticVersion;
    }

    /**
     * 获取版本序号
     *
     * @return 版本序号
     */
    public int getOrder() {
        return order;
    }

    /**
     * 获取该版本支持的功能特性集合
     *
     * @return 不可变的功能特性集合
     */
    public Set<String> getFeatures() {
        return features;
    }

    /**
     * 获取Accept头中的媒体类型
     *
     * @return 媒体类型字符串
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * 检查是否支持指定功能
     *
     * @param feature 功能标识
     * @return true表示支持
     */
    public boolean supportsFeature(String feature) {
        return features.contains(feature);
    }

    /**
     * 检查当前版本是否包含另一个版本的所有功能
     *
     * @param other 另一个版本
     * @return true表示当前版本包含other的所有功能
     */
    public boolean includes(ApiVersion other) {
        return this.order >= other.order;
    }

    /**
     * 获取默认版本（最新稳定版）
     *
     * @return 默认版本枚举
     */
    public static ApiVersion getDefault() {
        return DEFAULT;
    }

    /**
     * 从版本前缀解析版本枚举
     *
     * @param prefix 版本前缀（如 "v1", "v2"）
     * @return 对应的ApiVersion，未找到返回null
     */
    public static ApiVersion fromPrefix(String prefix) {
        if (prefix == null) return null;
        return PREFIX_MAP.get(prefix.toLowerCase());
    }

    /**
     * 从语义化版本号解析版本枚举
     *
     * @param version 版本号（如 "1.0.0", "2.0.0"）
     * @return 对应的ApiVersion，未找到返回null
     */
    public static ApiVersion fromVersion(String version) {
        if (version == null) return null;
        return VERSION_MAP.get(version);
    }

    /**
     * 从URL路径中检测API版本
     * <p>
     * 匹配模式: /api/v{N}/*
     * </p>
     *
     * @param path 请求路径
     * @return 检测到的版本，未检测到返回null
     */
    public static ApiVersion detectFromPath(String path) {
        if (path == null) return null;

        for (ApiVersion v : values()) {
            if (path.startsWith(v.getApiPathPrefix() + "/") ||
                path.equals(v.getApiPathPrefix())) {
                return v;
            }
        }
        return null;
    }

    /**
     * 从Accept头中检测API版本
     * <p>
     * 匹配模式: application/vnd.memoryplatform.v{N}+json
     * </p>
     *
     * @param acceptHeader Accept头的值
     * @return 检测到的版本，未检测到返回null
     */
    public static ApiVersion detectFromAcceptHeader(String acceptHeader) {
        if (acceptHeader == null) return null;

        for (ApiVersion v : values()) {
            if (acceptHeader.contains(v.getMediaType())) {
                return v;
            }
        }
        return null;
    }

    /**
     * 检测请求的API版本（综合检测策略）
     * <p>
     * 检测优先级:
     * <ol>
     *   <li>URL路径前缀</li>
     *   <li>Accept头</li>
     *   <li>查询参数 api_version</li>
     *   <li>默认版本</li>
     * </ol>
     * </p>
     *
     * @param path        请求路径
     * @param acceptHeader Accept头
     * @param queryVersion 查询参数中的版本
     * @return 检测到的版本
     */
    public static ApiVersion detect(String path, String acceptHeader, String queryVersion) {
        // 1. 从URL路径检测（最高优先级）
        ApiVersion version = detectFromPath(path);
        if (version != null) return version;

        // 2. 从Accept头检测
        version = detectFromAcceptHeader(acceptHeader);
        if (version != null) return version;

        // 3. 从查询参数检测
        if (queryVersion != null) {
            version = fromPrefix(queryVersion);
            if (version != null) return version;
            version = fromVersion(queryVersion);
            if (version != null) return version;
        }

        // 4. 返回默认版本
        return DEFAULT;
    }

    /**
     * 获取支持的所有版本
     *
     * @return 版本列表（按序号排序）
     */
    public static List<ApiVersion> getAllVersions() {
        List<ApiVersion> versions = new ArrayList<>(Arrays.asList(values()));
        versions.sort(Comparator.comparingInt(ApiVersion::getOrder));
        return versions;
    }

    /**
     * 获取指定版本之后的所有版本（包含指定版本）
     *
     * @param from 起始版本
     * @return 版本列表
     */
    public static List<ApiVersion> getVersionsFrom(ApiVersion from) {
        List<ApiVersion> result = new ArrayList<>();
        for (ApiVersion v : values()) {
            if (v.order >= from.order) {
                result.add(v);
            }
        }
        result.sort(Comparator.comparingInt(ApiVersion::getOrder));
        return result;
    }

    @Override
    public String toString() {
        return prefix + " (" + semanticVersion + ")";
    }
}
