package com.memoryplatform.circuit;

/**
 * 熔断器异常 - 当熔断器处于OPEN状态时抛出
 * <p>
 * 该异常表示目标服务已被熔断保护, 请求被直接拒绝。
 * 调用方应捕获此异常并执行降级逻辑 (如返回缓存数据、默认值等)。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * try {
 *     circuitBreaker.execute(() -> vectorStore.upsert("col", records));
 * } catch (CircuitBreakedException e) {
 *     System.err.println("服务被熔断, 执行降级: " + e.getMessage());
 *     return fallbackResult;
 * }
 * }</pre>
 */
public class CircuitBreakedException extends Exception {

    /**
     * 构造熔断器异常
     * @param message 异常信息
     */
    public CircuitBreakedException(String message) {
        super(message);
    }

    /**
     * 构造熔断器异常 (带原因)
     * @param message 异常信息
     * @param cause 原始异常
     */
    public CircuitBreakedException(String message, Throwable cause) {
        super(message, cause);
    }
}
