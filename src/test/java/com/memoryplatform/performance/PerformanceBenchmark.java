package com.memoryplatform.performance;

import com.memoryplatform.model.*;
import com.memoryplatform.scorer.Bm25Scorer;
import com.memoryplatform.scorer.FusionScorer;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.EmbeddingService;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能基准测试 - 单线程写入延迟、多线程并发吞吐量、BM25评分性能、融合评分性能
 * <p>
 * 使用System.nanoTime()手动计时，输出简易性能报告。
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmark {

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = EmbeddingService.noOp();
    }

    // ==================== 基准测试1: 单线程写入延迟 ====================

    @Test
    @Order(1)
    @DisplayName("单线程写入延迟 - 100次顺序写入")
    void testSingleThreadWriteLatency() throws Exception {
        StubVectorStore vectorStore = new StubVectorStore();
        StubGraphStore graphStore = new StubGraphStore();
        StubMetadataStore metadataStore = new StubMetadataStore();

        ConcurrentWriteService service = ConcurrentWriteService.builder()
                .vectorStore(vectorStore)
                .graphStore(graphStore)
                .metadataStore(metadataStore)
                .embeddingService(embeddingService)
                .shardCount(4)
                .batchWindowMs(50)
                .maxRetries(1)
                .circuitFailureThreshold(10)
                .circuitRecoveryTimeoutMs(60000)
                .circuitSuccessThreshold(3)
                .build();

        int iterations = 100;
        long[] latencies = new long[iterations];

        try {
            // 预热
            for (int i = 0; i < 10; i++) {
                Memory mem = createMemory("warmup-" + i, "bench-user", "预热数据" + i);
                service.write(mem).get(5, TimeUnit.SECONDS);
            }

            // 正式测试
            for (int i = 0; i < iterations; i++) {
                Memory mem = createMemory("bench-" + i, "bench-user", "基准测试数据" + i);
                long start = System.nanoTime();
                service.write(mem).get(5, TimeUnit.SECONDS);
                latencies[i] = System.nanoTime() - start;
            }

            // 统计结果
            Arrays.sort(latencies);
            long totalNs = Arrays.stream(latencies).sum();
            double avgMs = totalNs / (double) iterations / 1_000_000.0;
            double p50Ms = latencies[iterations / 2] / 1_000_000.0;
            double p95Ms = latencies[(int) (iterations * 0.95)] / 1_000_000.0;
            double p99Ms = latencies[(int) (iterations * 0.99)] / 1_000_000.0;
            double minMs = latencies[0] / 1_000_000.0;
            double maxMs = latencies[iterations - 1] / 1_000_000.0;

            System.out.println("\n========== 单线程写入延迟基准 ==========");
            System.out.printf("  迭代次数:  %d%n", iterations);
            System.out.printf("  平均延迟:  %.3f ms%n", avgMs);
            System.out.printf("  P50延迟:   %.3f ms%n", p50Ms);
            System.out.printf("  P95延迟:   %.3f ms%n", p95Ms);
            System.out.printf("  P99延迟:   %.3f ms%n", p99Ms);
            System.out.printf("  最小延迟:  %.3f ms%n", minMs);
            System.out.printf("  最大延迟:  %.3f ms%n", maxMs);
            System.out.printf("  吞吐量:    %.1f ops/s%n", iterations / (totalNs / 1_000_000_000.0));
            System.out.println("=========================================\n");

            // 断言：平均延迟应在合理范围内
            assertTrue(avgMs < 500, "单线程平均写入延迟应 < 500ms，实际: " + avgMs + "ms");
            assertTrue(p99Ms < 1000, "P99延迟应 < 1000ms，实际: " + p99Ms + "ms");

        } finally {
            service.shutdown(3000);
        }
    }

    // ==================== 基准测试2: 多线程并发写入吞吐量 ====================

    @Test
    @Order(2)
    @DisplayName("多线程并发写入吞吐量 - 10线程并发")
    void testConcurrentWriteThroughput() throws Exception {
        StubVectorStore vectorStore = new StubVectorStore();
        StubGraphStore graphStore = new StubGraphStore();
        StubMetadataStore metadataStore = new StubMetadataStore();

        ConcurrentWriteService service = ConcurrentWriteService.builder()
                .vectorStore(vectorStore)
                .graphStore(graphStore)
                .metadataStore(metadataStore)
                .embeddingService(embeddingService)
                .shardCount(8)
                .batchWindowMs(50)
                .maxRetries(1)
                .circuitFailureThreshold(10)
                .circuitRecoveryTimeoutMs(60000)
                .circuitSuccessThreshold(3)
                .build();

        int threadCount = 10;
        int writesPerThread = 50;
        int totalWrites = threadCount * writesPerThread;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            // 预热
            for (int i = 0; i < 10; i++) {
                Memory mem = createMemory("warmup-" + i, "warmup-user", "预热" + i);
                service.write(mem).get(5, TimeUnit.SECONDS);
            }

            // 正式测试
            long startNs = System.nanoTime();
            List<CompletableFuture<Void>> tasks = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < writesPerThread; i++) {
                        try {
                            String userId = "user-" + (threadId % 5); // 5个不同用户分片
                            Memory mem = createMemory(
                                    "concurrent-" + threadId + "-" + i,
                                    userId,
                                    "并发测试 线程" + threadId + " 记录" + i);
                            WriteResult result = service.write(mem).get(5, TimeUnit.SECONDS);
                            if (result.isSuccess()) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                        }
                    }
                }, executor);
                tasks.add(task);
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
            long totalNs = System.nanoTime() - startNs;

            double totalSec = totalNs / 1_000_000_000.0;
            double throughput = successCount.get() / totalSec;

            System.out.println("\n========== 多线程并发写入吞吐量基准 ==========");
            System.out.printf("  线程数:      %d%n", threadCount);
            System.out.printf("  每线程写入:  %d%n", writesPerThread);
            System.out.printf("  总写入:      %d%n", totalWrites);
            System.out.printf("  成功:        %d%n", successCount.get());
            System.out.printf("  失败:        %d%n", failCount.get());
            System.out.printf("  总耗时:      %.3f s%n", totalSec);
            System.out.printf("  吞吐量:      %.1f ops/s%n", throughput);
            System.out.printf("  平均延迟:    %.3f ms%n", (totalNs / (double) successCount.get()) / 1_000_000.0);
            System.out.println("=============================================\n");

            // 断言
            assertEquals(totalWrites, successCount.get(),
                    "所有并发写入应成功，失败数: " + failCount.get());
            assertTrue(throughput > 10, "并发吞吐量应 > 10 ops/s，实际: " + throughput);

        } finally {
            service.shutdown(5000);
            executor.shutdownNow();
        }
    }

    // ==================== 基准测试3: BM25评分性能 ====================

    @Test
    @Order(3)
    @DisplayName("BM25索引+查询性能 - 1000文档")
    void testBm25Performance() {
        int docCount = 1000;
        int queryCount = 100;

        // 生成测试文档
        List<VectorRecord> documents = generateTestDocuments(docCount);
        Bm25Scorer scorer = new Bm25Scorer();

        // 索引构建性能
        long indexStart = System.nanoTime();
        scorer.index(documents);
        long indexNs = System.nanoTime() - indexStart;

        // 查询性能
        String[] queries = {
                "机器学习深度学习模型",
                "Python数据分析可视化",
                "Java微服务架构设计",
                "分布式系统一致性算法",
                "云计算容器化部署",
                "网络安全加密算法",
                "数据库索引优化",
                "前端框架响应式设计",
                "人工智能自然语言处理",
                "区块链智能合约"
        };

        long queryTotalNs = 0;
        long[] queryLatencies = new long[queryCount];

        for (int i = 0; i < queryCount; i++) {
            String query = queries[i % queries.length];
            long qStart = System.nanoTime();
            Map<String, Double> scores = scorer.scoreQuery(query, documents);
            long qNs = System.nanoTime() - qStart;
            queryLatencies[i] = qNs;
            queryTotalNs += qNs;

            assertFalse(scores.isEmpty(), "查询应返回结果: " + query);
        }

        // 统计
        Arrays.sort(queryLatencies);
        double indexMs = indexNs / 1_000_000.0;
        double avgQueryUs = (queryTotalNs / (double) queryCount) / 1_000.0;
        double p50QueryUs = queryLatencies[queryCount / 2] / 1_000.0;
        double p95QueryUs = queryLatencies[(int) (queryCount * 0.95)] / 1_000.0;
        double maxQueryUs = queryLatencies[queryCount - 1] / 1_000.0;

        Map<String, Object> stats = scorer.getStats();

        System.out.println("\n========== BM25性能基准 ==========");
        System.out.printf("  文档数:      %d%n", docCount);
        System.out.printf("  索引耗时:    %.3f ms%n", indexMs);
        System.out.printf("  索引词项数:  %d%n", (int) stats.get("totalTerms"));
        System.out.printf("  平均文档长:  %.1f tokens%n", (double) stats.get("avgDocLength"));
        System.out.printf("  查询次数:    %d%n", queryCount);
        System.out.printf("  平均查询耗时: %.1f μs%n", avgQueryUs);
        System.out.printf("  P50查询耗时:  %.1f μs%n", p50QueryUs);
        System.out.printf("  P95查询耗时:  %.1f μs%n", p95QueryUs);
        System.out.printf("  最大查询耗时: %.1f μs%n", maxQueryUs);
        System.out.printf("  查询吞吐量:  %.0f queries/s%n", queryCount / (queryTotalNs / 1e9));
        System.out.println("===================================\n");

        // 断言
        assertTrue(indexMs < 5000, "1000文档索引应 < 5s，实际: " + indexMs + "ms");
        assertTrue(avgQueryUs < 5000, "平均查询应 < 5ms，实际: " + avgQueryUs + "μs");
    }

    // ==================== 基准测试4: 融合评分性能 ====================

    @Test
    @Order(4)
    @DisplayName("融合评分性能 - 100000次融合操作")
    void testFusionScorerPerformance() {
        int iterations = 100_000;
        FusionScorer scorer = new FusionScorer();

        // 预热
        for (int i = 0; i < 1000; i++) {
            scorer.fuse(Math.random(), Math.random() * 10, Math.random(), 10.0);
        }

        // fuseNormalized 基准
        long fnStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            scorer.fuseNormalized(Math.random(), Math.random(), Math.random());
        }
        long fnNs = System.nanoTime() - fnStart;

        // fuse（含归一化）基准
        long fStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            scorer.fuse(Math.random(), Math.random() * 10, Math.random(), 10.0);
        }
        long fNs = System.nanoTime() - fStart;

        // normalizeBatch 基准（批量归一化）
        int batchIterations = 10_000;
        int batchSize = 1000;
        long nbStart = System.nanoTime();
        for (int i = 0; i < batchIterations; i++) {
            double[] scores = new double[batchSize];
            for (int j = 0; j < batchSize; j++) {
                scores[j] = Math.random() * 100;
            }
            FusionScorer.normalizeBatch(scores);
        }
        long nbNs = System.nanoTime() - nbStart;

        // 统计
        double fnOpsPerSec = iterations / (fnNs / 1e9);
        double fOpsPerSec = iterations / (fNs / 1e9);
        double nbOpsPerSec = batchIterations / (nbNs / 1e9);

        System.out.println("\n========== 融合评分性能基准 ==========");
        System.out.printf("  fuseNormalized:  %d次, %.3f ms总耗时, %.0f ops/s%n",
                iterations, fnNs / 1e6, fnOpsPerSec);
        System.out.printf("  fuse(含归一化):  %d次, %.3f ms总耗时, %.0f ops/s%n",
                iterations, fNs / 1e6, fOpsPerSec);
        System.out.printf("  normalizeBatch:  %d批×%d条, %.3f ms总耗时, %.0f batches/s%n",
                batchIterations, batchSize, nbNs / 1e6, nbOpsPerSec);
        System.out.printf("  单次fuseNormalized: %.2f ns%n", (double) fnNs / iterations);
        System.out.printf("  单次fuse:           %.2f ns%n", (double) fNs / iterations);
        System.out.println("=======================================\n");

        // 断言
        assertTrue(fnOpsPerSec > 1_000_000, "fuseNormalized应 > 1M ops/s，实际: " + fnOpsPerSec);
        assertTrue(fOpsPerSec > 500_000, "fuse应 > 500K ops/s，实际: " + fOpsPerSec);
    }

    // ==================== 基准测试5: BM25 + Fusion联合检索基准 ====================

    @Test
    @Order(5)
    @DisplayName("联合检索性能 - BM25索引+评分+融合 100次完整流程")
    void testCombinedRetrievalPerformance() {
        int docCount = 500;
        int queryCount = 100;

        // 1. 构建文档集
        List<VectorRecord> documents = generateTestDocuments(docCount);

        // 2. 构建BM25索引
        long indexStart = System.nanoTime();
        Bm25Scorer bm25 = new Bm25Scorer();
        bm25.index(documents);
        long indexNs = System.nanoTime() - indexStart;

        FusionScorer fusion = new FusionScorer();
        String[] queries = generateTestQueries(10);

        // 3. 完整检索流程基准
        long totalStart = System.nanoTime();
        for (int q = 0; q < queryCount; q++) {
            String query = queries[q % queries.length];

            // BM25评分
            Map<String, Double> bm25Scores = bm25.scoreQuery(query, documents);
            double bm25Max = bm25Scores.values().stream()
                    .mapToDouble(Double::doubleValue).max().orElse(1.0);

            // 模拟语义分数
            Map<String, Double> semanticScores = new HashMap<>();
            for (VectorRecord doc : documents) {
                semanticScores.put(doc.getId(), Math.random());
            }

            // 融合排序
            List<SearchResult> results = new ArrayList<>();
            for (VectorRecord doc : documents) {
                String docId = doc.getId();
                double semScore = semanticScores.getOrDefault(docId, 0.0);
                double bm25Score = bm25Scores.getOrDefault(docId, 0.0);
                double entityScore = Math.random();

                double fusedScore = fusion.fuse(semScore, bm25Score, entityScore, bm25Max);
                results.add(new SearchResult(docId, doc.getText(), fusedScore,
                        semScore, bm25Score, entityScore, Map.of()));
            }

            results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
            assertFalse(results.isEmpty());
        }
        long totalNs = System.nanoTime() - totalStart;

        double totalMs = totalNs / 1e6;
        double avgQueryMs = totalMs / queryCount;

        System.out.println("\n========== 联合检索性能基准 ==========");
        System.out.printf("  文档数:      %d%n", docCount);
        System.out.printf("  索引耗时:    %.3f ms%n", indexNs / 1e6);
        System.out.printf("  查询次数:    %d%n", queryCount);
        System.out.printf("  总检索耗时:  %.3f ms%n", totalMs);
        System.out.printf("  平均单次:    %.3f ms%n", avgQueryMs);
        System.out.printf("  检索吞吐量:  %.1f queries/s%n", queryCount / (totalNs / 1e9));
        System.out.println("======================================\n");

        assertTrue(avgQueryMs < 100, "单次联合检索应 < 100ms，实际: " + avgQueryMs + "ms");
    }

    // ==================== 辅助方法 ====================

    private Memory createMemory(String id, String userId, String text) {
        float[] vec = embeddingService.embed(text);
        double[] embedding = new double[vec.length];
        for (int i = 0; i < vec.length; i++) embedding[i] = vec[i];
        return Memory.builder()
                .id(id).text(text).userId(userId)
                .agentId("perf-test-agent")
                .importance(0.5).embedding(embedding)
                .build();
    }

    private List<VectorRecord> generateTestDocuments(int count) {
        String[] topics = {"机器学习", "深度学习", "自然语言处理", "计算机视觉",
                "数据分析", "Web开发", "分布式系统", "微服务架构",
                "云计算", "容器化", "网络安全", "区块链",
                "数据库优化", "缓存策略", "消息队列", "负载均衡",
                "人工智能", "强化学习", "推荐系统", "搜索引擎"};
        String[] actions = {"研究了", "学习了", "使用了", "优化了", "设计了", "实现了", "部署了", "测试了"};
        String[] targets = {"最新的", "高效的", "可扩展的", "稳定的", "安全的", "高性能的", "可靠的", "灵活的"};

        List<VectorRecord> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String topic = topics[i % topics.length];
            String action = actions[i % actions.length];
            String target = targets[i % targets.length];
            String text = String.format("%s%s%s%s技术方案", action, target, topic,
                    i > 0 ? "以及" + topics[(i + 5) % topics.length] : "");

            float[] vec = embeddingService.embed(text);

            docs.add(VectorRecord.builder()
                    .id("doc-" + i)
                    .text(text)
                    .userId("perf-test-user")
                    .vector(vec)
                    .metadata(Map.of())
                    .build());
        }
        return docs;
    }

    private String[] generateTestQueries(int count) {
        return new String[]{
                "机器学习模型训练优化",
                "Python数据分析最佳实践",
                "Java微服务架构设计模式",
                "分布式系统一致性协议",
                "云计算容器化部署方案",
                "网络安全加密算法选择",
                "数据库索引优化策略",
                "前端框架性能优化",
                "自然语言处理技术应用",
                "推荐系统算法设计"
        };
    }

    // ==================== Stub存储实现 ====================

    static class StubVectorStore implements VectorStore {
        volatile int vectorRecordCount = 0;
        private final Map<String, VectorRecord> store = new ConcurrentHashMap<>();

        @Override
        public boolean createCollection(String name, int dimension, String metric) { return true; }

        @Override
        public boolean upsert(String collection, List<VectorRecord> records) {
            for (VectorRecord r : records) store.put(r.getId(), r);
            vectorRecordCount += records.size();
            return true;
        }

        @Override
        public List<SearchResult> search(String collection, float[] queryVector,
                                         int topK, Map<String, Object> filters) {
            return List.of();
        }

        @Override
        public boolean delete(String collection, List<String> ids) {
            ids.forEach(store::remove);
            return true;
        }

        @Override
        public List<VectorRecord> get(String collection, List<String> ids) {
            return ids.stream().map(store::get).filter(Objects::nonNull).toList();
        }

        @Override
        public boolean healthCheck() { return true; }

        @Override
        public Map<String, Object> getStats(String collection) {
            return Map.of("count", store.size());
        }
    }

    static class StubGraphStore implements GraphStore {
        volatile int nodeCount = 0;

        @Override
        public String createNode(GraphNode node) { nodeCount++; return node.getId(); }
        @Override
        public String createEdge(GraphEdge edge) { return edge.getId(); }
        @Override
        public GraphNode getNode(String id) { return null; }
        @Override
        public List<Map<String, Object>> traverse(String s, List<String> r, String d, int m) { return List.of(); }
        @Override
        public List<GraphNode> searchNodes(String l, Map<String, Object> p, int limit) { return List.of(); }
        @Override
        public boolean delete(List<String> n, List<String> e) { return true; }
        @Override
        public List<String> findMemoriesByEntity(String e) { return List.of(); }
        @Override
        public boolean healthCheck() { return true; }
    }

    static class StubMetadataStore implements MetadataStore {
        volatile int recordCount = 0;
        private final Map<String, MetadataRecord> store = new ConcurrentHashMap<>();

        @Override
        public String insert(String table, MetadataRecord record) {
            store.put(record.getId(), record);
            recordCount++;
            return record.getId();
        }

        @Override
        public List<String> batchInsert(String table, List<MetadataRecord> records) {
            List<String> ids = new ArrayList<>();
            for (MetadataRecord r : records) {
                store.put(r.getId(), r);
                recordCount++;
                ids.add(r.getId());
            }
            return ids;
        }

        @Override
        public List<MetadataRecord> find(String table, Map<String, Object> filters, int limit, int offset) {
            return List.of();
        }

        @Override
        public boolean update(String table, String id, Map<String, Object> updates) { return true; }

        @Override
        public boolean delete(String table, List<String> ids) {
            ids.forEach(store::remove);
            return true;
        }

        @Override
        public long count(String table, Map<String, Object> filters) { return store.size(); }

        @Override
        public boolean healthCheck() { return true; }
    }
}
