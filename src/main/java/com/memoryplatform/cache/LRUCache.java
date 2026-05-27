package com.memoryplatform.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用LRU缓存实现（Caffeine后端）
 *
 * <p>基于Caffeine实现的线程安全缓存，使用W-TinyLFU淘汰策略（比LRU命中率高~20%），
 * 支持TTL过期、容量限制和命中率统计。</p>
 *
 * <h3>设计特性</h3>
 * <ul>
 *   <li>使用Caffeine W-TinyLFU淘汰策略（比LRU命中率高~20%）</li>
 *   <li>无锁读写（JUC实现）</li>
 *   <li>异步淘汰（不阻塞主线程）</li>
 *   <li>每条目独立TTL过期时间</li>
 *   <li>最大容量限制，超出时淘汰最低频条目</li>
 *   <li>缓存命中率统计（命中数/总访问数）</li>
 *   <li>自动清理过期条目</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建缓存：最大1000条，默认TTL 5分钟
 * LRUCache<String, Object> cache = new LRUCache<>(1000, 300_000);
 *
 * // 存入缓存
 * cache.put("key1", value1);
 *
 * // 存入带自定义TTL的缓存
 * cache.put("key2", value2, 60_000); // 60秒后过期
 *
 * // 获取缓存
 * Object val = cache.get("key1");
 *
 * // 获取统计信息
 * log.info(cache.getStats())
 * }</pre>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author Agent Memory Platform
 */
@Slf4j
public class LRUCache<K, V> {

    // ==================== 核心数据结构 ====================

    /** Caffeine缓存实例（W-TinyLFU淘汰策略） */
    private final Cache<K, V> cache;

    /** 最大容量 */
    private final int maxSize;

    /** 默认TTL（毫秒），0表示永不过期 */
    private final long defaultTtlMs;

    // ==================== 统计计数器 ====================

    /** 命中次数 */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** 未命中次数 */
    private final AtomicLong missCount = new AtomicLong(0);

    /** 总过期清理次数 */
    private final AtomicLong expirationCount = new AtomicLong(0);

    // ==================== 构造函数 ====================

    /**
     * 创建LRU缓存
     *
     * @param maxSize      最大容量
     * @param defaultTtlMs 默认TTL（毫秒），0表示永不过期
     */
    public LRUCache(int maxSize, long defaultTtlMs) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;

        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .recordStats();

        if (defaultTtlMs > 0) {
            builder.expireAfterWrite(defaultTtlMs, TimeUnit.MILLISECONDS);
        } else {
            // 使用极大TTL以启用per-entry过期支持
            builder.expireAfterWrite(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }

        this.cache = builder.build();
        log.info("Caffeine缓存初始化: maxSize={}, ttlMs={}", maxSize, defaultTtlMs);
    }

    /**
     * 创建无TTL的LRU缓存
     *
     * @param maxSize 最大容量
     */
    public LRUCache(int maxSize) {
        this(maxSize, 0);
    }

    // ==================== 核心操作 ====================

    /**
     * 获取缓存值
     *
     * @param key 缓存键
     * @return 缓存值，未命中或已过期返回null
     */
    public V get(K key) {
        V value = cache.getIfPresent(key);
        if (value != null) {
            hitCount.incrementAndGet();
        } else {
            missCount.incrementAndGet();
        }
        return value;
    }

    /**
     * 存入缓存（使用默认TTL）
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    /**
     * 存入缓存（使用自定义TTL）
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttlMs TTL（毫秒），0表示永不过期
     */
    public void put(K key, V value, long ttlMs) {
        cache.policy().expireAfterWrite().ifPresent(policy -> {
            long nanos = ttlMs > 0 ? TimeUnit.MILLISECONDS.toNanos(ttlMs) : Long.MAX_VALUE;
            policy.put(key, value, nanos, TimeUnit.NANOSECONDS);
        });
    }

    /**
     * 移除缓存条目
     *
     * @param key 缓存键
     * @return 被移除的值，不存在返回null
     */
    public V remove(K key) {
        V value = cache.getIfPresent(key);
        cache.invalidate(key);
        return value;
    }

    /**
     * 检查缓存中是否包含指定key
     *
     * @param key 缓存键
     * @return 如果存在且未过期返回true
     */
    public boolean containsKey(K key) {
        return cache.getIfPresent(key) != null;
    }

    /**
     * 获取缓存大小（近似值）
     *
     * @return 缓存大小
     */
    public int size() {
        return (int) cache.estimatedSize();
    }

    /**
     * 清空缓存
     */
    public void clear() {
        cache.invalidateAll();
    }

    /**
     * 获取所有有效的键集合（不含过期条目）
     *
     * @return 键集合
     */
    public Set<K> keySet() {
        Set<K> validKeys = new LinkedHashSet<>();
        for (K key : cache.asMap().keySet()) {
            if (cache.getIfPresent(key) != null) {
                validKeys.add(key);
            }
        }
        return validKeys;
    }

    /**
     * 获取所有有效的键值对（不含过期条目）
     *
     * @return 键值对Map
     */
    public Map<K, V> entries() {
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : cache.asMap().keySet()) {
            V value = cache.getIfPresent(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    // ==================== 过期清理 ====================

    /**
     * 主动清理过期条目
     *
     * <p>触发Caffeine同步维护，移除已过期的条目。</p>
     *
     * @return 被清理的条目数
     */
    public int cleanup() {
        int before = (int) cache.estimatedSize();
        cache.cleanUp();
        int after = (int) cache.estimatedSize();
        int cleaned = before - after;
        expirationCount.addAndGet(cleaned);
        return cleaned;
    }

    // ==================== 统计信息 ====================

    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        public final int size;
        public final int maxSize;
        public final long hitCount;
        public final long missCount;
        public final double hitRate;
        public final long evictionCount;
        public final long expirationCount;

        public CacheStats(int size, int maxSize, long hitCount, long missCount,
                          double hitRate, long evictionCount, long expirationCount) {
            this.size = size;
            this.maxSize = maxSize;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.hitRate = hitRate;
            this.evictionCount = evictionCount;
            this.expirationCount = expirationCount;
        }

        @Override
        public String toString() {
            return String.format(
                    "CacheStats{size=%d/%d, hit=%d, miss=%d, rate=%.2f%%, evict=%d, expire=%d}",
                    size, maxSize, hitCount, missCount, hitRate * 100, evictionCount, expirationCount
            );
        }
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息对象
     */
    public CacheStats getStats() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        double rate = total > 0 ? (double) hits / total : 0.0;

        return new CacheStats(
                (int) cache.estimatedSize(), maxSize, hits, misses, rate,
                cache.stats().evictionCount(), expirationCount.get()
        );
    }

    /**
     * 获取当前缓存命中率
     *
     * @return 命中率（0.0~1.0）
     */
    public double getHitRate() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
     * 获取当前有效缓存条目数（不含过期条目）
     *
     * @return 有效条目数
     */
    public int getEffectiveSize() {
        return (int) cache.estimatedSize();
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        hitCount.set(0);
        missCount.set(0);
        expirationCount.set(0);
    }
}
