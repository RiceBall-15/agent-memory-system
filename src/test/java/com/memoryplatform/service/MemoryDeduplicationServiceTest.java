package com.memoryplatform.service;

import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryDeduplicationService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MemoryDeduplicationServiceTest {

    @Mock
    private MetadataStore metadataStore;

    @Mock
    private VectorStore vectorStore;

    private MemoryDeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new MemoryDeduplicationService(metadataStore, vectorStore, 0.95, 10000, 100);
    }

    private Memory buildMemory(String id, String text, String userId, double importance) {
        return Memory.builder()
                .id(id)
                .text(text)
                .userId(userId)
                .agentId("agent1")
                .importance(importance)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void testCheckAndMerge_NoExistingMemories() {
        // 无现有记录时，checkAndMerge应返回null
        Memory newMemory = buildMemory("mem-new", "Hello world", "user1", 0.5);

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        String result = service.checkAndMerge(newMemory);

        assertNull(result, "无现有记录时应返回null");
    }

    @Test
    void testCheckAndMerge_NullStoreReturnsNull() {
        MemoryDeduplicationService svc =
                new MemoryDeduplicationService(null, vectorStore, 0.95, 10000, 100);
        Memory newMemory = buildMemory("mem-new", "test", "user1", 0.5);

        String result = svc.checkAndMerge(newMemory);
        assertNull(result, "metadataStore为null时应返回null");
    }

    @Test
    void testCheckAndMerge_NullVectorStoreReturnsNull() {
        MemoryDeduplicationService svc =
                new MemoryDeduplicationService(metadataStore, null, 0.95, 10000, 100);
        Memory newMemory = buildMemory("mem-new", "test", "user1", 0.5);

        // 有现有记录但向量存储为null
        MetadataRecord existing = new MetadataRecord();
        existing.setId("mem-existing");
        existing.setTable("memories");
        existing.setImportance(0.3);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        String result = svc.checkAndMerge(newMemory);

        // 向量相似度为0，文本相似度取决于BM25，如果不够高则返回null
        assertNull(result, "向量存储为null时相似度低应返回null");
    }

    @Test
    void testCheckAndMerge_HighSimilarityMerges() {
        // 场景：新记忆与现有记忆高度相似，应合并
        Memory newMemory = buildMemory("mem-new", "Updated memory content", "user1", 0.9);

        MetadataRecord existing = new MetadataRecord();
        existing.setId("mem-existing");
        existing.setTable("memories");
        existing.setContent("Updated memory content");
        existing.setImportance(0.5);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        // 向量搜索返回高相似度
        SearchResult sr = new SearchResult("mem-existing", "Updated memory content",
                0.98, 0.98, 0.0, 0.0, Map.of());
        when(vectorStore.search(eq("memories"), any(float[].class), anyInt(), anyMap()))
                .thenReturn(List.of(sr));

        when(metadataStore.update(eq("memories"), anyString(), anyMap()))
                .thenReturn(true);

        String result = service.checkAndMerge(newMemory);

        assertNotNull(result, "高相似度应返回被合并的ID");
        assertEquals("mem-existing", result);
        // 验证更新被调用（更新保留的记忆 + 标记被合并的记忆）
        verify(metadataStore, times(2)).update(eq("memories"), anyString(), anyMap());
    }

    @Test
    void testCheckAndMerge_LowSimilarityNoMerge() {
        // 场景：新记忆与现有记忆相似度低，不应合并
        Memory newMemory = buildMemory("mem-new", "Completely different content", "user1", 0.5);

        MetadataRecord existing = new MetadataRecord();
        existing.setId("mem-existing");
        existing.setTable("memories");
        existing.setContent("Something else entirely");
        existing.setImportance(0.3);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        // 向量搜索返回低相似度
        SearchResult sr = new SearchResult("mem-existing", "Something else",
                0.3, 0.3, 0.0, 0.0, Map.of());
        when(vectorStore.search(eq("memories"), any(float[].class), anyInt(), anyMap()))
                .thenReturn(List.of(sr));

        String result = service.checkAndMerge(newMemory);

        assertNull(result, "低相似度不应合并");
        verify(metadataStore, never()).update(anyString(), anyString(), anyMap());
    }

    @Test
    void testCheckAndMerge_SkipsSelfComparison() {
        // 场景：新记忆与自身ID相同，应跳过
        Memory newMemory = buildMemory("mem-1", "Test memory", "user1", 0.5);

        MetadataRecord existing = new MetadataRecord();
        existing.setId("mem-1"); // 同ID
        existing.setTable("memories");
        existing.setContent("Test memory");
        existing.setImportance(0.5);
        existing.setCreatedAt(Instant.now());

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        String result = service.checkAndMerge(newMemory);

        assertNull(result, "自身比较应跳过，返回null");
        verify(metadataStore, never()).update(anyString(), anyString(), anyMap());
    }

    @Test
    void testScanAndDedup_EmptyStore() {
        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        assertDoesNotThrow(() -> service.scanAndDedup());
    }

    @Test
    void testScanAndDedup_NullStoreNoOp() {
        MemoryDeduplicationService svc =
                new MemoryDeduplicationService(null, vectorStore, 0.95, 10000, 100);

        assertDoesNotThrow(() -> svc.scanAndDedup());
    }

    @Test
    void testGetStats_ReturnsCorrectKeys() {
        Map<String, Object> stats = service.getStats();
        assertNotNull(stats);
        assertTrue(stats.containsKey("totalScanned"));
        assertTrue(stats.containsKey("totalDuplicates"));
        assertTrue(stats.containsKey("totalMerged"));
        assertTrue(stats.containsKey("dedupThreshold"));
        assertEquals(0.95, (double) stats.get("dedupThreshold"), 0.001);
    }

    @Test
    void testDefaultConstructor() {
        MemoryDeduplicationService defaultService =
                new MemoryDeduplicationService(metadataStore, vectorStore);
        assertNotNull(defaultService);

        Map<String, Object> stats = defaultService.getStats();
        assertEquals(0.95, (double) stats.get("dedupThreshold"), 0.001);
    }
}
