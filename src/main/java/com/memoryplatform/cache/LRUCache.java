package com.memoryplatform.cache;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用LRU缓存实现（无外部依赖）
 *
 * <p>基于LinkedHashMap实现的线程安全LRU缓存，支持TTL过期、容量限制和命中率统计。</p>
 *
 * <h3>设计特性</h3>
 * <ul>
 *   <li>使用LinkedHashMap实现LRU淘汰策略</li>
 *   <li>使用synchronized保证线程安全</li>
 *   <li>每条目独立TTL过期时间</li>
 *   <li>最大容量限制，超出时淘汰最久未访问的条目</li>
 *   <li>缓存命中率统计（命中数/总访问数）</li>
 *   <li>自动清理过期条目（惰性清理 + 主动清理）</li>
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
 * System.out.println(cache.getStats());
 * }</pre>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author Agent Memory Platform
 */
public class LRUCache<K, V> {

    // ==================== 缓存条目 ====================

    /**
     * 缓存条目，包装值和过期时间
     */
    private static class CacheEntry<V> {
        final V value;
        /** 过期时间戳（毫秒），0表示永不过期 */
        final long expireAt;

        CacheEntry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        /**
         * 检查条目是否已过期
         */
        boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }
    }

    // ==================== 核心数据结构 ====================

    /** LRU映射表（accessOrder=true实现LRU） */
    private final LinkedHashMap<K, CacheEntry<V>> map;

    /** 最大容量 */
    private final int maxSize;

    /** 默认TTL（毫秒），0表示永不过期 */
    private final long defaultTtlMs;

    // ==================== 统计计数器 ====================

    /** 命中次数 */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** 未命中次数 */
    private final AtomicLong missCount = new AtomicLong(0);

    /** 总淘汰次数 */
    private final AtomicLong evictionCount = new AtomicLong(0);

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

        // accessOrder=true: 每次get/put都会将条目移到链表尾部，实现LRU
        this.map = new LinkedHashMap<>(maxSize, 0.75f, true);
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
        synchronized (map) {
            CacheEntry<V> entry = map.get(key);

            if (entry == null) {
                // 未命中
                missCount.incrementAndGet();
                return null;
            }

            // 检查是否过期
            if (entry.isExpired()) {
                // 过期，移除
                map.remove(key);
                expirationCount.incrementAndGet();
                missCount.incrementAndGet();
                return null;
            }

            // 命中
            hitCount.incrementAndGet();
            return entry.value;
        }
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
     * @param key     缓存键
     * @param value   缓存值
     * @param ttlMs   TTL（毫秒），0表示永不过期
     */
    public void put(K key, V value, long ttlMs) {
        synchronized (map) {
            long expireAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0;
            CacheEntry<V> newEntry = new CacheEntry<>(value, expireAt);

            // 如果key已存在，先移除旧条目
            CacheEntry<V> oldEntry = map.remove(key);

            // 尝试放入
            map.put(key, newEntry);

            // 检查是否超出容量
            while (map.size() > maxSize) {
                // 移除最老的条目（LRU头部）
                Iterator<K> it = map.keySet().iterator();
                if (it.hasNext()) {
                    K oldestKey = it.next();
                    it.remove();
                    evictionCount.incrementAndGet();
                }
            }
        }
    }

    /**
     * 移除缓存条目
     *
     * @param key 缓存键
     * @return 被移除的值，不存在返回null
     */
    public V remove(K key) {
        synchronized (map) {
            CacheEntry<V> entry = map.remove(key);
            return entry != null ? entry.value : null;
        }
    }

    /**
     * 检查缓存中是否包含指定key
     *
     * @param key 缓存键
     * @return 如果存在且未过期返回true
     */
    public boolean containsKey(K key) {
        synchronized (map) {
            CacheEntry<V> entry = map.get(key);
            if (entry == null) return false;
            if (entry.isExpired()) {
                map.remove(key);
                expirationCount.incrementAndGet();
                return false;
            }
            return true;
        }
    }

    /**
     * 获取缓存大小（包含可能的过期条目）
     *
     * @return 缓存大小
     */
    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    /**
     * 清空缓存
     */
    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }

    /**
     * 获取所有有效的键集合（不含过期条目）
     *
     * @return 键集合
     */
    public Set<K> keySet() {
        synchronized (map) {
            Set<K> validKeys = new LinkedHashSet<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<K, CacheEntry<V>> entry : map.entrySet()) {
                if (entry.getValue().expireAt <= 0 || now <= entry.getValue().expireAt) {
                    validKeys.add(entry.getKey());
                }
            }
            return validKeys;
        }
    }

    /**
     * 获取所有有效的键值对（不含过期条目）
     *
     * @return 键值对Map
     */
    public Map<K, V> entries() {
        synchronized (map) {
            Map<K, V> result = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<K, CacheEntry<V>> entry : map.entrySet()) {
                CacheEntry<V> cacheEntry = entry.getValue();
                if (cacheEntry.expireAt <= 0 || now <= cacheEntry.expireAt) {
                    result.put(entry.getKey(), cacheEntry.value);
                }
            }
            return result;
        }
    }

    // ==================== 过期清理 ====================

    /**
     * 主动清理过期条目
     *
     * <p>遍历所有条目，移除已过期的。适用于缓存较大且过期条目较多的场景。</p>
     *
     * @return 被清理的条目数
     */
    public int cleanup() {
        synchronized (map) {
            int cleaned = 0;
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<K, CacheEntry<V>> entry = it.next();
                if (entry.getValue().isExpired()) {
                    it.remove();
                    cleaned++;
                }
            }
            expirationCount.addAndGet(cleaned);
            return cleaned;
        }
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
        synchronized (map) {
            long hits = hitCount.get();
            long misses = missCount.get();
            long total = hits + misses;
            double rate = total > 0 ? (double) hits / total : 0.0;

            // 清理过期条目以获取准确大小
            int currentSize = 0;
            long now = System.currentTimeMillis();
            for (CacheEntry<V> entry : map.values()) {
                if (entry.expireAt <= 0 || now <= entry.expireAt) {
                    currentSize++;
                }
            }

            return new CacheStats(
                    currentSize, maxSize, hits, misses, rate,
                    evictionCount.get(), expirationCount.get()
            );
        }
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
        synchronized (map) {
            int count = 0;
            long now = System.currentTimeMillis();
            for (CacheEntry<V> entry : map.values()) {
                if (entry.expireAt <= 0 || now <= entry.expireAt) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        hitCount.set(0);
        missCount.set(0);
        evictionCount.set(0);
        expirationCount.set(0);
    }
}
