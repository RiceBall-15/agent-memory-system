package com.memoryplatform.service;

import com.memoryplatform.model.Entity;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.model.VectorRecord;
import com.memoryplatform.scorer.Bm25Scorer;
import com.memoryplatform.scorer.FusionScorer;
import com.memoryplatform.service.MemoryDecayService;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * 混合检索服务 - Mem0 v3风格多信号融合检索
 * <p>
 * 多阶段检索流程：
 * <ol>
 *   <li>向量检索: vectorStore.search() 获取top2K候选</li>
 *   <li>BM25重排: 对候选集计算BM25分数</li>
 *   <li>实体boost: 从图库查实体关联，计算boost分数</li>
 *   <li>融合排序: fusionScorer融合三路分数</li>
 *   <li>过滤返回: 按threshold过滤，返回topK</li>
 * </ol>
 * <p>
 * 特性：
 * <ul>
 *   <li>支持向量库不可用时的降级策略（仅BM25 + 元数据过滤）</li>
 *   <li>支持元数据过滤</li>
 *   <li>响应时间目标: P99 < 200ms</li>
 *   <li>线程安全，支持并发检索</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @since 1.0
 */
public class HybridRetrievalService {

    /** 向量检索候选集倍数: 获取 topK * CANDIDATE_MULTIPLIER 个候选 */
    private static final int CANDIDATE_MULTIPLIER = 2;

    /** 实体boost最大值 */
    private static final double ENTITY_BOOST_MAX = 1.0;

    /** 实体权重衰减因子 - 距离越远权重越低 */
    private static final double ENTITY_DEPTH_DECAY = 0.5;

    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final MetadataStore metadataStore;
    private final EmbeddingService embeddingService;
    private final Bm25Scorer bm25Scorer;
    private final FusionScorer fusionScorer;
    /** 记忆衰减服务（可选） */
    private MemoryDecayService decayService;

    /** 缓存: collection名称 -> VectorRecord列表（用于BM25索引） */
    private final ConcurrentHashMap<String, List<VectorRecord>> documentCache = new ConcurrentHashMap<>();

    /** 集合名默认值 */
    private static final String DEFAULT_COLLECTION = "memories";

    /** documentCache最大条目数 */
    private static final int MAX_CACHE_SIZE = 1000;

    /** documentCache维护插入顺序，用于LRU淘汰 */
    private final LinkedHashMap<String, List<VectorRecord>> cacheInsertionOrder = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

    /** graphStore健康检查缓存（5秒过期） */
    private static final long HEALTH_CHECK_TTL_MS = 5000L;
    private volatile boolean graphStoreHealthy = false;
    private volatile long lastGraphStoreHealthCheck = 0;

    /**
     * 构造混合检索服务
     *
     * @param vectorStore      向量存储（可为null，表示不启用向量检索）
     * @param graphStore       图存储（可为null，表示不启用实体boost）
     * @param metadataStore    元数据存储（可为null，表示不启用元数据过滤）
     * @param embeddingService Embedding服务（可为null，用于查询向量化）
     */
    public HybridRetrievalService(VectorStore vectorStore,
                                   GraphStore graphStore,
                                   MetadataStore metadataStore,
                                   EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.metadataStore = metadataStore;
        this.embeddingService = embeddingService;
        this.bm25Scorer = new Bm25Scorer();
        this.fusionScorer = new FusionScorer();

        System.out.println("[HybridRetrieval] init: vectorStore=" + isAvailable(vectorStore) +
            " graphStore=" + isAvailable(graphStore) +
            " metadataStore=" + isAvailable(metadataStore) +
            " embeddingService=" + isAvailable(embeddingService));
    }

    /**
     * 构造混合检索服务（完整参数版）
     *
     * @param vectorStore      向量存储
     * @param graphStore       图存储
     * @param metadataStore    元数据存储
     * @param embeddingService Embedding服务
     * @param wSemantic        语义权重
     * @param wBm25            BM25权重
     * @param wEntity          实体权重
     */
    public HybridRetrievalService(VectorStore vectorStore,
                                   GraphStore graphStore,
                                   MetadataStore metadataStore,
                                   EmbeddingService embeddingService,
                                   double wSemantic, double wBm25, double wEntity) {
        this(vectorStore, graphStore, metadataStore, embeddingService);
        // 如果提供了自定义权重，在初始化后更新
        if (Math.abs(wSemantic + wBm25 + wEntity - 1.0) > 0.01) {
            System.out.println("[HybridRetrieval] WARNING: custom weights invalid, using defaults");
        } else {
            this.fusionScorer.setWeights(wSemantic, wBm25, wEntity);
        }
    }

    /**
     * 设置衰减服务
     */
    public void setDecayService(MemoryDecayService decayService) {
        this.decayService = decayService;
    }

    /**
     * 混合检索 - 主入口
     *
     * @param query 搜索查询
     * @return 按融合分数降序排列的搜索结果列表
     */
    public List<SearchResult> search(SearchQuery query) {
        long startTime = System.currentTimeMillis();
        System.out.println("[HybridRetrieval] search: text='" + query.getText() +
            "' userId=" + query.getUserId() + " topK=" + query.getTopK() +
            " threshold=" + query.getThreshold());

        try {
            // 1. 确定检索策略
            boolean useVector = isAvailable(vectorStore) && embeddingService != null && embeddingService.isAvailable();
            boolean useGraph = isAvailable(graphStore);

            if (!useVector && !useGraph) {
                System.out.println("[HybridRetrieval] search: DEGRADED - no vector/graph stores, using BM25 only");
            }

            // 2. 向量检索 - 获取top2K候选
            Map<String, VectorRecord> candidates = new LinkedHashMap<>();
            Map<String, Double> semanticScores = new HashMap<>();

            if (useVector) {
                candidates = vectorRetrieve(query, semanticScores);
            }

            // 3. 如果向量检索无结果，尝试从文档缓存获取候选
            if (candidates.isEmpty()) {
                candidates = getCachedCandidates(query);
            }

            if (candidates.isEmpty()) {
                System.out.println("[HybridRetrieval] search: no candidates found, returning empty");
                return Collections.emptyList();
            }

            // 4. BM25重排
            Map<String, Double> bm25Scores = bm25Retrieve(query, candidates);

            // 5. 实体boost
            Map<String, Double> entityBoosts = entityBoost(query, candidates);

            // 6. 融合排序
            List<SearchResult> fused = fuseAndRank(candidates, semanticScores, bm25Scores, entityBoosts, query.getTopK());

            // 7. 阈值过滤
            List<SearchResult> filtered = applyThreshold(fused, query.getThreshold());

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[HybridRetrieval] search: completed in " + elapsed + "ms, " +
                "candidates=" + candidates.size() + " results=" + filtered.size());

            return filtered;

        } catch (Exception e) {
            System.err.println("[HybridRetrieval] search: error - " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * 阶段1: 向量检索
     * <p>
     * 使用EmbeddingService将查询文本向量化，然后在VectorStore中检索top2K候选。
     *
     * @param query          搜索查询
     * @param semanticScores 语义分数输出参数
     * @return 候选文档映射 (ID -> VectorRecord)
     */
    private Map<String, VectorRecord> vectorRetrieve(SearchQuery query, Map<String, Double> semanticScores) {
        Map<String, VectorRecord> candidates = new LinkedHashMap<>();

        try {
            float[] queryVector = embeddingService.embed(query.getText());
            String collection = getCollectionName(query);

            int candidateCount = query.getTopK() * CANDIDATE_MULTIPLIER;
            Map<String, Object> filters = buildFilters(query);

            List<SearchResult> vectorResults = vectorStore.search(collection, queryVector, candidateCount, filters);

            if (vectorResults != null) {
                for (SearchResult result : vectorResults) {
                    if (result.getId() != null) {
                        semanticScores.put(result.getId(), result.getScore());
                        // 构建VectorRecord用于后续BM25
                        VectorRecord record = VectorRecord.builder()
                            .id(result.getId())
                            .text(result.getText())
                            .userId(query.getUserId())
                            .build();
                        candidates.put(result.getId(), record);
                    }
                }
            }

            System.out.println("[HybridRetrieval] vectorRetrieve: found " + candidates.size() + " candidates");

        } catch (Exception e) {
            System.err.println("[HybridRetrieval] vectorRetrieve: failed - " + e.getMessage());
        }

        return candidates;
    }

    /**
     * 从文档缓存获取候选（降级策略）
     *
     * @param query 搜索查询
     * @return 候选文档映射
     */
    private Map<String, VectorRecord> getCachedCandidates(SearchQuery query) {
        Map<String, VectorRecord> candidates = new LinkedHashMap<>();
        String collection = getCollectionName(query);

        List<VectorRecord> cached = documentCache.get(collection);
        if (cached != null) {
            // 应用用户过滤
            for (VectorRecord record : cached) {
                if (record.getUserId() != null && record.getUserId().equals(query.getUserId())) {
                    candidates.put(record.getId(), record);
                }
            }
        }

        System.out.println("[HybridRetrieval] getCachedCandidates: found " + candidates.size() + " from cache");
        return candidates;
    }

    /**
     * 阶段2: BM25重排
     *
     * @param query      搜索查询
     * @param candidates 候选文档映射
     * @return 文档ID到BM25分数的映射
     */
    private Map<String, Double> bm25Retrieve(SearchQuery query, Map<String, VectorRecord> candidates) {
        Map<String, Double> bm25Scores = new HashMap<>();

        try {
            List<VectorRecord> docs = new ArrayList<>(candidates.values());

            // 如果索引为空，先构建索引
            if (bm25Scorer.getDocCount() == 0 && !docs.isEmpty()) {
                bm25Scorer.index(docs);
            }

            bm25Scores = bm25Scorer.scoreQuery(query.getText(), docs);

            System.out.println("[HybridRetrieval] bm25Retrieve: scored " + bm25Scores.size() + " docs");

        } catch (Exception e) {
            System.err.println("[HybridRetrieval] bm25Retrieve: failed - " + e.getMessage());
        }

        return bm25Scores;
    }

    /**
     * 阶段3: 实体boost计算
     * <p>
     * 从图库查询与候选文档关联的实体，计算实体增强分数。
     * boost = Σ (entity.confidence * decay^depth)
     *
     * @param query      搜索查询
     * @param candidates 候选文档映射
     * @return 文档ID到实体boost分数的映射
     */
    private Map<String, Double> entityBoost(SearchQuery query, Map<String, VectorRecord> candidates) {
        Map<String, Double> boosts = new HashMap<>();

        // 使用缓存的健康状态（5秒过期），避免每次检索都调用healthCheck
        if (!isGraphStoreHealthy()) {
            return boosts;
        }

        try {
            // 从查询文本提取实体关键词（简化：使用停用词后的token）
            List<String> queryTokens = Bm25Scorer.tokenize(query.getText());

            for (Map.Entry<String, VectorRecord> entry : candidates.entrySet()) {
                String docId = entry.getKey();
                VectorRecord record = entry.getValue();
                double boost = 0.0;

                // 方式1: 从VectorRecord中提取实体
                if (record.getEntities() != null && !record.getEntities().isEmpty()) {
                    for (Entity entity : record.getEntities()) {
                        // 检查实体名称是否在查询中出现
                        if (queryTokens.contains(entity.getName().toLowerCase())) {
                            boost += entity.getConfidence();
                        }
                    }
                }

                // 方式2: 从图库查询实体关联
                try {
                    List<Map<String, Object>> traversals = graphStore.traverse(
                        docId,
                        Arrays.asList("MENTIONS", "RELATED_TO"),
                        "BOTH",
                        2  // maxDepth = 2
                    );

                    if (traversals != null) {
                        for (Map<String, Object> step : traversals) {
                            Object nameObj = step.get("name");
                            if (nameObj != null) {
                                String entityName = nameObj.toString().toLowerCase();
                                if (queryTokens.contains(entityName)) {
                                    // 深度衰减
                                    Object depthObj = step.get("depth");
                                    int depth = depthObj instanceof Number ? ((Number) depthObj).intValue() : 1;
                                    double decay = Math.pow(ENTITY_DEPTH_DECAY, depth - 1);
                                    boost += decay;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // 图库查询失败，忽略实体boost
                }

                // 将boost归一化到[0,1]
                double normalizedBoost = Math.min(boost, ENTITY_BOOST_MAX);
                boosts.put(docId, normalizedBoost);
            }

            System.out.println("[HybridRetrieval] entityBoost: computed boost for " + boosts.size() + " docs");

        } catch (Exception e) {
            System.err.println("[HybridRetrieval] entityBoost: failed - " + e.getMessage());
        }

        return boosts;
    }

    /**
     * 阶段4: 融合排序
     * <p>
     * 将三路信号通过FusionScorer融合为最终分数，然后排序。
     *
     * @param candidates     候选文档映射
     * @param semanticScores 语义分数映射
     * @param bm25Scores     BM25分数映射
     * @param entityBoosts   实体boost映射
     * @param topK           返回数量
     * @return 排序后的搜索结果列表
     */
    private List<SearchResult> fuseAndRank(Map<String, VectorRecord> candidates,
                                            Map<String, Double> semanticScores,
                                            Map<String, Double> bm25Scores,
                                            Map<String, Double> entityBoosts,
                                            int topK) {
        // 计算BM25最大值（用于归一化）
        double bm25Max = 0.0;
        for (double score : bm25Scores.values()) {
            if (score > bm25Max) bm25Max = score;
        }

        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, VectorRecord> entry : candidates.entrySet()) {
            String docId = entry.getKey();
            VectorRecord record = entry.getValue();

            double semanticScore = semanticScores.getOrDefault(docId, 0.0);
            double bm25Score = bm25Scores.getOrDefault(docId, 0.0);
            double entityBoost = entityBoosts.getOrDefault(docId, 0.0);

            // 融合分数
            double finalScore = fusionScorer.fuse(semanticScore, bm25Score, entityBoost, bm25Max);

            // 应用衰减权重
            if (decayService != null) {
                double decayWeight = decayService.getDecayWeight(docId, record.getMetadata());
                finalScore *= decayWeight;
            }

            results.add(new SearchResult(
                docId,
                record.getText(),
                finalScore,
                semanticScore,
                bm25Score,
                entityBoost,
                record.getMetadata()
            ));
        }

        // 按分数降序排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        // 截取topK
        if (results.size() > topK) {
            results = results.subList(0, topK);
        }

        return results;
    }

    /**
     * 阶段5: 阈值过滤
     *
     * @param results   排序后的结果列表
     * @param threshold 最低分数阈值
     * @return 过滤后的结果列表
     */
    private List<SearchResult> applyThreshold(List<SearchResult> results, double threshold) {
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) {
            if (result.getScore() >= threshold) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    /**
     * 更新BM25索引
     * <p>
     * 在记忆写入时调用，增量更新BM25倒排索引。
     *
     * @param records 新增的向量记录列表
     */
    public void updateIndex(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) return;

        // 更新文档缓存（带LRU淘汰，上限1000条）
        cacheLock.writeLock().lock();
        try {
            for (VectorRecord record : records) {
                String collection = record.getCollection() != null ? record.getCollection() : DEFAULT_COLLECTION;
                documentCache.computeIfAbsent(collection, k -> new ArrayList<>()).add(record);
                cacheInsertionOrder.put(collection, documentCache.get(collection));

                // 淘汰最旧的条目，保持缓存不超过MAX_CACHE_SIZE
                while (documentCache.size() > MAX_CACHE_SIZE) {
                    String oldestKey = cacheInsertionOrder.keySet().iterator().next();
                    cacheInsertionOrder.remove(oldestKey);
                    documentCache.remove(oldestKey);
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }

        // 增量更新BM25索引
        bm25Scorer.index(records);

        System.out.println("[HybridRetrieval] updateIndex: added " + records.size() + " records to index");
    }

    /**
     * 构建元数据过滤条件
     *
     * @param query 搜索查询
     * @return 过滤条件Map
     */
    private Map<String, Object> buildFilters(SearchQuery query) {
        Map<String, Object> filters = new HashMap<>();

        if (query.getUserId() != null) {
            filters.put("userId", query.getUserId());
        }
        if (query.getAgentId() != null) {
            filters.put("agentId", query.getAgentId());
        }
        if (query.getFilters() != null) {
            filters.putAll(query.getFilters());
        }

        return filters;
    }

    /**
     * 获取集合名称
     *
     * @param query 搜索查询
     * @return 集合名称
     */
    private String getCollectionName(SearchQuery query) {
        if (query.getFilters() != null && query.getFilters().containsKey("collection")) {
            return query.getFilters().get("collection").toString();
        }
        return DEFAULT_COLLECTION;
    }

    /**
     * 检查graphStore健康状态（带5秒缓存）
     * 避免每次entityBoost调用都执行healthCheck
     */
    private boolean isGraphStoreHealthy() {
        long now = System.currentTimeMillis();
        if (now - lastGraphStoreHealthCheck < HEALTH_CHECK_TTL_MS) {
            return graphStoreHealthy;
        }
        // 双重检查，仅一个线程执行实际检查
        synchronized (this) {
            if (now - lastGraphStoreHealthCheck < HEALTH_CHECK_TTL_MS) {
                return graphStoreHealthy;
            }
            try {
                graphStoreHealthy = graphStore != null && graphStore.healthCheck();
            } catch (Exception e) {
                graphStoreHealthy = false;
            }
            lastGraphStoreHealthCheck = now;
            return graphStoreHealthy;
        }
    }

    /**
     * 检查存储是否可用
     */
    private static boolean isAvailable(Object store) {
        if (store == null) return false;
        try {
            if (store instanceof VectorStore) return ((VectorStore) store).healthCheck();
            if (store instanceof GraphStore) return ((GraphStore) store).healthCheck();
            if (store instanceof MetadataStore) return ((MetadataStore) store).healthCheck();
            if (store instanceof EmbeddingService) return ((EmbeddingService) store).isAvailable();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 获取服务统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("bm25Stats", bm25Scorer.getStats());
        stats.put("fusionWeights", fusionScorer.getWeights());
        stats.put("weightChangeCount", fusionScorer.getWeightChangeCount());
        stats.put("vectorStoreAvailable", isAvailable(vectorStore));
        stats.put("graphStoreAvailable", isAvailable(graphStore));
        stats.put("metadataStoreAvailable", isAvailable(metadataStore));
        stats.put("cachedCollections", documentCache.keySet());
        return stats;
    }

    /**
     * 获取BM25评分器（用于外部测试或调试）
     *
     * @return BM25评分器实例
     */
    public Bm25Scorer getBm25Scorer() {
        return bm25Scorer;
    }

    /**
     * 获取融合评分器（用于外部权重调整）
     *
     * @return 融合评分器实例
     */
    public FusionScorer getFusionScorer() {
        return fusionScorer;
    }
}
