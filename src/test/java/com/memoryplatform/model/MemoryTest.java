package com.memoryplatform.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory数据模型单元测试
 */
class MemoryTest {

    @Test
    void testBuilderCreatesValidMemory() {
        Instant now = Instant.parse("2025-01-01T00:00:00Z");
        List<Entity> entities = List.of(
            new Entity("张三", EntityType.PERSON, 0.95),
            new Entity("阿里巴巴", EntityType.ORG, 0.88)
        );
        List<String> linkedIds = Arrays.asList("mem-001", "mem-002");
        double[] embedding = {0.1, 0.2, 0.3};

        Memory memory = Memory.builder()
                .id("mem-100")
                .text("张三在阿里巴巴工作")
                .userId("user-01")
                .agentId("agent-01")
                .entities(entities)
                .linkedMemoryIds(linkedIds)
                .importance(0.9)
                .embedding(embedding)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertEquals("mem-100", memory.getId());
        assertEquals("张三在阿里巴巴工作", memory.getText());
        assertEquals("user-01", memory.getUserId());
        assertEquals("agent-01", memory.getAgentId());
        assertEquals(2, memory.getEntities().size());
        assertEquals(2, memory.getLinkedMemoryIds().size());
        assertEquals(0.9, memory.getImportance(), 0.001);
        assertArrayEquals(embedding, memory.getEmbedding());
        assertEquals(now, memory.getCreatedAt());
        assertEquals(now, memory.getUpdatedAt());
    }

    @Test
    void testBuilderDefaultImportance() {
        Memory memory = Memory.builder()
                .id("mem-200")
                .text("测试默认重要性")
                .userId("user-02")
                .build();

        assertEquals(0.5, memory.getImportance(), 0.001);
        assertNotNull(memory.getCreatedAt());
        assertNotNull(memory.getUpdatedAt());
        assertNull(memory.getEntities());
        assertNull(memory.getLinkedMemoryIds());
        assertNull(memory.getEmbedding());
        assertNull(memory.getAgentId());
    }

    @Test
    void testBuilderRejectsNullId() {
        assertThrows(IllegalStateException.class, () ->
            Memory.builder()
                .text("缺少ID")
                .userId("user-01")
                .build()
        );
    }

    @Test
    void testBuilderRejectsNullText() {
        assertThrows(IllegalStateException.class, () ->
            Memory.builder()
                .id("mem-300")
                .userId("user-01")
                .build()
        );
    }

    @Test
    void testBuilderRejectsNullUserId() {
        assertThrows(IllegalStateException.class, () ->
            Memory.builder()
                .id("mem-400")
                .text("缺少用户ID")
                .build()
        );
    }

    @Test
    void testChainedBuilderMethods() {
        Memory m1 = Memory.builder().id("a").text("t").userId("u").build();
        Memory m2 = Memory.builder().id("b").text("t").userId("u").importance(0.1).build();
        Memory m3 = Memory.builder().id("c").text("t").userId("u").importance(1.0).build();

        assertEquals(0.5, m1.getImportance(), 0.001);
        assertEquals(0.1, m2.getImportance(), 0.001);
        assertEquals(1.0, m3.getImportance(), 0.001);
    }
}
