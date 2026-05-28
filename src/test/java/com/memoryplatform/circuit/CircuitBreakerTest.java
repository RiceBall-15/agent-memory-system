package com.memoryplatform.circuit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CircuitBreaker熔断器单元测试
 */
class CircuitBreakerTest {

    @Test
    void testInitialStateIsClosed() {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("test")
                .failureThreshold(3)
                .recoveryTimeout(1000)
                .successThreshold(2)
                .build();

        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals("test", cb.getName());
        assertEquals(3, cb.getFailureThreshold());
        assertEquals(1000, cb.getRecoveryTimeoutMs());
        assertEquals(2, cb.getSuccessThreshold());
    }

    @Test
    void testClosedToOpenOnFailures() throws Exception {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("test")
                .failureThreshold(3)
                .recoveryTimeout(5000)
                .successThreshold(2)
                .build();

        // 连续失败3次应触发熔断
        for (int i = 0; i < 3; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException e) {
                // expected
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // OPEN状态下应拒绝请求
        assertThrows(CircuitBreakedException.class, () ->
            cb.execute(() -> "should not reach")
        );
    }

    @Test
    void testSuccessResetsFailureCount() throws Exception {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("test")
                .failureThreshold(3)
                .recoveryTimeout(5000)
                .successThreshold(2)
                .build();

        // 失败2次（未达阈值）
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException e) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // 成功1次，重置失败计数
        cb.execute(() -> "ok");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // 再失败2次不应触发熔断（因为计数已被重置）
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException e) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testOpenToHalfOpenAfterTimeout() throws Exception {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("test")
                .failureThreshold(2)
                .recoveryTimeout(100) // 100ms超短超时
                .successThreshold(2)
                .build();

        // 触发熔断
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException e) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // 等待超时
        Thread.sleep(150);

        // 超时后应转为HALF_OPEN并执行
        String result = cb.execute(() -> "recovery");
        assertEquals("recovery", result);
        // HALF_OPEN下成功2次后应恢复为CLOSED
        cb.execute(() -> "ok2");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testHalfOpenFailureReopens() throws Exception {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("test")
                .failureThreshold(2)
                .recoveryTimeout(100)
                .successThreshold(3)
                .build();

        // 触发熔断
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException e) {
                // expected
            }
        }

        Thread.sleep(150);

        // HALF_OPEN下失败应重新打开
        try {
            cb.execute(() -> { throw new RuntimeException("half-open fail"); });
        } catch (RuntimeException e) {
            // expected
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void testStats() throws Exception {
        CircuitBreaker cb = new CircuitBreaker.Builder()
                .name("stats-test")
                .failureThreshold(2)
                .recoveryTimeout(5000)
                .successThreshold(2)
                .build();

        cb.execute(() -> "ok");
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException e) {
            // expected
        }

        CircuitBreaker.Stats stats = cb.getStats();
        assertEquals(CircuitBreaker.State.CLOSED, stats.state);
        assertEquals(1, stats.totalRequests);
        assertEquals(1, stats.totalFailures);
        assertEquals(0, stats.rejectedRequests);
    }

    @Test
    void testInvalidBuilderParams() {
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker.Builder().failureThreshold(0).build()
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker.Builder().recoveryTimeout(0).build()
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitBreaker.Builder().successThreshold(0).build()
        );
    }
}
