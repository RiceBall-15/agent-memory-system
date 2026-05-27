package com.memoryplatform.connection;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用连接池接口
 *
 * <p>提供统一的连接池抽象，支持连接借用/归还、健康检查、空闲连接回收等功能。</p>
 *
 * <h3>设计特性</h3>
 * <ul>
 *   <li>使用Semaphore控制并发连接数</li>
 *   <li>支持最小/最大连接数配置</li>
 *   <li>空闲连接超时检测与回收（默认60秒）</li>
 *   <li>连接健康检查（Ping）</li>
 *   <li>池状态监控（活跃数、空闲数、等待数）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ConnectionPool<MyConnection> pool = new MyConnectionPool(config);
 * pool.initialize();
 *
 * MyConnection conn = pool.borrowObject();
 * try {
 *     // 使用连接
 * } finally {
 *     pool.returnObject(conn);
 * }
 * }</pre>
 *
 * @param <T> 连接对象类型
 * @author Agent Memory Platform
 */
public interface ConnectionPool<T> {

    /**
     * 连接池统计信息
     */
    class PoolStats {
        /** 最大连接数 */
        public final int maxConnections;
        /** 最小连接数 */
        public final int minConnections;
        /** 当前活跃（借出）连接数 */
        public final int activeConnections;
        /** 当前空闲连接数 */
        public final int idleConnections;
        /** 等待获取连接的线程数 */
        public final int waitingThreads;
        /** 总创建连接数 */
        public final long totalCreated;
        /** 总销毁连接数 */
        public final long totalDestroyed;
        /** 总成功借出次数 */
        public final long totalBorrowed;
        /** 总成功归还次数 */
        public final long totalReturned;
        /** 健康检查失败次数 */
        public final long healthCheckFailures;

        public PoolStats(int maxConnections, int minConnections,
                         int activeConnections, int idleConnections,
                         int waitingThreads, long totalCreated,
                         long totalDestroyed, long totalBorrowed,
                         long totalReturned, long healthCheckFailures) {
            this.maxConnections = maxConnections;
            this.minConnections = minConnections;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.waitingThreads = waitingThreads;
            this.totalCreated = totalCreated;
            this.totalDestroyed = totalDestroyed;
            this.totalBorrowed = totalBorrowed;
            this.totalReturned = totalReturned;
            this.healthCheckFailures = healthCheckFailures;
        }

        @Override
        public String toString() {
            return String.format(
                    "PoolStats{max=%d, min=%d, active=%d, idle=%d, waiting=%d, " +
                    "created=%d, destroyed=%d, borrowed=%d, returned=%d, fail=%d}",
                    maxConnections, minConnections, activeConnections,
                    idleConnections, waitingThreads, totalCreated,
                    totalDestroyed, totalBorrowed, totalReturned,
                    healthCheckFailures
            );
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化连接池
     *
     * <p>创建最小数量的初始连接，并启动空闲连接检测线程。</p>
     *
     * @throws Exception 初始化失败时抛出
     */
    void initialize() throws Exception;

    /**
     * 关闭连接池，释放所有连接
     *
     * <p>等待所有借出的连接归还（最多等待指定超时时间），然后关闭所有空闲连接。</p>
     *
     * @param timeoutMs 关闭超时时间（毫秒）
     */
    void shutdown(long timeoutMs);

    // ==================== 连接操作 ====================

    /**
     * 从池中借用一个连接
     *
     * <p>如果池中有空闲连接则直接返回；如果没有空闲连接且未达到最大连接数，
     * 则创建新连接；如果已达到最大连接数，则阻塞等待直到有连接归还或超时。</p>
     *
     * @param timeoutMs 等待超时时间（毫秒），0表示无限等待
     * @return 借用的连接对象
     * @throws Exception 如果获取连接失败或超时
     */
    T borrowObject(long timeoutMs) throws Exception;

    /**
     * 归还连接到池中
     *
     * <p>检查连接健康状态，如果健康则放回空闲队列，否则销毁。</p>
     *
     * @param object 要归还的连接对象
     */
    void returnObject(T object);

    /**
     * 销毁一个连接（不可归还时调用）
     *
     * @param object 要销毁的连接对象
     */
    void destroyObject(T object);

    // ==================== 健康检查 ====================

    /**
     * 对连接进行健康检查（Ping）
     *
     * @param object 要检查的连接对象
     * @return 如果连接健康返回true
     */
    boolean healthCheck(T object);

    // ==================== 状态查询 ====================

    /**
     * 获取池的统计信息
     *
     * @return 统计信息对象
     */
    PoolStats getStats();

    /**
     * 获取池中空闲连接数
     *
     * @return 空闲连接数
     */
    int getIdleCount();

    /**
     * 获取池中活跃（借出）连接数
     *
     * @return 活跃连接数
     */
    int getActiveCount();

    /**
     * 获取等待获取连接的线程数
     *
     * @return 等待线程数
     */
    int getWaitingCount();

    /**
     * 检查连接池是否已关闭
     *
     * @return 如果已关闭返回true
     */
    boolean isShutdown();
}
