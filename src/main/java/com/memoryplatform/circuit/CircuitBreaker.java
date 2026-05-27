package com.memoryplatform.circuit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

/**
 * 熔断器 - 防止级联故障的保护机制
 * <p>
 * 实现了经典熔断器模式, 包含三个状态:
 * <ul>
 *   <li><b>CLOSED</b>: 正常状态, 计数失败次数, 超过阈值则打开</li>
 *   <li><b>OPEN</b>: 熔断状态, 直接拒绝请求返回降级结果, 超时后进入半开</li>
 *   <li><b>HALF_OPEN</b>: 试探状态, 放行少量请求, 连续成功则关闭, 失败则重新打开</li>
 * </ul>
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CircuitBreaker cb = new CircuitBreaker.Builder()
 *     .failureThreshold(5)
 *     .recoveryTimeout(30000)
 *     .successThreshold(3)
 *     .name("vector-store")
 *     .build();
 *
 * String result = cb.execute(() -> vectorStore.upsert("memories", records));
 * }</pre>
 *
 * <h3>状态转换图</h3>
 * <pre>
 *   CLOSED --(failures >= threshold)--> OPEN --(timeout elapsed)--> HALF_OPEN
 *     ^                                                           |
 *     |                    (successes >= threshold)                |
 *     +-----------------------------------------------------------+
 *     |                    (any failure)                           |
 *     +<-- HALF_OPEN --(failure)--> OPEN
 * </pre>
 *
 * @see ConcurrentWriteService
 */
public class CircuitBreaker {

    /**
     * 熔断器状态枚举
     */
    public enum State {
        /** 正常: 允许所有请求通过 */
        CLOSED,
        /** 熔断: 拒绝所有请求, 返回降级结果 */
        OPEN,
        /** 试探: 放行少量请求测试下游恢复情况 */
        HALF_OPEN
    }

    /** 熔断器名称 (用于日志和监控) */
    private final String name;

    /** 失败阈值: 连续失败多少次后触发熔断 (默认5) */
    private final int failureThreshold;

    /** 恢复超时: 熔断后多久尝试恢复, 单位毫秒 (默认30000ms) */
    private final long recoveryTimeoutMs;

    /** 成功阈值: HALF_OPEN状态下连续成功多少次后关闭熔断 (默认3) */
    private final int successThreshold;

    /** 当前状态 */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /** 当前连续失败计数 (CLOSED状态下累计) */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 当前连续成功计数 (HALF_OPEN状态下累计) */
    private final AtomicInteger successCount = new AtomicInteger(0);

    /** 熔断打开的时间戳 */
    private final AtomicLong openedAt = new AtomicLong(0);

    /** 统计: 总请求数 */
    private final AtomicLong totalRequests = new AtomicLong(0);

    /** 统计: 总失败数 */
    private final AtomicLong totalFailures = new AtomicLong(0);

    /** 统计: 被熔断拒绝的请求数 */
    private final AtomicLong rejectedRequests = new AtomicLong(0);

    private CircuitBreaker(Builder builder) {
        this.name = builder.name;
        this.failureThreshold = builder.failureThreshold;
        this.recoveryTimeoutMs = builder.recoveryTimeoutMs;
        this.successThreshold = builder.successThreshold;
    }

    /**
     * 执行被熔断器保护的操作
     * <p>
     * 根据当前熔断器状态决定是否执行操作:
     * <ul>
     *   <li>CLOSED: 直接执行, 记录结果</li>
     *   <li>OPEN: 判断是否超时, 超时则转为HALF_OPEN并执行, 否则直接抛出熔断异常</li>
     *   <li>HALF_OPEN: 执行操作, 根据结果更新状态</li>
     * </ul>
     * </p>
     *
     * @param <T> 返回值类型
     * @param supplier 要执行的操作
     * @return 操作结果
     * @throws CircuitBreakedException 熔断器打开时抛出
     * @throws Exception 操作本身抛出的异常
     */
    public <T> T execute(Callable<T> supplier) throws Exception {
        totalRequests.incrementAndGet();

        State currentState = state.get();
        System.out.println("[CircuitBreaker-" + name + "] 状态=" + currentState + ", 执行请求...");

        switch (currentState) {
            case CLOSED:
                return executeClosed(supplier);

            case OPEN:
                return executeOpen(supplier);

            case HALF_OPEN:
                return executeHalfOpen(supplier);

            default:
                throw new IllegalStateException("未知熔断器状态: " + currentState);
        }
    }

    /**
     * CLOSED状态执行: 直接放行, 成功则重置计数, 失败则累加计数
     */
    private <T> T executeClosed(Callable<T> supplier) throws Exception {
        try {
            T result = supplier.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * OPEN状态执行: 判断是否超过恢复超时, 超时则转为HALF_OPEN试探
     */
    private <T> T executeOpen(Callable<T> supplier) throws Exception {
        long now = System.currentTimeMillis();
        long elapsed = now - openedAt.get();

        if (elapsed >= recoveryTimeoutMs) {
            System.out.println("[CircuitBreaker-" + name + "] 超时已过(" + elapsed + "ms), "
                    + "尝试转为HALF_OPEN试探...");
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                successCount.set(0);
                System.out.println("[CircuitBreaker-" + name + "] 已转为HALF_OPEN状态");
                return executeHalfOpen(supplier);
            }
            // CAS失败说明其他线程已转换, 继续按OPEN处理
        }

        rejectedRequests.incrementAndGet();
        String msg = String.format("[CircuitBreaker-%s] 熔断器已打开, 拒绝请求! "
                + "已开启%dms (超时=%dms), 已拒绝%d个请求",
                name, elapsed, recoveryTimeoutMs, rejectedRequests.get());
        System.out.println(msg);
        throw new CircuitBreakedException(msg);
    }

    /**
     * HALF_OPEN状态执行: 放行请求试探, 连续成功达阈值则关闭熔断
     */
    private <T> T executeHalfOpen(Callable<T> supplier) throws Exception {
        try {
            T result = supplier.call();
            onHalfOpenSuccess();
            return result;
        } catch (Exception e) {
            onHalfOpenFailure();
            throw e;
        }
    }

    /**
     * CLOSED/HALF_OPEN状态成功回调
     */
    private void onSuccess() {
        failureCount.set(0);
        System.out.println("[CircuitBreaker-" + name + "] 操作成功, 失败计数已重置");
    }

    /**
     * CLOSED状态失败回调: 失败计数累加, 达到阈值则打开熔断
     */
    private void onFailure() {
        int failures = failureCount.incrementAndGet();
        totalFailures.incrementAndGet();
        System.out.println("[CircuitBreaker-" + name + "] 操作失败, 当前连续失败=" + failures
                + "/" + failureThreshold);

        if (failures >= failureThreshold) {
            trip();
        }
    }

    /**
     * HALF_OPEN状态成功回调: 成功计数累加, 达到阈值则关闭熔断
     */
    private void onHalfOpenSuccess() {
        int successes = successCount.incrementAndGet();
        System.out.println("[CircuitBreaker-" + name + "] 试探成功, 当前连续成功="
                + successes + "/" + successThreshold);

        if (successes >= successThreshold) {
            reset();
        }
    }

    /**
     * HALF_OPEN状态失败回调: 重新打开熔断
     */
    private void onHalfOpenFailure() {
        totalFailures.incrementAndGet();
        System.out.println("[CircuitBreaker-" + name + "] 试探失败, 重新打开熔断器");
        trip();
    }

    /**
     * 触发熔断: 从CLOSED或HALF_OPEN转为OPEN
     */
    private void trip() {
        state.set(State.OPEN);
        openedAt.set(System.currentTimeMillis());
        successCount.set(0);
        System.out.println("[CircuitBreaker-" + name + "] !!! 熔断器已打开 !!! "
                + "将在" + recoveryTimeoutMs + "ms后尝试恢复");
    }

    /**
     * 关闭熔断: 从HALF_OPEN转回CLOSED
     */
    private void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        System.out.println("[CircuitBreaker-" + name + "] 熔断器已恢复关闭, 服务恢复正常");
    }

    // ============ Getters / 监控 ============

    public String getName() { return name; }
    public State getState() { return state.get(); }
    public int getFailureThreshold() { return failureThreshold; }
    public long getRecoveryTimeoutMs() { return recoveryTimeoutMs; }
    public int getSuccessThreshold() { return successThreshold; }

    /** 获取当前统计快照 */
    public Stats getStats() {
        return new Stats(
                state.get(),
                failureCount.get(),
                successCount.get(),
                totalRequests.get(),
                totalFailures.get(),
                rejectedRequests.get()
        );
    }

    /**
     * 熔断器统计信息
     */
    public static class Stats {
        public final State state;
        public final int currentFailures;
        public final int currentSuccesses;
        public final long totalRequests;
        public final long totalFailures;
        public final long rejectedRequests;

        public Stats(State state, int currentFailures, int currentSuccesses,
                     long totalRequests, long totalFailures, long rejectedRequests) {
            this.state = state;
            this.currentFailures = currentFailures;
            this.currentSuccesses = currentSuccesses;
            this.totalRequests = totalRequests;
            this.totalFailures = totalFailures;
            this.rejectedRequests = rejectedRequests;
        }

        @Override
        public String toString() {
            return String.format("CircuitBreakerStats{state=%s, failures=%d/%d, successes=%d/%d, "
                            + "total=%d, failed=%d, rejected=%d}",
                    state, currentFailures, 0, currentSuccesses, 0,
                    totalRequests, totalFailures, rejectedRequests);
        }
    }

    /**
     * 熔断器构建器
     * <p>
     * 使用Builder模式配置熔断器参数:
     * <pre>{@code
     * CircuitBreaker cb = new CircuitBreaker.Builder()
     *     .name("my-breaker")
     *     .failureThreshold(5)
     *     .recoveryTimeout(30000)
     *     .successThreshold(3)
     *     .build();
     * }</pre>
     * </p>
     */
    public static class Builder {
        private String name = "default";
        private int failureThreshold = 5;
        private long recoveryTimeoutMs = 30000;
        private int successThreshold = 3;

        /**
         * 设置熔断器名称 (用于日志标识)
         * @param name 名称
         * @return this
         */
        public Builder name(String name) { this.name = name; return this; }

        /**
         * 设置失败阈值: 连续失败多少次触发熔断
         * @param threshold 阈值 (默认5)
         * @return this
         */
        public Builder failureThreshold(int threshold) { this.failureThreshold = threshold; return this; }

        /**
         * 设置恢复超时: 熔断后多久尝试恢复
         * @param timeoutMs 超时毫秒数 (默认30000ms = 30s)
         * @return this
         */
        public Builder recoveryTimeout(long timeoutMs) { this.recoveryTimeoutMs = timeoutMs; return this; }

        /**
         * 设置成功阈值: HALF_OPEN状态连续成功多少次恢复
         * @param threshold 阈值 (默认3)
         * @return this
         */
        public Builder successThreshold(int threshold) { this.successThreshold = threshold; return this; }

        public CircuitBreaker build() {
            if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold必须>0");
            if (recoveryTimeoutMs <= 0) throw new IllegalArgumentException("recoveryTimeout必须>0");
            if (successThreshold <= 0) throw new IllegalArgumentException("successThreshold必须>0");
            return new CircuitBreaker(this);
        }
    }
}
