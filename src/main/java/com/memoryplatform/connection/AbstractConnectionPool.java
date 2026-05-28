package com.memoryplatform.connection;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
/**
 * 连接池抽象基类
 *
 * <p>提供连接池的通用骨架实现，子类只需实现连接的创建、销毁和健康检查。</p>
 *
 * <h3>核心机制</h3>
 * <ul>
 *   <li>使用Semaphore控制最大并发连接数</li>
 *   <li>使用LinkedBlockingDeque管理空闲连接</li>
 *   <li>后台调度线程定时检测空闲超时连接并回收</li>
 *   <li>线程安全的统计计数器</li>
 * </ul>
 *
 * @param <T> 连接对象类型
 * @author Agent Memory Platform
 */
@Slf4j
public abstract class AbstractConnectionPool<T> implements ConnectionPool<T> {

    // ==================== 配置 ====================

    /** 最小连接数 */
    protected final int minConnections;

    /** 最大连接数 */
    protected final int maxConnections;

    /** 空闲连接超时时间（毫秒），默认60秒 */
    protected final long idleTimeoutMs;

    /** 借用连接超时时间（毫秒），默认10秒 */
    protected final long borrowTimeoutMs;

    /** 连接健康检查间隔（毫秒），默认30秒 */
    protected final long healthCheckIntervalMs;

    // ==================== 核心数据结构 ====================

    /** 并发控制信号量 */
    protected final Semaphore semaphore;

    /** 空闲连接队列（线程安全） */
    protected final LinkedBlockingDeque<PoolEntry<T>> idleQueue;

    /** 关闭标记 */
    protected final AtomicBoolean shutdown = new AtomicBoolean(false);

    // ==================== 统计计数器 ====================

    /** 当前活跃（借出）连接数 */
    protected final AtomicInteger activeCount = new AtomicInteger(0);

    /** 总创建连接数 */
    protected final AtomicLong totalCreated = new AtomicLong(0);

    /** 总销毁连接数 */
    protected final AtomicLong totalDestroyed = new AtomicLong(0);

    /** 总成功借出次数 */
    protected final AtomicLong totalBorrowed = new AtomicLong(0);

    /** 总成功归还次数 */
    protected final AtomicLong totalReturned = new AtomicLong(0);

    /** 健康检查失败次数 */
    protected final AtomicLong healthCheckFailures = new AtomicLong(0);

    // ==================== 后台任务 ====================

    /** 空闲连接检测调度器 */
    protected ScheduledExecutorService evictor;

    // ==================== 构造函数 ====================

    /**
     * 创建连接池
     *
     * @param minConnections 最小连接数
     * @param maxConnections 最大连接数
     * @param idleTimeoutMs  空闲超时（毫秒）
     * @param borrowTimeoutMs 借用超时（毫秒）
     */
    protected AbstractConnectionPool(int minConnections, int maxConnections,
                                      long idleTimeoutMs, long borrowTimeoutMs) {
        if (minConnections < 0) throw new IllegalArgumentException("minConnections must be >= 0");
        if (maxConnections <= 0) throw new IllegalArgumentException("maxConnections must be > 0");
        if (minConnections > maxConnections) throw new IllegalArgumentException("minConnections > maxConnections");

        this.minConnections = minConnections;
        this.maxConnections = maxConnections;
        this.idleTimeoutMs = idleTimeoutMs;
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.healthCheckIntervalMs = Math.min(idleTimeoutMs / 2, 30_000);

        this.semaphore = new Semaphore(maxConnections, true); // 公平信号量
        this.idleQueue = new LinkedBlockingDeque<>(maxConnections);
    }

    // ==================== 生命周期实现 ====================

    @Override
    public void initialize() throws Exception {
        if (shutdown.get()) {
            throw new IllegalStateException("连接池已关闭，无法初始化");
        }

        log.info("[ConnectionPool] 初始化连接池: min=" + minConnections
                + ", max=" + maxConnections + ", idleTimeout=" + idleTimeoutMs + "ms")

        // 创建最小连接数的初始连接
        for (int i = 0; i < minConnections; i++) {
            try {
                T conn = createConnection();
                PoolEntry<T> entry = new PoolEntry<>(conn, System.currentTimeMillis());
                idleQueue.offer(entry);
                totalCreated.incrementAndGet();
                log.info("[ConnectionPool] 创建初始连接 #" + (i + 1) + "/" + minConnections)
            } catch (Exception e) {
                log.error("[ConnectionPool] 创建初始连接失败: " + e.getMessage());
                throw e;
            }
        }

        // 启动空闲连接检测调度器（虚拟线程工厂）
        evictor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("conn-pool-evictor-").factory()
        );
        evictor.scheduleAtFixedRate(this::evictIdleConnections,
                healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);

        log.info("[ConnectionPool] 连接池初始化完成，已创建 " + minConnections + " 个连接")
    }

    @Override
    public void shutdown(long timeoutMs) {
        if (!shutdown.compareAndSet(false, true)) {
            return; // 已关闭
        }

        log.info("[ConnectionPool] 开始关闭连接池...")
        long deadline = System.currentTimeMillis() + timeoutMs;

        // 停止调度器
        if (evictor != null) {
            evictor.shutdown();
            try {
                evictor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                evictor.shutdownNow();
            }
        }

        // 关闭所有空闲连接
        PoolEntry<T> entry;
        int closedCount = 0;
        while ((entry = idleQueue.poll()) != null) {
            try {
                destroyConnection(entry.connection);
                closedCount++;
                totalDestroyed.incrementAndGet();
            } catch (Exception e) {
                log.error("[ConnectionPool] 关闭空闲连接异常: " + e.getMessage());
            }
        }

        // 等待活跃连接归还
        long waitTime = deadline - System.currentTimeMillis();
        if (waitTime > 0 && activeCount.get() > 0) {
            log.info("[ConnectionPool] 等待 " + activeCount.get()
                    + " 个活跃连接归还 (最多 " + waitTime + "ms)...")
            try {
                Thread.sleep(Math.min(waitTime, 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("[ConnectionPool] 连接池已关闭，关闭了 " + closedCount + " 个空闲连接")
    }

    // ==================== 连接借用/归还 ====================

    @Override
    public T borrowObject(long timeoutMs) throws Exception {
        if (shutdown.get()) {
            throw new IllegalStateException("连接池已关闭");
        }

        long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : Long.MAX_VALUE);

        // 尝试获取信号量许可
        boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new TimeoutException("获取连接超时（等待 " + timeoutMs + "ms）");
        }

        try {
            // 尝试从空闲队列获取连接
            PoolEntry<T> entry = idleQueue.poll();
            if (entry != null) {
                // 检查连接是否过期
                if (System.currentTimeMillis() - entry.lastUsedTime > idleTimeoutMs) {
                    // 连接过期，销毁并创建新连接
                    log.info("[ConnectionPool] 空闲连接已过期，销毁并重建")
                    destroyConnection(entry.connection);
                    totalDestroyed.incrementAndGet();
                } else {
                    // 尝试健康检查
                    if (healthCheck(entry.connection)) {
                        activeCount.incrementAndGet();
                        totalBorrowed.incrementAndGet();
                        return entry.connection;
                    } else {
                        // 健康检查失败，销毁并创建新连接
                        log.info("[ConnectionPool] 空闲连接健康检查失败，重建")
                        destroyConnection(entry.connection);
                        totalDestroyed.incrementAndGet();
                        healthCheckFailures.incrementAndGet();
                    }
                }
            }

            // 没有可用的空闲连接，创建新连接
            T conn = createConnection();
            totalCreated.incrementAndGet();
            activeCount.incrementAndGet();
            totalBorrowed.incrementAndGet();
            return conn;

        } catch (Exception e) {
            semaphore.release(); // 创建失败，释放许可
            throw e;
        }
    }

    @Override
    public void returnObject(T object) {
        if (object == null) return;

        activeCount.decrementAndGet();
        totalReturned.incrementAndGet();

        if (shutdown.get()) {
            // 连接池已关闭，直接销毁
            try {
                destroyConnection(object);
                totalDestroyed.incrementAndGet();
            } catch (Exception e) {
                log.error("[ConnectionPool] 关闭时销毁连接异常: " + e.getMessage());
            }
            semaphore.release();
            return;
        }

        // 尝试健康检查
        if (!healthCheck(object)) {
            log.info("[ConnectionPool] 归还时健康检查失败，销毁连接")
            healthCheckFailures.incrementAndGet();
            try {
                destroyConnection(object);
                totalDestroyed.incrementAndGet();
            } catch (Exception e) {
                log.error("[ConnectionPool] 销毁不健康连接异常: " + e.getMessage());
            }
            semaphore.release();
            return;
        }

        // 放回空闲队列
        PoolEntry<T> entry = new PoolEntry<>(object, System.currentTimeMillis());
        if (!idleQueue.offer(entry)) {
            // 队列已满，销毁多余连接
            log.info("[ConnectionPool] 空闲队列已满，销毁多余连接")
            try {
                destroyConnection(object);
                totalDestroyed.incrementAndGet();
            } catch (Exception e) {
                log.error("[ConnectionPool] 销毁多余连接异常: " + e.getMessage());
            }
        }
        semaphore.release();
    }

    @Override
    public void destroyObject(T object) {
        if (object == null) return;

        activeCount.decrementAndGet();
        try {
            destroyConnection(object);
            totalDestroyed.incrementAndGet();
        } catch (Exception e) {
            log.error("[ConnectionPool] 销毁连接异常: " + e.getMessage());
        }
        semaphore.release();
    }

    // ==================== 空闲连接回收 ====================

    /**
     * 回收过期的空闲连接
     */
    protected void evictIdleConnections() {
        if (shutdown.get()) return;

        long now = System.currentTimeMillis();
        int evictedCount = 0;

        // 从队列头部开始检查
        PoolEntry<T> entry;
        while ((entry = idleQueue.peek()) != null) {
            if (now - entry.lastUsedTime > idleTimeoutMs) {
                // 连接已过期，从队列移除
                entry = idleQueue.poll();
                if (entry != null) {
                    try {
                        destroyConnection(entry.connection);
                        totalDestroyed.incrementAndGet();
                        evictedCount++;
                    } catch (Exception e) {
                        log.error("[ConnectionPool] 回收过期连接异常: " + e.getMessage());
                    }
                }
            } else {
                break; // 队列按时间排序，后面的也不会过期
            }
        }

        // 确保空闲连接数不低于最小值
        int currentIdle = idleQueue.size();
        if (currentIdle < minConnections && !shutdown.get()) {
            int toCreate = minConnections - currentIdle;
            for (int i = 0; i < toCreate; i++) {
                try {
                    if (semaphore.tryAcquire()) {
                        T conn = createConnection();
                        PoolEntry<T> newEntry = new PoolEntry<>(conn, System.currentTimeMillis());
                        idleQueue.offer(newEntry);
                        totalCreated.incrementAndGet();
                        semaphore.release();
                    }
                } catch (Exception e) {
                    log.error("[ConnectionPool] 补充最小连接数失败: " + e.getMessage());
                }
            }
        }

        if (evictedCount > 0) {
            log.info("[ConnectionPool] 回收了 " + evictedCount + " 个过期空闲连接")
        }
    }

    // ==================== 状态查询 ====================

    @Override
    public PoolStats getStats() {
        return new PoolStats(
                maxConnections,
                minConnections,
                activeCount.get(),
                idleQueue.size(),
                semaphore.getQueueLength(),
                totalCreated.get(),
                totalDestroyed.get(),
                totalBorrowed.get(),
                totalReturned.get(),
                healthCheckFailures.get()
        );
    }

    @Override
    public int getIdleCount() {
        return idleQueue.size();
    }

    @Override
    public int getActiveCount() {
        return activeCount.get();
    }

    @Override
    public int getWaitingCount() {
        return semaphore.getQueueLength();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    // ==================== 子类必须实现 ====================

    /**
     * 创建新的连接对象
     *
     * @return 新创建的连接
     * @throws Exception 创建失败时抛出
     */
    protected abstract T createConnection() throws Exception;

    /**
     * 销毁连接对象
     *
     * @param connection 要销毁的连接
     * @throws Exception 销毁失败时抛出
     */
    protected abstract void destroyConnection(T connection) throws Exception;

    /**
     * 连接健康检查
     *
     * @param connection 要检查的连接
     * @return 如果连接健康返回true
     */
    protected abstract boolean healthCheckImpl(T connection);

    @Override
    public boolean healthCheck(T object) {
        if (object == null) return false;
        try {
            return healthCheckImpl(object);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 内部类 ====================

    /**
     * 连接池条目，包装连接对象和最后使用时间
     */
    protected static class PoolEntry<T> {
        final T connection;
        final long lastUsedTime;

        PoolEntry(T connection, long lastUsedTime) {
            this.connection = connection;
            this.lastUsedTime = lastUsedTime;
        }
    }
}
