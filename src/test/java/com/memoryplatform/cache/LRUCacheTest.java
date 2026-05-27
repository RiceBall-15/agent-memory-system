package com.memoryplatform.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LRUCache 单元测试
 * 覆盖：put/get操作、TTL过期、LRU淘汰、容量限制、命中率统计、并发安全
 */
class LRUCacheTest {

    // ==================== 基本操作 ====================

    @Test
    @DisplayName("基本put/get操作")
    void basicPutGet() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    @DisplayName("get不存在的key返回null")
    void getNonExistent_returnsNull() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        assertNull(cache.get("nonexistent"));
    }

    @Test
    @DisplayName("覆盖已有key")
    void overwriteExistingKey() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        cache.put("key1", "value2");
        assertEquals("value2", cache.get("key1"));
    }

    @Test
    @DisplayName("remove操作")
    void removeOperation() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        String removed = cache.remove("key1");
        assertEquals("value1", removed);
        assertNull(cache.get("key1"));
    }

    @Test
    @DisplayName("remove不存在的key返回null")
    void removeNonExistent_returnsNull() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        assertNull(cache.remove("nonexistent"));
    }

    @Test
    @DisplayName("containsKey操作")
    void containsKeyOperation() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        assertTrue(cache.containsKey("key1"));
        assertFalse(cache.containsKey("key2"));
    }

    @Test
    @DisplayName("clear操作")
    void clearOperation() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("key1"));
    }

    @Test
    @DisplayName("size操作")
    void sizeOperation() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        assertEquals(0, cache.size());
        cache.put("key1", "value1");
        assertEquals(1, cache.size());
        cache.put("key2", "value2");
        assertEquals(2, cache.size());
    }

    // ==================== 容量限制与LRU淘汰 ====================

    @Test
    @DisplayName("容量限制 - 超出时淘汰最旧条目")
    void capacityLimit_evictsOldest() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        // 缓存已满，再添加一个应该淘汰"a"
        cache.put("d", "4");
        assertNull(cache.get("a"), "最先加入的'a'应该被淘汰");
        assertEquals(3, cache.size());
        // b, c, d 应该还在
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
        assertEquals("4", cache.get("d"));
    }

    @Test
    @DisplayName("LRU策略 - 最近访问的不被淘汰")
    void lruStrategy_recentAccessNotEvicted() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        // 访问"a"，使其变为最近使用
        cache.get("a");
        // 添加新条目，应该淘汰"b"（最久未访问）
        cache.put("d", "4");
        assertEquals("1", cache.get("a"), "最近访问的'a'应该保留");
        assertNull(cache.get("b"), "'b'应该被淘汰");
    }

    @Test
    @DisplayName("无效容量构造抛异常")
    void invalidCapacity_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<>(-1));
    }

    // ==================== TTL过期 ====================

    @Test
    @DisplayName("TTL过期 - 过期后返回null")
    void ttlExpiration_returnsNull() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10, 50); // 50ms TTL
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
        Thread.sleep(100);
        assertNull(cache.get("key1"), "过期后应返回null");
    }

    @Test
    @DisplayName("自定义TTL过期")
    void customTtlExpiration() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1", 50); // 50ms TTL
        cache.put("key2", "value2", 0); // 永不过期
        Thread.sleep(100);
        assertNull(cache.get("key1"), "自定义TTL过期后应返回null");
        assertEquals("value2", cache.get("key2"), "TTL=0应永不过期");
    }

    @Test
    @DisplayName("containsKey - 过期条目返回false")
    void containsKey_expiredReturnsFalse() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10, 50);
        cache.put("key1", "value1");
        assertTrue(cache.containsKey("key1"));
        Thread.sleep(100);
        assertFalse(cache.containsKey("key1"));
    }

    @Test
    @DisplayName("cleanup - 主动清理过期条目")
    void cleanup_removesExpired() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10, 50);
        cache.put("key1", "value1");
        cache.put("key2", "value2", 0); // 永不过期
        Thread.sleep(100);
        int cleaned = cache.cleanup();
        assertEquals(1, cleaned, "应清理1个过期条目");
        assertEquals(1, cache.size());
        assertEquals("value2", cache.get("key2"));
    }

    // ==================== 命中率统计 ====================

    @Test
    @DisplayName("命中率统计 - 正确计算")
    void hitRateStats() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");

        cache.get("key1"); // hit
        cache.get("key1"); // hit
        cache.get("key2"); // miss

        LRUCache.CacheStats stats = cache.getStats();
        assertEquals(2, stats.hitCount);
        assertEquals(1, stats.missCount);
        assertEquals(2.0 / 3.0, stats.hitRate, 0.001);
    }

    @Test
    @DisplayName("getHitRate - 空缓存返回0")
    void hitRate_emptyCache() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        assertEquals(0.0, cache.getHitRate());
    }

    @Test
    @DisplayName("resetStats - 重置统计")
    void resetStats() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("key1", "value1");
        cache.get("key1");
        cache.get("key2");
        cache.resetStats();
        LRUCache.CacheStats stats = cache.getStats();
        assertEquals(0, stats.hitCount);
        assertEquals(0, stats.missCount);
    }

    @Test
    @DisplayName("CacheStats toString")
    void cacheStats_toString() {
        LRUCache.CacheStats stats = new LRUCache.CacheStats(5, 10, 8, 2, 0.8, 1, 3);
        String str = stats.toString();
        assertTrue(str.contains("5/10"));
        assertTrue(str.contains("80.00%"));
    }

    // ==================== keySet / entries ====================

    @Test
    @DisplayName("keySet - 返回有效键集合")
    void keySet_returnsValidKeys() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("a", "1");
        cache.put("b", "2");
        Set<String> keys = cache.keySet();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
    }

    @Test
    @DisplayName("entries - 返回有效键值对")
    void entries_returnsValidEntries() {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.put("a", "1");
        cache.put("b", "2");
        Map<String, String> entries = cache.entries();
        assertEquals(2, entries.size());
        assertEquals("1", entries.get("a"));
        assertEquals("2", entries.get("b"));
    }

    @Test
    @DisplayName("keySet - 排除过期条目")
    void keySet_excludesExpired() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10, 50);
        cache.put("key1", "value1");
        cache.put("key2", "value2", 0);
        Thread.sleep(100);
        Set<String> keys = cache.keySet();
        assertEquals(1, keys.size());
        assertTrue(keys.contains("key2"));
    }

    // ==================== getEffectiveSize ====================

    @Test
    @DisplayName("getEffectiveSize - 不含过期条目")
    void getEffectiveSize_excludesExpired() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10, 50);
        cache.put("key1", "value1");
        cache.put("key2", "value2", 0);
        Thread.sleep(100);
        assertEquals(1, cache.getEffectiveSize());
    }

    // ==================== 并发安全 ====================

    @Test
    @DisplayName("并发读写 - 不抛异常")
    void concurrentReadWrite_noException() throws Exception {
        LRUCache<String, String> cache = new LRUCache<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);

        for (int t = 0; t < 10; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        String key = "thread" + threadId + "_key" + i;
                        cache.put(key, "value" + i);
                        cache.get(key);
                        cache.size();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        // 不抛异常即为通过
        assertTrue(cache.size() <= 100);
    }
}
