package com.memoryplatform.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于令牌桶算法的限流器
 * <p>
 * 每个用户独立限流，默认每秒产生10个令牌，突发容量20。
 * 当令牌不足时拒绝请求，返回429 Too Many Requests。
 * </p>
 * <p>
 * 令牌桶算法原理：
 * <ul>
 *   <li>桶以固定速率（permitsPerSecond）持续产生令牌</li>
 *   <li>桶最多容纳burstCapacity个令牌</li>
 *   <li>每次请求消耗一个令牌</li>
 *   <li>如果令牌不足，请求被拒绝</li>
 * </ul>
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
public class RateLimiter {

    /** 每个用户独立的令牌桶 */
    private final ConcurrentHashMap<String, TokenBucket> buckets;

    /** 每秒产生的令牌数（稳态速率） */
    private final double permitsPerSecond;

    /** 桶的最大容量（支持突发） */
    private final int burstCapacity;

    /**
     * 创建限流器（默认配置：每秒10个请求，突发容量20）
     */
    public RateLimiter() {
        this(10.0, 20);
    }

    /**
     * 创建限流器
     *
     * @param permitsPerSecond 每秒产生的令牌数
     * @param burstCapacity 桶的最大容量
     */
    public RateLimiter(double permitsPerSecond, int burstCapacity) {
        this.buckets = new ConcurrentHashMap<>();
        this.permitsPerSecond = permitsPerSecond;
        this.burstCapacity = burstCapacity;
    }

    /**
     * 尝试获取一个令牌
     * <p>
     * 如果用户桶不存在则自动创建。令牌不足时返回false。
     * </p>
     *
     * @param key 限流键（通常是userId或IP地址）
     * @return true表示允许，false表示被限流
     */
    public boolean tryAcquire(String key) {
        TokenBucket bucket = buckets.computeIfAbsent(key,
            k -> new TokenBucket(burstCapacity, permitsPerSecond));
        return bucket.tryAcquire();
    }

    /**
     * 获取剩余令牌数（用于调试和监控）
     *
     * @param key 限流键
     * @return 剩余令牌数，如果桶不存在返回-1
     */
    public double getAvailableTokens(String key) {
        TokenBucket bucket = buckets.get(key);
        return bucket != null ? bucket.getAvailableTokens() : -1;
    }

    /**
     * 获取稳态速率
     */
    public double getPermitsPerSecond() {
        return permitsPerSecond;
    }

    /**
     * 获取突发容量
     */
    public int getBurstCapacity() {
        return burstCapacity;
    }

    /**
     * 获取当前跟踪的用户数
     */
    public int getTrackedUsersCount() {
        return buckets.size();
    }

    /**
     * 移除指定用户的桶（重置限流状态）
     *
     * @param key 限流键
     */
    public void removeBucket(String key) {
        buckets.remove(key);
    }

    /**
     * 清空所有用户桶
     */
    public void clearAll() {
        buckets.clear();
    }

    /**
     * 令牌桶实现
     */
    private static class TokenBucket {

        /** 桶的最大容量 */
        private final int capacity;

        /** 每秒产生的令牌数 */
        private final double refillRate;

        /** 当前可用令牌数 */
        private double availableTokens;

        /** 上次补充令牌的时间戳（纳秒） */
        private long lastRefillTime;

        TokenBucket(int capacity, double refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.availableTokens = capacity; // 初始时桶是满的
            this.lastRefillTime = System.nanoTime();
        }

        /**
         * 尝试获取一个令牌
         *
         * @return true表示获取成功，false表示令牌不足
         */
        synchronized boolean tryAcquire() {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }

        /**
         * 获取当前可用令牌数
         */
        synchronized double getAvailableTokens() {
            refill();
            return availableTokens;
        }

        /**
         * 根据时间流逝补充令牌
         */
        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0;

            if (elapsedSeconds > 0) {
                double tokensToAdd = elapsedSeconds * refillRate;
                availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
}
