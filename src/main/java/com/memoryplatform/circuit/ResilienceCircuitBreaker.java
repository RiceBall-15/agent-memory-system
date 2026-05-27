package com.memoryplatform.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 基于Resilience4j的熔断器适配层
 *
 * <p>提供与旧版 {@link CircuitBreaker} 相同的 execute(Callable) API,
 * 但内部使用Resilience4j的工业级熔断器实现。</p>
 *
 * <p>优势:</p>
 * <ul>
 *   <li>滑动窗口统计 (COUNT_BASED / TIME_BASED)</li>
 *   <li>失败率/慢调用率阈值</li>
 *   <li>自动Micrometer指标集成</li>
 *   <li>可配置的半开状态许可请求数</li>
 * </ul>
 *
 * @see CircuitBreakerRegistry
 */
@Slf4j
@Component
public class ResilienceCircuitBreaker {

    private final CircuitBreakerRegistry registry;

    public ResilienceCircuitBreaker(CircuitBreakerRegistry registry) {
        this.registry = registry;
        log.info("[ResilienceCircuitBreaker] 初始化完成, 已注册实例: {}",
                registry.getAllCircuitBreakers().stream()
                        .map(io.github.resilience4j.circuitbreaker.CircuitBreaker::getName)
                        .toList());
    }

    /**
     * 执行被熔断器保护的操作 (与旧API兼容)
     *
     * @param name     熔断器实例名 (对应application.yml中resilience4j.circuitbreaker.instances的key)
     * @param callable 要执行的操作
     * @param <T>      返回值类型
     * @return 操作结果
     * @throws Exception 当熔断器打开时抛出 CircuitBreakedException
     */
    public <T> T execute(String name, Callable<T> callable) throws Exception {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = registry.circuitBreaker(name);

        try {
            return io.github.resilience4j.circuitbreaker.CircuitBreaker
                    .decorateCallable(cb, callable).call();
        } catch (io.github.resilience4j.circuitbreaker.CircuitBreakerOpenException e) {
            Metrics metrics = cb.getMetrics();
            String msg = String.format(
                    "[ResilienceCircuitBreaker-%s] 熔断器已打开, 拒绝请求! "
                            + "失败率=%.1f%%, 成功=%d, 失败=%d, 被拒=%d",
                    name,
                    metrics.getFailureRate(),
                    metrics.getNumberOfSuccessfulCalls(),
                    metrics.getNumberOfFailedCalls(),
                    metrics.getNumberOfNotPermittedCalls());
            log.error(msg);
            throw new CircuitBreakedException(msg, e);
        }
    }

    /**
     * 获取指定熔断器实例的状态
     *
     * @param name 熔断器实例名
     * @return 状态字符串 (CLOSED/OPEN/HALF_OPEN)
     */
    public String getState(String name) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = registry.circuitBreaker(name);
        return cb.getState().name();
    }

    /**
     * 获取指定熔断器的统计信息
     *
     * @param name 熔断器实例名
     * @return Stats对象
     */
    public Stats getStats(String name) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = registry.circuitBreaker(name);
        Metrics m = cb.getMetrics();
        return new Stats(
                cb.getState().name(),
                m.getFailureRate(),
                m.getNumberOfSuccessfulCalls(),
                m.getNumberOfFailedCalls(),
                m.getNumberOfNotPermittedCalls(),
                m.getNumberOfBufferedCalls()
        );
    }

    /**
     * 熔断器统计信息 (兼容旧版Stats接口)
     */
    public static class Stats {
        public final String state;
        public final double failureRate;
        public final long successes;
        public final long failures;
        public final long rejected;
        public final long totalCalls;

        public Stats(String state, double failureRate, long successes,
                     long failures, long rejected, long totalCalls) {
            this.state = state;
            this.failureRate = failureRate;
            this.successes = successes;
            this.failures = failures;
            this.rejected = rejected;
            this.totalCalls = totalCalls;
        }

        @Override
        public String toString() {
            return String.format(
                    "Resilience4jStats{state=%s, failureRate=%.1f%%, successes=%d, failures=%d, rejected=%d, total=%d}",
                    state, failureRate, successes, failures, rejected, totalCalls);
        }
    }
}
