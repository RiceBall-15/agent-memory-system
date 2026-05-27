package com.memoryplatform.service;

import com.memoryplatform.circuit.CircuitBreaker;
import com.memoryplatform.model.*;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConcurrentWriteService分片队列写入单元测试
 */
class ConcurrentWriteServiceTest {

    private StubVectorStore vectorStore;
    private StubGraphStore graphStore;
    private StubMetadataStore metadataStore;
    private ConcurrentWriteService service;

    @BeforeEach
    void setUp() {
        vectorStore = new StubVectorStore();
        graphStore = new StubGraphStore();
        metadataStore = new StubMetadataStore();

        service = ConcurrentWriteService.builder()
                .vectorStore(vectorStore)
                .graphStore(graphStore)
                .metadataStore(metadataStore)
                .embeddingService(EmbeddingService.noOp())
                .shardCount(4)
                .batchWindowMs(50)
                .maxRetries(1)
                .circuitFailureThreshold(5)
                .circuitRecoveryTimeoutMs(30000)
                .circuitSuccessThreshold(3)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown(2000);
        }
    }

    @Test
    void testWriteSingleMemory() throws Exception {
        Memory memory = createMemory("mem-001", "user-01", "测试记忆");

        CompletableFuture<WriteResult> future = service.write(memory);
        WriteResult result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess(), "单条写入应成功");
        assertEquals("mem-001", result.getMemoryId());
        assertTrue(vectorStore.upserted);
        assertTrue(graphStore.nodeCreated);
        assertTrue(metadataStore.batchInserted);
    }

    @Test
    void testWriteMultipleMemoriesSameUser() throws Exception {
        // 同一用户的消息应路由到同一分片
        List<CompletableFuture<WriteResult>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Memory mem = createMemory("mem-" + i, "user-same", "记忆" + i);
            futures.add(service.write(mem));
        }

        // 等待所有完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        for (CompletableFuture<WriteResult> f : futures) {
            assertTrue(f.get().isSuccess(), "所有写入应成功");
        }
        assertTrue(vectorStore.vectorRecordCount >= 5, "应批量写入5条向量记录");
    }

    @Test
    void testWriteToDifferentShards() throws Exception {
        // 不同用户hash应分配到不同分片
        Memory m1 = createMemory("mem-a", "user-alpha", "记忆A");
        Memory m2 = createMemory("mem-b", "user-beta", "记忆B");

        CompletableFuture<WriteResult> f1 = service.write(m1);
        CompletableFuture<WriteResult> f2 = service.write(m2);

        WriteResult r1 = f1.get(5, TimeUnit.SECONDS);
        WriteResult r2 = f2.get(5, TimeUnit.SECONDS);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
    }

    @Test
    void testWriteAfterShutdown() throws Exception {
        service.shutdown(1000);

        Memory memory = createMemory("mem-shutdown", "user-01", "关闭后写入");
        CompletableFuture<WriteResult> future = service.write(memory);
        WriteResult result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.isSuccess(), "关闭后写入应失败");
        assertNotNull(result.getError());
    }

    @Test
    void testCircuitBreakerState() {
        CircuitBreaker cb = service.getVectorCircuitBreaker();
        assertNotNull(cb);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals("vector-store", cb.getName());

        assertNotNull(service.getGraphCircuitBreaker());
        assertNotNull(service.getMetadataCircuitBreaker());
    }

    @Test
    void testServiceStats() throws Exception {
        Memory mem = createMemory("mem-stats", "user-stats", "统计测试");
        service.write(mem).get(5, TimeUnit.SECONDS);

        String stats = service.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("totalWrites=1"), "统计应显示1次写入");
    }

    @Test
    void testBuilderValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            ConcurrentWriteService.builder()
                .shardCount(0)
                .build()
        );
        assertThrows(IllegalArgumentException.class, () ->
            ConcurrentWriteService.builder()
                .batchWindowMs(0)
                .build()
        );
    }

    // ============ 测试辅助 ============

    private Memory createMemory(String id, String userId, String text) {
        return Memory.builder()
                .id(id)
                .text(text)
                .userId(userId)
                .agentId("test-agent")
                .importance(0.7)
                .embedding(new double[]{0.1, 0.2, 0.3})
                .build();
    }

    // ============ Stub实现 ============

    static class StubVectorStore implements VectorStore {
        volatile boolean upserted = false;
        volatile int vectorRecordCount = 0;

        @Override
        public boolean createCollection(String name, int dimension, String metric) { return true; }

        @Override
        public boolean upsert(String collection, List<VectorRecord> records) {
            upserted = true;
            vectorRecordCount += records.size();
            return true;
        }

        @Override
        public List<SearchResult> search(String collection, float[] queryVector,
                                         int topK, Map<String, Object> filters) {
            return List.of();
        }

        @Override
        public boolean delete(String collection, List<String> ids) { return true; }

        @Override
        public List<VectorRecord> get(String collection, List<String> ids) { return List.of(); }

        @Override
        public boolean healthCheck() { return true; }

        @Override
        public Map<String, Object> getStats(String collection) { return Map.of(); }
    }

    static class StubGraphStore implements GraphStore {
        volatile boolean nodeCreated = false;
        volatile int nodeCount = 0;

        @Override
        public String createNode(GraphNode node) {
            nodeCreated = true;
            nodeCount++;
            return node.getId();
        }

        @Override
        public String createEdge(GraphEdge edge) { return edge.getId(); }

        @Override
        public GraphNode getNode(String id) { return null; }

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
        public boolean delete(List<String> nodeIds, List<String> edgeIds) { return true; }

        @Override
        public List<String> findMemoriesByEntity(String entityName) { return List.of(); }

        @Override
        public boolean healthCheck() { return true; }
    }

    static class StubMetadataStore implements MetadataStore {
        volatile boolean batchInserted = false;
        volatile int recordCount = 0;

        @Override
        public String insert(String table, MetadataRecord record) {
            recordCount++;
            return record.getId();
        }

        @Override
        public List<String> batchInsert(String table, List<MetadataRecord> records) {
            batchInserted = true;
            recordCount += records.size();
            return records.stream().map(MetadataRecord::getId).toList();
        }

        @Override
        public List<MetadataRecord> find(String table, Map<String, Object> filters,
                                          int limit, int offset) {
            return List.of();
        }

        @Override
        public boolean update(String table, String id, Map<String, Object> updates) { return true; }

        @Override
        public boolean delete(String table, List<String> ids) { return true; }

        @Override
        public long count(String table, Map<String, Object> filters) { return 0; }

        @Override
        public boolean healthCheck() { return true; }
    }
}
