package com.memoryplatform.handler;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.storage.MetadataStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆分析和洞察处理器 - 提供记忆统计、时间线、分类、标签、Agent统计和质量分析
 * <p>
 * 提供以下API端点:
 * <ul>
 *   <li>{@code GET /api/analytics/overview} - 总览统计</li>
 *   <li>{@code GET /api/analytics/timeline} - 时间线分析</li>
 *   <li>{@code GET /api/analytics/categories} - 分类统计</li>
 *   <li>{@code GET /api/analytics/tags} - 标签云</li>
 *   <li>{@code GET /api/analytics/agents} - Agent统计</li>
 *   <li>{@code GET /api/analytics/quality} - 质量分析</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class AnalyticsHandler implements HttpHandler {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 默认标签云返回数量 */
    private static final int DEFAULT_TAG_LIMIT = 50;

    /** 最大标签云返回数量 */
    private static final int MAX_TAG_LIMIT = 200;

    /** 查询批次大小（用于分批加载大量记录） */
    private static final int BATCH_SIZE = 500;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /**
     * 构造分析处理器
     *
     * @param metadataStore 元数据存储
     */
    public AnalyticsHandler(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
        log("[AnalyticsHandler] 初始化完成");
    }

    /**
     * 处理HTTP请求，根据路径分发到对应分析端点
     *
     * @param exchange   HTTP交换对象
     * @param pathParams 路径参数映射
     * @throws IOException 如果IO操作失败
     */
    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[AnalyticsHandler] 请求: " + method + " " + path);

        try {
            if (!"GET".equals(method)) {
                errorResponse(exchange, 405, "不支持的HTTP方法: " + method + "，仅支持GET");
                return;
            }

            if (metadataStore == null) {
                errorResponse(exchange, 503, "元数据存储未配置");
                return;
            }

            // 根据路径分发到对应处理方法
            if (path.endsWith("/overview")) {
                handleOverview(exchange);
            } else if (path.endsWith("/timeline")) {
                handleTimeline(exchange);
            } else if (path.endsWith("/categories")) {
                handleCategories(exchange);
            } else if (path.endsWith("/tags")) {
                handleTags(exchange);
            } else if (path.endsWith("/agents")) {
                handleAgents(exchange);
            } else if (path.endsWith("/quality")) {
                handleQuality(exchange);
            } else {
                errorResponse(exchange, 404, "未知的分析端点: " + path);
            }
        } catch (Exception e) {
            logError("[AnalyticsHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== GET /api/analytics/overview ====================

    /**
     * 总览统计 - 返回总记忆数、活跃记忆数、归档记忆数、存储使用量、平均权重
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleOverview(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] 总览统计 - 开始");

        Map<String, Object> overview = new LinkedHashMap<>();

        // 1. 总记忆数
        long totalCount = metadataStore.count(METADATA_TABLE, new HashMap<>());
        overview.put("totalMemories", totalCount);

        // 2. 活跃记忆数（未归档，即 data 中没有 archived=true 的）
        // 由于 MetadataStore.count 不支持复杂条件，我们通过 find 全量扫描来统计
        List<MetadataRecord> allRecords = loadAllRecords();
        long activeCount = 0;
        long archivedCount = 0;
        double totalWeight = 0.0;
        long weightCount = 0;
        long storageBytes = 0;

        for (MetadataRecord record : allRecords) {
            // 归档判断
            boolean isArchived = isArchived(record);
            if (isArchived) {
                archivedCount++;
            } else {
                activeCount++;
            }

            // 权重统计
            double importance = record.getImportance();
            totalWeight += importance;
            weightCount++;

            // 存储使用量估算（内容长度 + 元数据JSON大小）
            storageBytes += estimateRecordSize(record);
        }

        overview.put("activeMemories", activeCount);
        overview.put("archivedMemories", archivedCount);

        // 3. 存储使用量
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("bytes", storageBytes);
        storage.put("kilobytes", Math.round(storageBytes / 1024.0 * 100.0) / 100.0);
        storage.put("megabytes", Math.round(storageBytes / (1024.0 * 1024.0) * 100.0) / 100.0);
        overview.put("storageUsage", storage);

        // 4. 平均权重
        double avgWeight = weightCount > 0 ? totalWeight / weightCount : 0.0;
        overview.put("averageWeight", Math.round(avgWeight * 1000.0) / 1000.0);

        // 5. 时间范围
        Instant earliest = null;
        Instant latest = null;
        for (MetadataRecord record : allRecords) {
            if (record.getCreatedAt() != null) {
                if (earliest == null || record.getCreatedAt().isBefore(earliest)) {
                    earliest = record.getCreatedAt();
                }
                if (latest == null || record.getCreatedAt().isAfter(latest)) {
                    latest = record.getCreatedAt();
                }
            }
        }
        if (earliest != null) {
            overview.put("earliestRecord", earliest.toString());
        }
        if (latest != null) {
            overview.put("latestRecord", latest.toString());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", overview);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] 总览统计完成: total=" + totalCount
                + ", active=" + activeCount + ", archived=" + archivedCount);
    }

    // ==================== GET /api/analytics/timeline ====================

    /**
     * 时间线分析 - 按时间聚合的记忆创建/更新/删除数量
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleTimeline(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] 时间线分析 - 开始");

        Map<String, String> queryParams = getQueryParams(exchange);
        String userId = queryParams.get("user_id");
        String agentId = queryParams.get("agent_id");
        String period = queryParams.get("period");
        if (period == null || period.isEmpty()) {
            period = "1d"; // 默认1天
        }

        // 加载记录
        List<MetadataRecord> records = loadFilteredRecords(userId, agentId);

        // 确定时间范围
        Instant now = Instant.now();
        Instant startTime;
        switch (period) {
            case "1h":
                startTime = now.minus(1, ChronoUnit.HOURS);
                break;
            case "1d":
                startTime = now.minus(1, ChronoUnit.DAYS);
                break;
            case "1w":
                startTime = now.minus(7, ChronoUnit.DAYS);
                break;
            case "1m":
                startTime = now.minus(30, ChronoUnit.DAYS);
                break;
            default:
                startTime = now.minus(1, ChronoUnit.DAYS);
                period = "1d";
        }

        // 确定时间桶的粒度
        ChronoUnit bucketUnit;
        long bucketCount;
        switch (period) {
            case "1h":
                bucketUnit = ChronoUnit.MINUTES;
                bucketCount = 60;
                break;
            case "1d":
                bucketUnit = ChronoUnit.HOURS;
                bucketCount = 24;
                break;
            case "1w":
                bucketUnit = ChronoUnit.DAYS;
                bucketCount = 7;
                break;
            case "1m":
                bucketUnit = ChronoUnit.DAYS;
                bucketCount = 30;
                break;
            default:
                bucketUnit = ChronoUnit.HOURS;
                bucketCount = 24;
        }

        // 按时间桶统计创建数量
        Map<String, Long> createdTimeline = new LinkedHashMap<>();
        Map<String, Long> updatedTimeline = new LinkedHashMap<>();
        for (long i = 0; i < bucketCount; i++) {
            Instant bucketStart = startTime.plus(i, bucketUnit);
            Instant bucketEnd = bucketStart.plus(1, bucketUnit);
            String bucketKey = bucketStart.toString();
            createdTimeline.put(bucketKey, 0L);
            updatedTimeline.put(bucketKey, 0L);
        }

        // 统计
        long createCount = 0;
        long updateCount = 0;
        for (MetadataRecord record : records) {
            if (record.getCreatedAt() != null && !record.getCreatedAt().isBefore(startTime)) {
                createCount++;
                String bucket = findBucket(record.getCreatedAt(), startTime, bucketUnit, bucketCount);
                if (bucket != null) {
                    createdTimeline.merge(bucket, 1L, Long::sum);
                }
            }
            if (record.getUpdatedAt() != null && !record.getUpdatedAt().isBefore(startTime)
                    && record.getCreatedAt() != null && record.getUpdatedAt().isAfter(record.getCreatedAt())) {
                updateCount++;
                String bucket = findBucket(record.getUpdatedAt(), startTime, bucketUnit, bucketCount);
                if (bucket != null) {
                    updatedTimeline.merge(bucket, 1L, Long::sum);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period);
        data.put("startTime", startTime.toString());
        data.put("endTime", now.toString());
        data.put("totalCreated", createCount);
        data.put("totalUpdated", updateCount);
        data.put("createdTimeline", createdTimeline);
        data.put("updatedTimeline", updatedTimeline);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] 时间线分析完成: period=" + period
                + ", created=" + createCount + ", updated=" + updateCount);
    }

    // ==================== GET /api/analytics/categories ====================

    /**
     * 分类统计 - 各分类的记忆数量
     * <p>
     * 分类基于记忆内容中的 data 字段:
     * <ul>
     *   <li>Factual - 事实性记忆（包含 entity 相关数据）</li>
     *   <li>Procedural - 程序性记忆（包含步骤/流程相关数据）</li>
     *   <li>Episodic - 情节性记忆（包含时间/事件相关数据）</li>
     *   <li>Semantic - 语义性记忆（包含概念/知识相关数据）</li>
     * </ul>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleCategories(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] 分类统计 - 开始");

        Map<String, String> queryParams = getQueryParams(exchange);
        String userId = queryParams.get("user_id");
        String agentId = queryParams.get("agent_id");

        List<MetadataRecord> records = loadFilteredRecords(userId, agentId);

        // 按分类统计
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("Factual", 0L);
        categoryCounts.put("Procedural", 0L);
        categoryCounts.put("Episodic", 0L);
        categoryCounts.put("Semantic", 0L);
        categoryCounts.put("Unclassified", 0L);

        for (MetadataRecord record : records) {
            String category = classifyMemory(record);
            categoryCounts.merge(category, 1L, Long::sum);
        }

        // 移除0计数的分类
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : categoryCounts.entrySet()) {
            if (entry.getValue() > 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMemories", records.size());
        data.put("categories", result);

        // 计算百分比
        Map<String, Double> percentages = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : result.entrySet()) {
            double pct = records.isEmpty() ? 0.0
                    : Math.round((double) entry.getValue() / records.size() * 10000.0) / 100.0;
            percentages.put(entry.getKey(), pct);
        }
        data.put("percentages", percentages);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] 分类统计完成: total=" + records.size());
    }

    // ==================== GET /api/analytics/tags ====================

    /**
     * 标签云 - 返回标签列表(名称+权重，按权重降序)
     * <p>
     * 标签从记忆内容的 data 字段中的 tags 数组提取，同时也会从内容文本中提取关键词作为标签。
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleTags(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] 标签云 - 开始");

        Map<String, String> queryParams = getQueryParams(exchange);
        String userId = queryParams.get("user_id");
        String agentId = queryParams.get("agent_id");
        int limit = DEFAULT_TAG_LIMIT;
        try {
            String limitStr = queryParams.get("limit");
            if (limitStr != null && !limitStr.isEmpty()) {
                limit = Math.min(Math.max(Integer.parseInt(limitStr), 1), MAX_TAG_LIMIT);
            }
        } catch (NumberFormatException e) {
            // 使用默认值
        }

        List<MetadataRecord> records = loadFilteredRecords(userId, agentId);

        // 收集所有标签及其权重
        Map<String, Double> tagWeights = new ConcurrentHashMap<>();

        for (MetadataRecord record : records) {
            // 1. 从 data.tags 中提取标签
            if (record.getData() != null) {
                Object tagsObj = record.getData().get("tags");
                if (tagsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) tagsObj;
                    for (String tag : tags) {
                        if (tag != null && !tag.isBlank()) {
                            tagWeights.merge(tag.trim().toLowerCase(),
                                    record.getImportance(), Double::sum);
                        }
                    }
                }

                // 2. 从 data.category 提取分类作为标签
                Object categoryObj = record.getData().get("category");
                if (categoryObj instanceof String) {
                    String cat = (String) categoryObj;
                    if (!cat.isBlank()) {
                        tagWeights.merge("category:" + cat.trim().toLowerCase(),
                                record.getImportance() * 0.5, Double::sum);
                    }
                }

                // 3. 从 data.entities 提取实体作为标签
                Object entitiesObj = record.getData().get("entities");
                if (entitiesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> entities = (List<Map<String, Object>>) entitiesObj;
                    for (Map<String, Object> entity : entities) {
                        Object nameObj = entity.get("name");
                        if (nameObj instanceof String) {
                            String name = ((String) nameObj).trim().toLowerCase();
                            if (!name.isEmpty()) {
                                tagWeights.merge("entity:" + name,
                                        record.getImportance() * 0.8, Double::sum);
                            }
                        }
                    }
                }
            }
        }

        // 按权重降序排列
        List<Map<String, Object>> tagList = new ArrayList<>();
        tagWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> {
                    Map<String, Object> tagInfo = new LinkedHashMap<>();
                    tagInfo.put("name", entry.getKey());
                    tagInfo.put("weight", Math.round(entry.getValue() * 1000.0) / 1000.0);
                    tagList.add(tagInfo);
                });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalTags", tagWeights.size());
        data.put("tags", tagList);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] 标签云完成: totalTags=" + tagWeights.size()
                + ", returned=" + tagList.size());
    }

    // ==================== GET /api/analytics/agents ====================

    /**
     * Agent统计 - 各Agent的记忆数量和最近活跃时间
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleAgents(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] Agent统计 - 开始");

        List<MetadataRecord> allRecords = loadAllRecords();

        // 按AgentId分组统计
        Map<String, AgentStats> agentStatsMap = new LinkedHashMap<>();

        for (MetadataRecord record : allRecords) {
            String agentId = record.getAgentId();
            if (agentId == null || agentId.isBlank()) {
                agentId = "(no-agent)";
            }

            AgentStats stats = agentStatsMap.computeIfAbsent(agentId, k -> new AgentStats());
            stats.count++;

            // 权重累计
            stats.totalWeight += record.getImportance();

            // 追踪最新活跃时间
            Instant recordTime = record.getUpdatedAt() != null ? record.getUpdatedAt() : record.getCreatedAt();
            if (recordTime != null) {
                if (stats.lastActiveTime == null || recordTime.isAfter(stats.lastActiveTime)) {
                    stats.lastActiveTime = recordTime;
                }
                if (stats.firstSeenTime == null || recordTime.isBefore(stats.firstSeenTime)) {
                    stats.firstSeenTime = recordTime;
                }
            }

            // 收集关联的用户
            if (record.getUserId() != null) {
                stats.userIds.add(record.getUserId());
            }
        }

        // 构建响应
        List<Map<String, Object>> agentList = new ArrayList<>();
        for (Map.Entry<String, AgentStats> entry : agentStatsMap.entrySet()) {
            AgentStats stats = entry.getValue();
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("agentId", entry.getKey());
            agentInfo.put("memoryCount", stats.count);
            agentInfo.put("averageWeight", stats.count > 0
                    ? Math.round(stats.totalWeight / stats.count * 1000.0) / 1000.0 : 0.0);
            agentInfo.put("lastActiveTime", stats.lastActiveTime != null ? stats.lastActiveTime.toString() : null);
            agentInfo.put("firstSeenTime", stats.firstSeenTime != null ? stats.firstSeenTime.toString() : null);
            agentInfo.put("userCount", stats.userIds.size());
            agentList.add(agentInfo);
        }

        // 按记忆数量降序排序
        agentList.sort((a, b) -> Long.compare(
                (long) b.get("memoryCount"), (long) a.get("memoryCount")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalAgents", agentStatsMap.size());
        data.put("totalMemories", allRecords.size());
        data.put("agents", agentList);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] Agent统计完成: agents=" + agentStatsMap.size());
    }

    // ==================== GET /api/analytics/quality ====================

    /**
     * 质量分析 - 平均衰减权重、高权重记忆占比、重复记忆占比
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleQuality(HttpExchange exchange) throws IOException {
        log("[AnalyticsHandler] 质量分析 - 开始");

        List<MetadataRecord> allRecords = loadAllRecords();
        long total = allRecords.size();

        if (total == 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMemories", 0);
            data.put("averageDecayWeight", 0.0);
            data.put("highWeightRatio", 0.0);
            data.put("duplicateRatio", 0.0);
            data.put("freshnessScore", 0.0);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("data", data);
            jsonResponse(exchange, 200, response);
            return;
        }

        double totalDecayWeight = 0.0;
        long highWeightCount = 0;
        double highWeightThreshold = 0.7;
        int duplicateCount = 0;
        long freshCount = 0;
        Instant now = Instant.now();

        // 用于检测重复：content的hash
        Set<String> contentHashes = new HashSet<>();
        Map<String, Integer> contentFrequency = new HashMap<>();

        for (MetadataRecord record : allRecords) {
            // 衰减权重（使用 importance 作为衰减后的权重近似）
            double weight = record.getImportance();
            totalDecayWeight += weight;

            // 高权重记忆
            if (weight >= highWeightThreshold) {
                highWeightCount++;
            }

            // 内容去重检测
            String content = record.getContent();
            if (content != null && !content.isBlank()) {
                String hash = content.trim().toLowerCase().hashCode() + "";
                contentFrequency.merge(hash, 1, Integer::sum);
                if (contentFrequency.get(hash) > 1) {
                    duplicateCount++;
                }
            }

            // 新鲜度检测（7天内有更新的记忆视为新鲜）
            if (record.getUpdatedAt() != null) {
                long daysSinceUpdate = ChronoUnit.DAYS.between(record.getUpdatedAt(), now);
                if (daysSinceUpdate <= 7) {
                    freshCount++;
                }
            } else if (record.getCreatedAt() != null) {
                long daysSinceCreate = ChronoUnit.DAYS.between(record.getCreatedAt(), now);
                if (daysSinceCreate <= 7) {
                    freshCount++;
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMemories", total);

        // 平均衰减权重
        data.put("averageDecayWeight", Math.round(totalDecayWeight / total * 1000.0) / 1000.0);

        // 高权重记忆占比
        double highWeightRatio = Math.round((double) highWeightCount / total * 10000.0) / 100.0;
        data.put("highWeightCount", highWeightCount);
        data.put("highWeightRatio", highWeightRatio);
        data.put("highWeightThreshold", highWeightThreshold);

        // 重复记忆占比（使用出现次数>1的内容hash来估算重复记忆数）
        long actualDuplicateCount = 0;
        for (Map.Entry<String, Integer> entry : contentFrequency.entrySet()) {
            if (entry.getValue() > 1) {
                actualDuplicateCount += entry.getValue() - 1; // 第2个及之后的都算重复
            }
        }
        double duplicateRatio = Math.round((double) actualDuplicateCount / total * 10000.0) / 100.0;
        data.put("duplicateCount", actualDuplicateCount);
        data.put("duplicateRatio", duplicateRatio);

        // 新鲜度分数
        double freshnessScore = Math.round((double) freshCount / total * 10000.0) / 100.0;
        data.put("freshCount", freshCount);
        data.put("freshnessScore", freshnessScore);
        data.put("freshnessPeriodDays", 7);

        // 质量评级
        String qualityGrade;
        double qualityScore = (1 - duplicateRatio / 100.0) * 0.4
                + highWeightRatio / 100.0 * 0.3
                + freshnessScore / 100.0 * 0.3;
        qualityScore = Math.round(qualityScore * 100.0) / 100.0;
        if (qualityScore >= 0.8) qualityGrade = "Excellent";
        else if (qualityScore >= 0.6) qualityGrade = "Good";
        else if (qualityScore >= 0.4) qualityGrade = "Fair";
        else qualityGrade = "Poor";

        data.put("qualityScore", qualityScore);
        data.put("qualityGrade", qualityGrade);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);

        jsonResponse(exchange, 200, response);
        log("[AnalyticsHandler] 质量分析完成: total=" + total
                + ", avgWeight=" + (totalDecayWeight / total)
                + ", highWeight=" + highWeightRatio + "%, duplicate=" + duplicateRatio + "%");
    }

    // ==================== 辅助方法 ====================

    /**
     * 加载所有记录（分批加载，避免内存溢出）
     *
     * @return 所有记忆记录列表
     */
    private List<MetadataRecord> loadAllRecords() {
        List<MetadataRecord> allRecords = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<MetadataRecord> batch = metadataStore.find(METADATA_TABLE, new HashMap<>(), BATCH_SIZE, offset);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            allRecords.addAll(batch);
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            offset += BATCH_SIZE;
        }
        return allRecords;
    }

    /**
     * 加载过滤后的记录（支持按userId和agentId过滤）
     *
     * @param userId  用户ID（可为null）
     * @param agentId AgentID（可为null）
     * @return 过滤后的记录列表
     */
    private List<MetadataRecord> loadFilteredRecords(String userId, String agentId) {
        Map<String, Object> filters = new HashMap<>();
        if (userId != null && !userId.isBlank()) {
            filters.put("userId", userId);
        }
        if (agentId != null && !agentId.isBlank()) {
            filters.put("agentId", agentId);
        }

        if (filters.isEmpty()) {
            return loadAllRecords();
        }

        List<MetadataRecord> records = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<MetadataRecord> batch = metadataStore.find(METADATA_TABLE, filters, BATCH_SIZE, offset);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            records.addAll(batch);
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            offset += BATCH_SIZE;
        }
        return records;
    }

    /**
     * 判断记录是否已归档
     *
     * @param record 元数据记录
     * @return 是否已归档
     */
    private boolean isArchived(MetadataRecord record) {
        if (record.getData() == null) return false;
        Object archived = record.getData().get("archived");
        return Boolean.TRUE.equals(archived);
    }

    /**
     * 对记忆进行分类
     * <p>
     * 基于 data 字段的特征进行分类:
     * <ul>
     *   <li>Factual - 包含 entity 或 name/date 相关数据</li>
     *   <li>Procedural - 包含 steps/stage 相关数据</li>
     *   <li>Episodic - 包含 event/location 相关数据</li>
     *   <li>Semantic - 包含 concept/definition 相关数据</li>
     * </ul>
     * </p>
     *
     * @param record 元数据记录
     * @return 分类名称
     */
    private String classifyMemory(MetadataRecord record) {
        if (record.getData() == null) return "Unclassified";

        Map<String, Object> data = record.getData();

        // 检查显式分类
        Object categoryObj = data.get("category");
        if (categoryObj instanceof String) {
            String cat = ((String) categoryObj).trim();
            switch (cat.toLowerCase()) {
                case "factual":
                case "procedural":
                case "episodic":
                case "semantic":
                    return cat.substring(0, 1).toUpperCase() + cat.substring(1).toLowerCase();
            }
        }

        // 基于特征的自动分类
        if (data.containsKey("entities") || data.containsKey("name")
                || data.containsKey("date") || data.containsKey("person")) {
            return "Factual";
        }
        if (data.containsKey("steps") || data.containsKey("stage")
                || data.containsKey("procedure") || data.containsKey("workflow")) {
            return "Procedural";
        }
        if (data.containsKey("event") || data.containsKey("location")
                || data.containsKey("time") || data.containsKey("scene")) {
            return "Episodic";
        }
        if (data.containsKey("concept") || data.containsKey("definition")
                || data.containsKey("relationship") || data.containsKey("knowledge")) {
            return "Semantic";
        }

        // 基于内容文本的启发式分类
        String content = record.getContent();
        if (content != null) {
            String lower = content.toLowerCase();
            if (lower.contains("步骤") || lower.contains("流程") || lower.contains("方法")
                    || lower.contains("step") || lower.contains("procedure")) {
                return "Procedural";
            }
            if (lower.contains("事件") || lower.contains("经历") || lower.contains("发生在")
                    || lower.contains("event") || lower.contains("happened")) {
                return "Episodic";
            }
            if (lower.contains("概念") || lower.contains("定义") || lower.contains("含义")
                    || lower.contains("concept") || lower.contains("definition")) {
                return "Semantic";
            }
            if (lower.contains("是") || lower.contains("名为") || lower.contains("位于")
                    || lower.contains("is called") || lower.contains("located")) {
                return "Factual";
            }
        }

        return "Unclassified";
    }

    /**
     * 估算记录的存储大小（字节）
     *
     * @param record 元数据记录
     * @return 估算的字节数
     */
    private long estimateRecordSize(MetadataRecord record) {
        long size = 0;
        if (record.getId() != null) size += record.getId().length() * 2;
        if (record.getContent() != null) size += record.getContent().length() * 2;
        if (record.getUserId() != null) size += record.getUserId().length() * 2;
        if (record.getAgentId() != null) size += record.getAgentId().length() * 2;
        if (record.getData() != null) {
            // 粗略估算 data map 的大小
            for (Map.Entry<String, Object> entry : record.getData().entrySet()) {
                size += entry.getKey().length() * 2;
                if (entry.getValue() != null) {
                    size += entry.getValue().toString().length() * 2;
                }
            }
        }
        // 固定开销（Instant等）
        size += 64;
        return size;
    }

    /**
     * 查找时间桶
     *
     * @param time       时间点
     * @param startTime  起始时间
     * @param unit       时间单位
     * @param bucketCount 桶数量
     * @return 时间桶的key，如果不在范围内则返回null
     */
    private String findBucket(Instant time, Instant startTime, ChronoUnit unit, long bucketCount) {
        long offset = unit.between(startTime, time);
        if (offset < 0 || offset >= bucketCount) return null;
        return startTime.plus(offset, unit).toString();
    }

    /**
     * Agent统计内部类
     */
    private static class AgentStats {
        long count = 0;
        double totalWeight = 0.0;
        Instant lastActiveTime = null;
        Instant firstSeenTime = null;
        Set<String> userIds = new HashSet<>();
    }
}
