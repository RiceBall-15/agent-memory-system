package com.memoryplatform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimiter 单元测试
 * 覆盖：令牌桶算法、速率限制、突发容量、多用户独立、并发安全
 */
class RateLimiterTest {

    @Test
    @DisplayName("默认构造 - 每秒10令牌，突发容量20")
    void defaultConstructor() {
        RateLimiter limiter = new RateLimiter();
        assertEquals(10.0, limiter.getPermitsPerSecond());
        assertEquals(20, limiter.getBurstCapacity());
    }

    @Test
    @DisplayName("自定义构造参数")
    void customConstructor() {
        RateLimiter limiter = new RateLimiter(5.0, 10);
        assertEquals(5.0, limiter.getPermitsPerSecond());
        assertEquals(10, limiter.getBurstCapacity());
    }

    @Test
    @DisplayName("正常速率请求 - 应通过")
    void normalRateRequests_shouldPass() {
        RateLimiter limiter = new RateLimiter(10.0, 20);
        // 发送20个请求（突发容量），应该全部通过
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire("user1"),
                    "请求 " + (i + 1) + " 应该通过");
        }
    }

    @Test
    @DisplayName("超过突发容量 - 应拒绝")
    void exceedBurstCapacity_shouldReject() {
        RateLimiter limiter = new RateLimiter(10.0, 20);
        // 先消耗所有令牌
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("user1");
        }
        // 下一个请求应该被拒绝
        assertFalse(limiter.tryAcquire("user1"),
                "超过突发容量后应被拒绝");
    }

    @Test
    @DisplayName("令牌补充 - 等待后应恢复")
    void tokenRefill_shouldRecover() throws InterruptedException {
        // 设置很小的突发容量以便测试
        RateLimiter limiter = new RateLimiter(100.0, 5);
        String key = "user_refill";

        // 消耗所有令牌
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(key));
        }
        assertFalse(limiter.tryAcquire(key));

        // 等待100ms，应该补充约10个令牌（100 * 0.1 = 10），但桶容量为5
        Thread.sleep(150);

        assertTrue(limiter.tryAcquire(key),
                "等待后应能获取令牌");
    }

    @Test
    @DisplayName("多用户独立限流")
    void multipleUsers_independent() {
        RateLimiter limiter = new RateLimiter(10.0, 3);

        // user1 消耗所有令牌
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("user1"));
        }
        assertFalse(limiter.tryAcquire("user1"));

        // user2 独立限流，应该仍有令牌
        assertTrue(limiter.tryAcquire("user2"),
                "不同用户的令牌桶应该是独立的");
    }

    @Test
    @DisplayName("获取剩余令牌数 - 不存在的key返回-1")
    void getAvailableTokens_nonExistentKey() {
        RateLimiter limiter = new RateLimiter();
        assertEquals(-1.0, limiter.getAvailableTokens("non_existent"));
    }

    @Test
    @DisplayName("获取剩余令牌数 - 存在的key返回正值")
    void getAvailableTokens_existingKey() {
        RateLimiter limiter = new RateLimiter(10.0, 10);
        limiter.tryAcquire("user1");
        double tokens = limiter.getAvailableTokens("user1");
        assertTrue(tokens >= 0 && tokens <= 10,
                "剩余令牌数应在0到容量之间，实际: " + tokens);
    }

    @Test
    @DisplayName("移除桶后重新限流")
    void removeBucket_shouldReset() {
        RateLimiter limiter = new RateLimiter(10.0, 3);
        String key = "user_reset";

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(key);
        }
        assertFalse(limiter.tryAcquire(key));

        // 移除桶
        limiter.removeBucket(key);

        // 重新获取应该有完整的令牌
        assertTrue(limiter.tryAcquire(key),
                "移除桶后应重新创建并拥有完整令牌");
    }

    @Test
    @DisplayName("清空所有桶")
    void clearAll() {
        RateLimiter limiter = new RateLimiter(10.0, 3);
        limiter.tryAcquire("user1");
        limiter.tryAcquire("user2");
        assertEquals(2, limiter.getTrackedUsersCount());

        limiter.clearAll();
        assertEquals(0, limiter.getTrackedUsersCount());
    }

    @Test
    @DisplayName("并发请求 - 线程安全")
    void concurrentRequests_threadSafe() throws Exception {
        RateLimiter limiter = new RateLimiter(10.0, 20);
        int threadCount = 10;
        int requestsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final String key = "concurrent_user_" + t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.tryAcquire(key)) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // 总请求 = threadCount * requestsPerThread = 50
        // 每个用户有20个令牌，但只请求5个，所以应该全部成功
        assertEquals(threadCount * requestsPerThread, successCount.get(),
                "每个用户只请求5个，应全部成功");
        assertEquals(0, failCount.get());
    }
}
