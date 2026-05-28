package com.memoryplatform.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorRecord数据模型单元测试
 */
class VectorRecordTest {

    @Test
    void testBuilderCreatesValidVectorRecord() {
        Instant now = Instant.parse("2025-06-01T12:00:00Z");
        float[] vector = {0.1f, 0.2f, 0.3f, 0.4f};
        List<Entity> entities = List.of(
            new Entity("北京", EntityType.LOCATION, 0.92)
        );
        Map<String, Object> metadata = Map.of("source", "chat", "version", 2);

        VectorRecord record = VectorRecord.builder()
                .id("vec-001")
                .collection("memories")
                .vector(vector)
                .text("我住在北京市")
                .userId("user-10")
                .agentId("agent-10")
                .entities(entities)
                .importance(0.85)
                .metadata(metadata)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertEquals("vec-001", record.getId());
        assertEquals("memories", record.getCollection());
        assertArrayEquals(vector, record.getVector());
        assertEquals("我住在北京市", record.getText());
        assertEquals("user-10", record.getUserId());
        assertEquals("agent-10", record.getAgentId());
        assertEquals(1, record.getEntities().size());
        assertEquals(0.85, record.getImportance(), 0.001);
        assertEquals("chat", record.getMetadata().get("source"));
        assertEquals(2, record.getMetadata().get("version"));
        assertEquals(now, record.getCreatedAt());
        assertEquals(now, record.getUpdatedAt());
    }

    @Test
    void testBuilderDefaultImportanceAndTimestamps() {
        VectorRecord record = VectorRecord.builder()
                .id("vec-002")
                .text("默认值测试")
                .userId("user-02")
                .build();

        assertEquals(0.5, record.getImportance(), 0.001);
        assertNotNull(record.getCreatedAt());
        assertNotNull(record.getUpdatedAt());
        assertNull(record.getVector());
        assertNull(record.getCollection());
        assertNull(record.getAgentId());
        assertNull(record.getEntities());
        assertNull(record.getMetadata());
    }

    @Test
    void testBuilderRejectsNullId() {
        assertThrows(IllegalStateException.class, () ->
            VectorRecord.builder()
                .text("缺少ID")
                .userId("user-01")
                .build()
        );
    }

    @Test
    void testBuilderRejectsNullText() {
        assertThrows(IllegalStateException.class, () ->
            VectorRecord.builder()
                .id("vec-100")
                .userId("user-01")
                .build()
        );
    }

    @Test
    void testBuilderRejectsNullUserId() {
        assertThrows(IllegalStateException.class, () ->
            VectorRecord.builder()
                .id("vec-200")
                .text("缺少userId")
                .build()
        );
    }

    @Test
    void testVectorRecordWithNullVector() {
        VectorRecord record = VectorRecord.builder()
                .id("vec-no-vec")
                .text("无向量记录")
                .userId("u1")
                .build();

        assertNull(record.getVector());
        assertNotNull(record.getText());
    }
}
