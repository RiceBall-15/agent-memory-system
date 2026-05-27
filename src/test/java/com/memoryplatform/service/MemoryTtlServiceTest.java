package com.memoryplatform.service;

import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryTtlService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MemoryTtlServiceTest {

    @Mock
    private MetadataStore metadataStore;

    private MemoryTtlService service;

    @BeforeEach
    void setUp() {
        service = new MemoryTtlService(metadataStore, 30, 300000, 100);
    }

    @Test
    void testDefaultConstructor_Uses30DaysDefault() {
        MemoryTtlService defaultService = new MemoryTtlService(metadataStore);
        Map<String, Object> stats = defaultService.getStats();
        assertEquals(30, stats.get("defaultTtlDays"));
    }

    @Test
    void testSetExpiration_Success() {
        Instant expireAt = Instant.now().plus(30, ChronoUnit.DAYS);
        when(metadataStore.update(eq("memories"), eq("mem-1"), anyMap()))
                .thenReturn(true);

        boolean result = service.setExpiration("mem-1", expireAt);

        assertTrue(result);
        verify(metadataStore).update(eq("memories"), eq("mem-1"), anyMap());
    }

    @Test
    void testSetExpiration_NullMemoryId() {
        boolean result = service.setExpiration(null, Instant.now());
        assertFalse(result, "null memoryId应返回false");
    }

    @Test
    void testSetExpiration_NullStore() {
        MemoryTtlService svc = new MemoryTtlService(null);
        boolean result = svc.setExpiration("mem-1", Instant.now());
        assertFalse(result, "null metadataStore应返回false");
    }

    @Test
    void testSetTtl_Success() {
        when(metadataStore.update(eq("memories"), eq("mem-1"), anyMap()))
                .thenReturn(true);

        boolean result = service.setTtl("mem-1", 15);

        assertTrue(result);
        verify(metadataStore).update(eq("memories"), eq("mem-1"), anyMap());
    }

    @Test
    void testSetDefaultTtl_StoresCorrectly() {
        service.setDefaultTtl("user:u1", 60);
        service.setDefaultTtl("agent:a1", 10);

        // user:u1的TTL应为60天
        assertEquals(60, service.getTtlDays("u1", null));
        // agent:a1的TTL应为10天
        assertEquals(10, service.getTtlDays("u1", "a1"));
    }

    @Test
    void testGetTtlDays_AgentOverridePriority() {
        service.setDefaultTtl("user:u1", 30);
        service.setDefaultTtl("agent:a1", 7);

        // agent级TTL优先于user级TTL
        assertEquals(7, service.getTtlDays("u1", "a1"));
    }

    @Test
    void testGetTtlDays_FallsBackToDefault() {
        // 无覆盖时返回默认值
        assertEquals(30, service.getTtlDays("unknown-user", null));
    }

    @Test
    void testAutoSetTtl_UsesCorrectTtl() {
        service.setDefaultTtl("user:u1", 45);
        when(metadataStore.update(eq("memories"), eq("mem-auto"), anyMap()))
                .thenReturn(true);

        Instant expireAt = service.autoSetTtl("mem-auto", "u1", null);

        assertNotNull(expireAt);
        // expireAt应在约45天后
        long daysDiff = ChronoUnit.DAYS.between(Instant.now(), expireAt);
        assertTrue(daysDiff >= 44 && daysDiff <= 46,
                "expireAt应在约45天后, 实际=" + daysDiff);
    }

    @Test
    void testIsExpired_NoExpireAt_ReturnsFalse() {
        MetadataRecord record = new MetadataRecord();
        record.setId("mem-1");
        record.setData(new HashMap<>()); // 无expireAt字段

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(record));

        assertFalse(service.isExpired("mem-1"), "未设置expireAt时不应过期");
    }

    @Test
    void testIsExpired_FutureExpireAt_ReturnsFalse() {
        MetadataRecord record = new MetadataRecord();
        record.setId("mem-1");
        Map<String, Object> data = new HashMap<>();
        data.put("expireAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        record.setData(data);

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(record));

        assertFalse(service.isExpired("mem-1"), "未到expireAt时间不应过期");
    }

    @Test
    void testIsExpired_PastExpireAt_ReturnsTrue() {
        MetadataRecord record = new MetadataRecord();
        record.setId("mem-1");
        Map<String, Object> data = new HashMap<>();
        data.put("expireAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        record.setData(data);

        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(List.of(record));

        assertTrue(service.isExpired("mem-1"), "已过期应返回true");
    }

    @Test
    void testScanAndCleanup_EmptyStore() {
        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        assertDoesNotThrow(() -> service.scanAndCleanup());
    }

    @Test
    void testGetStats_ReturnsCorrectKeys() {
        Map<String, Object> stats = service.getStats();
        assertNotNull(stats);
        assertTrue(stats.containsKey("scannedCount"));
        assertTrue(stats.containsKey("expiredCount"));
        assertTrue(stats.containsKey("cleanedCount"));
        assertTrue(stats.containsKey("defaultTtlDays"));
        assertTrue(stats.containsKey("running"));
        assertTrue(stats.containsKey("userTtlOverrides"));
    }

    @Test
    void testResetStats_ResetsCounters() {
        // 执行一次扫描以增加计数
        when(metadataStore.find(eq("memories"), anyMap(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        service.scanAndCleanup();

        service.resetStats();
        Map<String, Object> stats = service.getStats();
        assertEquals(0L, stats.get("scannedCount"));
        assertEquals(0L, stats.get("expiredCount"));
    }
}
