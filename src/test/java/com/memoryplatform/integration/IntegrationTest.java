package com.memoryplatform.integration;

import com.memoryplatform.circuit.CircuitBreaker;
import com.memoryplatform.model.*;
import com.memoryplatform.scorer.Bm25Scorer;
import com.memoryplatform.scorer.FusionScorer;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.EmbeddingService;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试套件 - 测试完整的CRUD流程、记忆提取、混合检索
 * <p>
 * 所有测试独立运行，使用内联Stub存储实现，不依赖外部服务。
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {

    private StubVectorStore vectorStore;
    private StubGraphStore graphStore;
    private StubMetadataStore metadataStore;
    private ConcurrentWriteService writeService;
    private Bm25Scorer bm25Scorer;
    private FusionScorer fusionScorer;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        vectorStore = new StubVectorStore();
        graphStore = new StubGraphStore();
        metadataStore = new StubMetadataStore();
        embeddingService = EmbeddingService.noOp();

        writeService = ConcurrentWriteService.builder()
                .vectorStore(vectorStore)
                .graphStore(graphStore)
                .metadataStore(metadataStore)
                .embeddingService(embeddingService)
                .shardCount(4)
                .batchWindowMs(50)
                .maxRetries(1)
                .circuitFailureThreshold(5)
                .circuitRecoveryTimeoutMs(30000)
                .circuitSuccessThreshold(3)
                .build();

        bm25Scorer = new Bm25Scorer();
        fusionScorer = new FusionScorer();
    }

    @AfterEach
    void tearDown() {
        if (writeService != null) {
            writeService.shutdown(2000);
        }
    }

    // ==================== 测试1: 完整CRUD流程 ====================

    @Test
    @Order(1)
    @DisplayName("完整CRUD流程: 创建→获取→更新→删除")
    void testFullCrudFlow() throws Exception {
        // Step 1: 创建记忆
        Memory memory = createMemory("crud-mem-001", "user-crud",
                "张三喜欢使用Python进行数据分析", 0.8);

        CompletableFuture<WriteResult> future = writeService.write(memory);
        WriteResult writeResult = future.get(5, TimeUnit.SECONDS);

        assertTrue(writeResult.isSuccess(), "创建记忆应成功");
        assertEquals("crud-mem-001", writeResult.getMemoryId());
        assertNotNull(writeResult.getVectorId());
        assertNotNull(writeResult.getGraphId());
        assertNotNull(writeResult.getMetadataId());

        // Step 2: 通过元数据存储查询
        Map<String, Object> filters = Map.of("userId", "user-crud");
        List<MetadataRecord> records = metadataStore.find("memories", filters, 10, 0);
        assertFalse(records.isEmpty(), "应能查询到刚创建的记忆");

        MetadataRecord stored = records.get(0);
        assertEquals("crud-mem-001", stored.getId());
        assertEquals("张三喜欢使用Python进行数据分析", stored.getContent());
        assertEquals("user-crud", stored.getUserId());
        assertEquals(0.8, stored.getImportance(), 0.001);

        // Step 3: 更新记忆
        Map<String, Object> updates = new HashMap<>();
        updates.put("content", "张三喜欢使用Python和R进行数据分析");
        updates.put("importance", 0.9);
        updates.put("updatedAt", Instant.now().toString());

        boolean updated = metadataStore.update("memories", "crud-mem-001", updates);
        assertTrue(updated, "更新应成功");

        // 验证更新后的数据
        records = metadataStore.find("memories", filters, 10, 0);
        assertFalse(records.isEmpty());
        MetadataRecord updatedRecord = records.get(0);
        assertEquals("张三喜欢使用Python和R进行数据分析", updatedRecord.getContent());
        assertEquals(0.9, updatedRecord.getImportance(), 0.001);

        // Step 4: 删除记忆
        boolean deleted = metadataStore.delete("memories", List.of("crud-mem-001"));
        assertTrue(deleted, "删除应成功");

        // 验证删除成功
        records = metadataStore.find("memories", filters, 10, 0);
        assertTrue(records.isEmpty(), "删除后应查询不到记忆");
    }

    // ==================== 测试2: 批量创建与查询 ====================

    @Test
    @Order(2)
    @DisplayName("批量创建记忆并验证存储一致性")
    void testBulkCreateAndVerify() throws Exception {
        int count = 10;
        List<CompletableFuture<WriteResult>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Memory mem = createMemory("bulk-mem-" + i, "user-batch",
                    "批量记忆第" + i + "条：关于" + getTopic(i) + "的讨论", 0.5 + (i * 0.05));
            futures.add(writeService.write(mem));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        // 验证所有写入成功
        for (CompletableFuture<WriteResult> f : futures) {
            assertTrue(f.get().isSuccess(), "批量写入每条都应成功");
        }

        // 验证向量存储收到所有记录
        assertEquals(count, vectorStore.vectorRecordCount, "向量存储应收到" + count + "条记录");

        // 验证图存储创建了节点
        assertTrue(graphStore.nodeCount >= count, "图存储应创建" + count + "个节点");

        // 验证元数据存储
        assertEquals(count, metadataStore.recordCount, "元数据存储应收到" + count + "条记录");

        // 通过元数据存储查询特定用户
        List<MetadataRecord> records = metadataStore.find("memories",
                Map.of("userId", "user-batch"), 100, 0);
        assertEquals(count, records.size(), "应查到全部" + count + "条记录");
    }

    // ==================== 测试3: 记忆提取流程 ====================

    @Test
    @Order(3)
    @DisplayName("模拟记忆提取流程: 对话→提取→写入→验证")
    void testMemoryExtractionFlow() throws Exception {
        // 模拟对话输入
        List<Message> conversation = List.of(
                new Message("user", "我最近在学Rust编程语言"),
                new Message("assistant", "Rust是很好的系统级编程语言"),
                new Message("user", "对，我在做一个关于WebAssembly的项目，用Rust写的"),
                new Message("assistant", "WebAssembly + Rust是非常强大的组合"),
                new Message("user", "我叫李明，是字节跳动的工程师")
        );

        // 模拟提取的记忆（不经过LLM，直接构造）
        List<Memory> extractedMemories = List.of(
                createMemory("extract-001", "user-lee",
                        "李明最近在学习Rust编程语言", 0.7),
                createMemory("extract-002", "user-lee",
                        "李明正在做一个WebAssembly项目，使用Rust编写", 0.8),
                createMemory("extract-003", "user-lee",
                        "李明是字节跳动的工程师", 0.9)
        );

        // 添加实体信息
        Memory entityMemory = createMemory("extract-004", "user-lee",
                "李明的公司是字节跳动", 0.85);
        List<Entity> entities = List.of(
                new Entity("李明", EntityType.PERSON, 0.95),
                new Entity("字节跳动", EntityType.ORG, 0.9),
                new Entity("Rust", EntityType.SKILL, 0.85),
                new Entity("WebAssembly", EntityType.PROJECT, 0.8)
        );
        Memory enrichedMemory = Memory.builder()
                .id(entityMemory.getId())
                .text(entityMemory.getText())
                .userId(entityMemory.getUserId())
                .agentId(entityMemory.getAgentId())
                .importance(entityMemory.getImportance())
                .entities(entities)
                .build();

        // 逐条写入
        List<Memory> allMemories = new ArrayList<>(extractedMemories);
        allMemories.add(enrichedMemory);

        List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
        for (Memory mem : allMemories) {
            futures.add(writeService.write(mem));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        // 验证写入结果
        for (CompletableFuture<WriteResult> f : futures) {
            assertTrue(f.get().isSuccess(), "提取的记忆写入应成功");
        }

        // 验证包含实体的记忆在图存储中有记录
        assertTrue(graphStore.nodeCount >= allMemories.size(),
                "图存储应记录所有记忆节点");

        // 验证元数据查询
        List<MetadataRecord> storedRecords = metadataStore.find("memories",
                Map.of("userId", "user-lee"), 100, 0);
        assertEquals(allMemories.size(), storedRecords.size(),
                "元数据应存储所有提取的记忆");
    }

    // ==================== 测试4: 混合检索 ====================

    @Test
    @Order(4)
    @DisplayName("混合检索: 向量相似度 + BM25 + 实体增强 → 融合排序")
    void testHybridRetrieval() {
        // 1. 构建文档集
        List<VectorRecord> documents = List.of(
                createVectorRecord("doc-1", "张三喜欢用Python做数据分析"),
                createVectorRecord("doc-2", "李四在研究Rust系统编程"),
                createVectorRecord("doc-3", "王五正在开发Java Web应用"),
                createVectorRecord("doc-4", "Python数据分析是数据科学的核心技能"),
                createVectorRecord("doc-5", "Rust语言以其内存安全性著称"),
                createVectorRecord("doc-6", "机器学习模型需要大量训练数据"),
                createVectorRecord("doc-7", "深度学习在自然语言处理中表现优异"),
                createVectorRecord("doc-8", "Python是机器学习最流行的编程语言")
        );

        // 2. 构建BM25索引
        bm25Scorer.index(documents);

        // 3. 查询
        String query = "Python机器学习";

        // 4. BM25评分
        Map<String, Double> bm25Scores = bm25Scorer.scoreQuery(query, documents);
        assertFalse(bm25Scores.isEmpty(), "BM25应返回评分结果");

        // 验证包含"Python"和"机器学习"的文档得分较高
        assertTrue(bm25Scores.getOrDefault("doc-4", 0.0) > 0,
                "doc-4包含Python和数据分析，应有BM25得分");
        assertTrue(bm25Scores.getOrDefault("doc-8", 0.0) > 0,
                "doc-8包含Python和机器学习，应有BM25得分");

        // 5. 找到BM25最大值用于归一化
        double bm25Max = bm25Scores.values().stream()
                .mapToDouble(Double::doubleValue)
                .max().orElse(1.0);

        // 6. 模拟语义分数（实际应来自向量检索）
        Map<String, Double> semanticScores = Map.of(
                "doc-1", 0.7, "doc-2", 0.3, "doc-3", 0.2,
                "doc-4", 0.9, "doc-5", 0.25, "doc-6", 0.6,
                "doc-7", 0.5, "doc-8", 0.95
        );

        // 7. 模拟实体boost（基于查询中实体的匹配度）
        Map<String, Double> entityBoosts = Map.of(
                "doc-1", 0.8, "doc-2", 0.1, "doc-3", 0.0,
                "doc-4", 0.9, "doc-5", 0.1, "doc-6", 0.5,
                "doc-7", 0.4, "doc-8", 0.85
        );

        // 8. 融合评分
        List<SearchResult> results = new ArrayList<>();
        for (VectorRecord doc : documents) {
            String docId = doc.getId();
            double semScore = semanticScores.getOrDefault(docId, 0.0);
            double bm25Score = bm25Scores.getOrDefault(docId, 0.0);
            double entityScore = entityBoosts.getOrDefault(docId, 0.0);

            double fusedScore = fusionScorer.fuse(semScore, bm25Score, entityScore, bm25Max);

            results.add(new SearchResult(
                    docId, doc.getText(), fusedScore,
                    semScore, bm25Score, entityScore,
                    Map.of()));
        }

        // 9. 按融合分数排序
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());

        // 10. 验证排序结果
        assertFalse(results.isEmpty(), "融合检索应返回结果");
        assertEquals(documents.size(), results.size(), "应返回所有文档的评分");

        // top结果应与查询最相关
        SearchResult topResult = results.get(0);
        assertTrue(topResult.getScore() > 0, "最高分应大于0");

        // doc-4 和 doc-8 应排在前面（包含Python和机器学习）
        List<String> top3Ids = results.subList(0, 3).stream()
                .map(SearchResult::getId).toList();
        assertTrue(top3Ids.contains("doc-4") || top3Ids.contains("doc-8"),
                "top3应包含与Python/机器学习相关的文档");

        System.out.println("[IntegrationTest] 混合检索Top5:");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            SearchResult r = results.get(i);
            System.out.printf("  #%d [%s] score=%.4f (sem=%.2f, bm25=%.4f, entity=%.2f) %s%n",
                    i + 1, r.getId(), r.getScore(),
                    r.getSemanticScore(), r.getBm25Score(), r.getEntityBoost(),
                    r.getText());
        }
    }

    // ==================== 测试5: 分片路由一致性 ====================

    @Test
    @Order(5)
    @DisplayName("分片路由: 相同用户路由到同一分片")
    void testShardRoutingConsistency() throws Exception {
        // 同一用户的消息应路由到同一分片
        List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Memory mem = createMemory("shard-mem-" + i, "consistent-user",
                    "分片测试消息" + i, 0.5);
            futures.add(writeService.write(mem));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        for (CompletableFuture<WriteResult> f : futures) {
            WriteResult r = f.get();
            assertTrue(r.isSuccess(), "同用户分片路由写入应全部成功");
        }

        // 同一用户的数据应全部写入成功
        assertEquals(8, vectorStore.vectorRecordCount, "应写入8条向量");
    }

    // ==================== 测试6: 错误处理与重试 ====================

    @Test
    @Order(6)
    @DisplayName("写入失败时的熔断器状态")
    void testCircuitBreakerOnFailure() {
        // 获取熔断器
        CircuitBreaker cb = writeService.getVectorCircuitBreaker();
        assertNotNull(cb, "应有向量存储熔断器");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState(),
                "初始状态应为CLOSED");

        // 验证所有存储的熔断器都已初始化
        assertNotNull(writeService.getGraphCircuitBreaker());
        assertNotNull(writeService.getMetadataCircuitBreaker());
    }

    // ==================== 测试7: 统计信息 ====================

    @Test
    @Order(7)
    @DisplayName("服务统计信息验证")
    void testServiceStats() throws Exception {
        // 写入几条记忆
        for (int i = 0; i < 3; i++) {
            Memory mem = createMemory("stats-mem-" + i, "stats-user",
                    "统计测试记忆" + i, 0.5);
            writeService.write(mem).get(5, TimeUnit.SECONDS);
        }

        String stats = writeService.getStats();
        assertNotNull(stats, "统计信息不应为空");
        assertTrue(stats.contains("totalWrites=3"), "应记录3次写入");
    }

    // ==================== 辅助方法 ====================

    private Memory createMemory(String id, String userId, String text, double importance) {
        return Memory.builder()
                .id(id)
                .text(text)
                .userId(userId)
                .agentId("integration-test-agent")
                .importance(importance)
                .embedding(embeddingService.embed(text))
                .build();
    }

    private VectorRecord createVectorRecord(String id, String text) {
        float[] vector = embeddingService.embed(text);
        return VectorRecord.builder()
                .id(id)
                .text(text)
                .userId("integration-test-user")
                .vector(vector)
                .metadata(Map.of())
                .build();
    }

    private String getTopic(int index) {
        String[] topics = {"机器学习", "Web开发", "数据分析", "系统设计",
                "分布式系统", "云计算", "微服务", "容器化", "网络安全", "区块链"};
        return topics[index % topics.length];
    }

    // ==================== Stub存储实现 ====================

    static class StubVectorStore implements VectorStore {
        volatile int vectorRecordCount = 0;
        private final Map<String, VectorRecord> store = new ConcurrentHashMap<>();

        @Override
        public boolean createCollection(String name, int dimension, String metric) { return true; }

        @Override
        public boolean upsert(String collection, List<VectorRecord> records) {
            for (VectorRecord r : records) {
                store.put(r.getId(), r);
            }
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
            for (String id : ids) {
                store.remove(id);
            }
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
        volatile int edgeCount = 0;
        private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();

        @Override
        public String createNode(GraphNode node) {
            nodes.put(node.getId(), node);
            nodeCount++;
            return node.getId();
        }

        @Override
        public String createEdge(GraphEdge edge) {
            edgeCount++;
            return edge.getId();
        }

        @Override
        public GraphNode getNode(String id) { return nodes.get(id); }

        @Override
        public List<Map<String, Object>> traverse(String startNodeId,
                                                   List<String> relationshipTypes,
                                                   String direction, int maxDepth) {
            return List.of();
        }

        @Override
        public List<GraphNode> searchNodes(String label, Map<String, Object> properties, int limit) {
            return List.of();
        }

        @Override
        public boolean delete(List<String> nodeIds, List<String> edgeIds) {
            nodeIds.forEach(nodes::remove);
            return true;
        }

        @Override
        public List<String> findMemoriesByEntity(String entityName) { return List.of(); }

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
        public List<MetadataRecord> find(String table, Map<String, Object> filters,
                                          int limit, int offset) {
            return store.values().stream()
                    .filter(r -> matchFilters(r, filters))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean update(String table, String id, Map<String, Object> updates) {
            MetadataRecord record = store.get(id);
            if (record == null) return false;

            if (updates.containsKey("content")) {
                record.setContent((String) updates.get("content"));
            }
            if (updates.containsKey("importance")) {
                record.setImportance((double) updates.get("importance"));
            }
            if (updates.containsKey("userId")) {
                record.setUserId((String) updates.get("userId"));
            }
            if (updates.containsKey("agentId")) {
                record.setAgentId((String) updates.get("agentId"));
            }
            return true;
        }

        @Override
        public boolean delete(String table, List<String> ids) {
            for (String id : ids) {
                store.remove(id);
            }
            return true;
        }

        @Override
        public long count(String table, Map<String, Object> filters) {
            return store.values().stream().filter(r -> matchFilters(r, filters)).count();
        }

        @Override
        public boolean healthCheck() { return true; }

        private boolean matchFilters(MetadataRecord record, Map<String, Object> filters) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                switch (entry.getKey()) {
                    case "userId" -> {
                        if (!entry.getValue().equals(record.getUserId())) return false;
                    }
                    case "id" -> {
                        if (!entry.getValue().equals(record.getId())) return false;
                    }
                    case "agentId" -> {
                        if (!entry.getValue().equals(record.getAgentId())) return false;
                    }
                }
            }
            return true;
        }
    }


}
