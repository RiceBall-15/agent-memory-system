package com.memoryplatform.service;

import com.memoryplatform.model.MemoryVersion;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryVersionService 单元测试
 * <p>
 * 测试版本管理功能：创建版本、获取版本列表、版本对比、版本回滚、最大版本数限制。
 * 使用Mock MetadataStore模拟持久化层。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class MemoryVersionServiceTest {

    @Mock
    private MetadataStore metadataStore;

    private MemoryVersionService service;

    @BeforeEach
    void setUp() {
        service = new MemoryVersionService(metadataStore);
        // 设置最大版本数为5（便于测试限制逻辑）
        ReflectionTestUtils.setField(service, "maxVersions", 5);
    }

    // ==================== createVersion ====================

    @Test
    void testCreateVersion_BasicCreation() {
        // 模拟MetadataStore返回空（首次创建）
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        MemoryVersion version = service.createVersion(
                "mem1", "Hello world", 0.8,
                MemoryVersion.ChangeType.CREATE, "user1"
        );

        assertNotNull(version);
        assertEquals("mem1_v1", version.getVersionId());
        assertEquals("mem1", version.getMemoryId());
        assertEquals(1, version.getVersion());
        assertEquals("Hello world", version.getContent());
        assertEquals(0.8, version.getImportance(), 0.001);
        assertEquals(MemoryVersion.ChangeType.CREATE, version.getChangeType());
        assertEquals("user1", version.getChangedBy());
        assertNotNull(version.getCreatedAt());
        assertNotNull(version.getSnapshot());
        assertEquals("Hello world", version.getSnapshot().get("content"));
    }

    @Test
    void testCreateVersion_IncrementsVersionNumber() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        // 第一个版本
        MemoryVersion v1 = service.createVersion(
                "mem1", "First version", 0.5,
                MemoryVersion.ChangeType.CREATE, "user1"
        );
        assertEquals(1, v1.getVersion());

        // 第二个版本 - 模拟MetadataStore返回已有版本的find结果
        // 注意：由于版本已缓存在内存中，getVersionsInternal会先命中缓存
        MemoryVersion v2 = service.createVersion(
                "mem1", "Second version", 0.7,
                MemoryVersion.ChangeType.UPDATE, "user1"
        );
        assertEquals(2, v2.getVersion());
        assertEquals("mem1_v2", v2.getVersionId());
    }

    @Test
    void testCreateVersion_NullMemoryId_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.createVersion(null, "content", 0.5,
                        MemoryVersion.ChangeType.CREATE, "user1")
        );
    }

    @Test
    void testCreateVersion_BlankMemoryId_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.createVersion("  ", "content", 0.5,
                        MemoryVersion.ChangeType.CREATE, "user1")
        );
    }

    // ==================== getVersions ====================

    @Test
    void testGetVersions_ReturnsVersionsDescending() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        // 创建3个版本
        service.createVersion("mem1", "v1 content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");
        service.createVersion("mem1", "v2 content", 0.6, MemoryVersion.ChangeType.UPDATE, "user1");
        service.createVersion("mem1", "v3 content", 0.7, MemoryVersion.ChangeType.UPDATE, "user2");

        List<MemoryVersion> versions = service.getVersions("mem1");

        assertEquals(3, versions.size());
        // 最新版本在前（降序）
        assertEquals(3, versions.get(0).getVersion());
        assertEquals(2, versions.get(1).getVersion());
        assertEquals(1, versions.get(2).getVersion());
    }

    @Test
    void testGetVersions_EmptyForUnknownMemoryId() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        List<MemoryVersion> versions = service.getVersions("unknown_mem");

        assertNotNull(versions);
        assertTrue(versions.isEmpty());
    }

    @Test
    void testGetVersions_ReturnsUnmodifiableList() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        service.createVersion("mem1", "content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");

        List<MemoryVersion> versions = service.getVersions("mem1");
        assertThrows(UnsupportedOperationException.class, () ->
                versions.add(MemoryVersion.builder().build())
        );
    }

    // ==================== getVersion ====================

    @Test
    void testGetVersion_ReturnsCorrectVersion() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        service.createVersion("mem1", "v1 content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");
        service.createVersion("mem1", "v2 content", 0.7, MemoryVersion.ChangeType.UPDATE, "user1");

        MemoryVersion v1 = service.getVersion("mem1", 1);
        assertNotNull(v1);
        assertEquals(1, v1.getVersion());
        assertEquals("v1 content", v1.getContent());

        MemoryVersion v2 = service.getVersion("mem1", 2);
        assertNotNull(v2);
        assertEquals(2, v2.getVersion());
        assertEquals("v2 content", v2.getContent());
    }

    @Test
    void testGetVersion_NonExistentVersion_ReturnsNull() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        MemoryVersion result = service.getVersion("mem1", 999);
        assertNull(result);
    }

    // ==================== getDiff ====================

    @Test
    void testGetDiff_ShowsContentAndImportanceChanges() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        service.createVersion("mem1", "original content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");
        service.createVersion("mem1", "updated content", 0.8, MemoryVersion.ChangeType.UPDATE, "user2");

        Map<String, Object> diff = service.getDiff("mem1", 1, 2);

        assertNotNull(diff);
        assertEquals("mem1", diff.get("memoryId"));
        assertEquals(1, diff.get("fromVersion"));
        assertEquals(2, diff.get("toVersion"));
        assertEquals(true, diff.get("contentChanged"));
        assertEquals("original content", diff.get("contentBefore"));
        assertEquals("updated content", diff.get("contentAfter"));
        assertEquals(true, diff.get("importanceChanged"));
        assertEquals(0.5, (double) diff.get("importanceBefore"), 0.001);
        assertEquals(0.8, (double) diff.get("importanceAfter"), 0.001);
    }

    @Test
    void testGetDiff_NoChanges_ReportsFalse() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        service.createVersion("mem1", "same content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");
        service.createVersion("mem1", "same content", 0.5, MemoryVersion.ChangeType.UPDATE, "user1");

        Map<String, Object> diff = service.getDiff("mem1", 1, 2);

        assertEquals(false, diff.get("contentChanged"));
        assertEquals(false, diff.get("importanceChanged"));
    }

    @Test
    void testGetDiff_VersionNotFound_ReturnsErrorMap() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        Map<String, Object> diff = service.getDiff("mem1", 1, 999);

        assertNotNull(diff);
        assertEquals("版本不存在", diff.get("error"));
        assertEquals(false, diff.get("v1_found"));
        assertEquals(false, diff.get("v2_found"));
    }

    // ==================== rollbackTo ====================

    @Test
    void testRollbackTo_CreatesNewVersionWithOldContent() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        // 创建3个版本
        service.createVersion("mem1", "original", 0.5, MemoryVersion.ChangeType.CREATE, "user1");
        service.createVersion("mem1", "modified v2", 0.6, MemoryVersion.ChangeType.UPDATE, "user1");
        service.createVersion("mem1", "modified v3", 0.7, MemoryVersion.ChangeType.UPDATE, "user1");

        // 回滚到v1
        MemoryVersion rolledBack = service.rollbackTo("mem1", 1, "admin");

        assertNotNull(rolledBack);
        assertEquals(4, rolledBack.getVersion());
        assertEquals("original", rolledBack.getContent());
        assertEquals(0.5, rolledBack.getImportance(), 0.001);
        assertEquals(MemoryVersion.ChangeType.UPDATE, rolledBack.getChangeType());
        assertEquals("admin", rolledBack.getChangedBy());
    }

    @Test
    void testRollbackTo_NonExistentVersion_ThrowsException() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        assertThrows(IllegalArgumentException.class, () ->
                service.rollbackTo("mem1", 999, "admin")
        );
    }

    // ==================== 最大版本数限制 ====================

    @Test
    void testMaxVersionsLimit_Enforced() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        // maxVersions = 5，创建7个版本
        for (int i = 1; i <= 7; i++) {
            service.createVersion("mem1", "content " + i, 0.5,
                    MemoryVersion.ChangeType.CREATE, "user1");
        }

        List<MemoryVersion> versions = service.getVersions("mem1");
        assertEquals(5, versions.size(), "应保留最多5个版本");
        // 最新版本应是v7
        assertEquals(7, versions.get(0).getVersion());
        // 最旧版本应是v3（v1和v2被清理）
        assertEquals(3, versions.get(versions.size() - 1).getVersion());
    }

    // ==================== getStats ====================

    @Test
    void testGetStats_ReturnsCorrectKeys() {
        when(metadataStore.find(eq("memory_versions"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(metadataStore.insert(eq("memory_versions"), any(MetadataRecord.class)))
                .thenReturn("versions_mem1");

        service.createVersion("mem1", "content", 0.5, MemoryVersion.ChangeType.CREATE, "user1");

        Map<String, Object> stats = service.getStats();
        assertNotNull(stats);
        assertEquals(5, stats.get("maxVersions"));
        assertEquals(1, stats.get("cachedMemoryIds"));
        assertEquals(1L, stats.get("totalCachedVersions"));
    }
}
