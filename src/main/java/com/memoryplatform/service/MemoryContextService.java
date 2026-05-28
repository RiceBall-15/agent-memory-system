package com.memoryplatform.service;

import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryContext;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.storage.MetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆上下文增强服务
 * <p>
 * 根据当前对话检索相关记忆，构建上下文窗口。
 * 使用多维度评分策略：语义相关性 + 时间近因性 + 主题一致性。
 * </p>
 *
 * <h3>评分权重</h3>
 * <ul>
 *   <li>语义相关性: 0.4</li>
 *   <li>时间近因性: 0.3（最近的记忆权重更高）</li>
 *   <li>主题一致性: 0.3（相同主题的记忆权重更高）</li>
 * </ul>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>上下文窗口大小: 默认10条</li>
 *   <li>去重: 同一记忆不重复出现在上下文中</li>
 *   <li>记忆链: 通过引用关系形成记忆链</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryContextService {

    /** 评分权重: 语义相关性 */
    private static final double W_SEMANTIC = 0.4;
    /** 评分权重: 时间近因性 */
    private static final double W_TEMPORAL = 0.3;
    /** 评分权重: 主题一致性 */
    private static final double W_TOPICAL = 0.3;

    /** 最大检索候选数（倍数） */
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final HybridRetrievalService retrievalService;
    private final MetadataStore metadataStore;

    /** 默认上下文窗口大小 */
    @Value("${app.memory.context.window-size:10}")
    private int defaultWindowSize = 10;

    /** 统计: 上下文构建次数 */
    private final AtomicLong contextBuildCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("[MemoryContextService] 初始化完成: defaultWindowSize={}", defaultWindowSize);
    }

    /**
     * 构建记忆上下文
     *
     * @param query      查询文本
     * @param userId     用户ID
     * @param agentId    Agent ID
     * @param windowSize 上下文窗口大小
     * @return 记忆上下文对象
     */
    public MemoryContext buildContext(String query, String userId, String agentId, int windowSize) {
        if (windowSize <= 0) windowSize = defaultWindowSize;

        log.info("[MemoryContextService] 构建上下文: query='{}' userId={} windowSize={}", query, userId, windowSize);

        // 1. 使用混合检索获取候选记忆
        List<SearchResult> candidates = retrieveCandidates(query, userId, agentId, windowSize);

        // 2. 计算综合评分
        Map<String, Double> scoredResults = scoreResults(candidates, query);

        // 3. 排序并截取topK
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scoredResults.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 去重并截取
        Set<String> seen = new HashSet<>();
        List<Memory> contextMemories = new ArrayList<>();
        Map<String, Double> finalScores = new LinkedHashMap<>();
        double totalRelevance = 0.0;

        for (Map.Entry<String, Double> entry : sorted) {
            String memoryId = entry.getKey();
            if (seen.contains(memoryId)) continue;
            seen.add(memoryId);

            Memory memory = loadMemory(memoryId);
            if (memory != null) {
                contextMemories.add(memory);
                finalScores.put(memoryId, entry.getValue());
                totalRelevance += entry.getValue();
            }

            if (contextMemories.size() >= windowSize) break;
        }

        contextBuildCount.incrementAndGet();

        log.info("[MemoryContextService] 上下文构建完成: {} memories, totalRelevance={}",
                contextMemories.size(), String.format("%.3f", totalRelevance));

        return MemoryContext.builder()
            .memories(contextMemories)
            .scores(finalScores)
            .windowSize(windowSize)
            .totalRelevance(totalRelevance)
            .build();
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("contextBuildCount", contextBuildCount.get());
        stats.put("defaultWindowSize", defaultWindowSize);
        stats.put("weights", Map.of(
            "semantic", W_SEMANTIC,
            "temporal", W_TEMPORAL,
            "topical", W_TOPICAL
        ));
        return stats;
    }

    // ==================== 内部方法 ====================

    /**
     * 使用混合检索获取候选记忆
     */
    private List<SearchResult> retrieveCandidates(String query, String userId,
                                                   String agentId, int windowSize) {
        try {
            SearchQuery searchQuery = SearchQuery.builder()
                .text(query)
                .userId(userId)
                .agentId(agentId)
                .topK(windowSize * CANDIDATE_MULTIPLIER)
                .threshold(0.1) // 低阈值，获取更多候选
                .build();

            return retrievalService.search(searchQuery);
        } catch (Exception e) {
            log.error("[MemoryContextService] 混合检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 对候选记忆进行综合评分
     *
     * @param candidates 候选搜索结果
     * @param query      查询文本
     * @return memoryId -> 综合分数
     */
    private Map<String, Double> scoreResults(List<SearchResult> candidates, String query) {
        Map<String, Double> scores = new LinkedHashMap<>();

        // 提取查询关键词（用于主题一致性）
        Set<String> queryTokens = extractTokens(query);
        long now = System.currentTimeMillis();

        for (SearchResult result : candidates) {
            String memoryId = result.getId();

            // 1. 语义相关性（使用混合检索的分数，已包含向量+BM25+实体）
            double semanticScore = result.getScore();

            // 2. 时间近因性
            double temporalScore = computeTemporalScore(result);

            // 3. 主题一致性
            double topicalScore = computeTopicalScore(result, queryTokens);

            // 综合评分
            double finalScore = W_SEMANTIC * semanticScore +
                               W_TEMPORAL * temporalScore +
                               W_TOPICAL * topicalScore;

            scores.put(memoryId, finalScore);
        }

        return scores;
    }

    /**
     * 计算时间近因性分数
     * 最近的记忆分数越高，使用指数衰减
     */
    private double computeTemporalScore(SearchResult result) {
        try {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata == null) return 0.5;

            Object createdAtObj = metadata.get("createdAt");
            if (createdAtObj == null) return 0.5;

            Instant createdAt;
            if (createdAtObj instanceof String) {
                createdAt = Instant.parse((String) createdAtObj);
            } else if (createdAtObj instanceof Instant) {
                createdAt = (Instant) createdAtObj;
            } else {
                return 0.5;
            }

            long ageMs = System.currentTimeMillis() - createdAt.toEpochMilli();
            // 7天衰减半衰期
            double halfLifeMs = 7.0 * 24 * 60 * 60 * 1000;
            return Math.exp(-0.693 * ageMs / halfLifeMs);
        } catch (Exception e) {
            return 0.5;
        }
    }

    /**
     * 计算主题一致性分数
     * 检查记忆文本与查询文本的关键词重叠度
     */
    private double computeTopicalScore(SearchResult result, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) return 0.5;

        String text = result.getText();
        if (text == null || text.isBlank()) return 0.0;

        Set<String> memoryTokens = extractTokens(text);
        if (memoryTokens.isEmpty()) return 0.0;

        // Jaccard相似度
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(memoryTokens);

        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(memoryTokens);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
    }

    /**
     * 简单分词（中英文混合）
     */
    private Set<String> extractTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) return tokens;

        // 英文分词
        String[] words = text.toLowerCase().split("[^a-zA-Z0-9]+");
        for (String w : words) {
            if (w.length() >= 2) tokens.add(w);
        }

        // 中文: 按字符bigram切分
        String cleaned = text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            char c = cleaned.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) {
                String bigram = cleaned.substring(i, i + 2);
                if (bigram.chars().allMatch(ch -> ch >= 0x4e00 && ch <= 0x9fa5)) {
                    tokens.add(bigram);
                }
            }
        }

        return tokens;
    }

    /**
     * 从元数据存储加载Memory对象
     */
    private Memory loadMemory(String memoryId) {
        try {
            Map<String, Object> data = metadataStore.get("memories", memoryId);
            if (data == null) return null;

            String text = data.get("content") != null ? data.get("content").toString() : "";
            String userId = data.get("userId") != null ? data.get("userId").toString() : "";
            String agentId = data.get("agentId") != null ? data.get("agentId").toString() : null;
            double importance = 0.5;
            if (data.get("importance") instanceof Number) {
                importance = ((Number) data.get("importance")).doubleValue();
            }

            Memory.MemoryBuilder builder = Memory.builder()
                .id(memoryId)
                .text(text)
                .userId(userId)
                .importance(importance);

            if (agentId != null) builder.agentId(agentId);

            return builder.build();
        } catch (Exception e) {
            log.error("[MemoryContextService] 加载记忆失败: {} - {}", memoryId, e.getMessage());
            return null;
        }
    }
}
