package com.memoryplatform.server;

import com.memoryplatform.storage.VectorStore;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.adapters.MilvusVectorStore;
import com.memoryplatform.storage.adapters.Neo4jGraphStore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 优雅停机管理器
 *
 * <p>负责协调整个应用的优雅关闭过程，包括：</p>
 * <ul>
 *   <li>注册JVM关闭钩子（Runtime.addShutdownHook）</li>
 *   <li>信号处理：SIGTERM、SIGINT触发优雅停机</li>
 *   <li>停机状态机：RUNNING → DRAINING → STOPPED</li>
 *   <li>等待进行中请求完成（超时30秒）</li>
 *   <li>关闭存储连接（Milvus、Neo4j等）</li>
 *   <li>关闭HTTP服务器</li>
 * </ul>
 *
 * <h3>状态机流转</h3>
 * <pre>
 *   RUNNING ──(收到信号)──→ DRAINING ──(请求清空或超时)──→ STOPPED
 * </pre>
 *
 * <h3>设计约束</h3>
 * <p>针对2核2G内存环境优化，使用CountDownLatch协调停机流程，避免阻塞主JVM Shutdown Hook。</p>
 *
 * @author Agent Memory Platform
 * @version 1.0
 */
public class GracefulShutdown {

    // ==================== 停机状态枚举 ====================

    /**
     * 停机状态枚举
     */
    public enum State {
        /** 正常运行中 */
        RUNNING,
        /** 正在排空请求（不再接受新请求） */
        DRAINING,
        /** 已停止 */
        STOPPED
    }

    // ==================== 静态常量 ====================

    /** 默认等待超时时间（秒） */
    private static final int DEFAULT_DRAIN_TIMEOUT_SECONDS = 30;

    /** JVM关闭钩子名称 */
    private static final String SHUTDOWN_HOOK_NAME = "GracefulShutdownHook";

    // ==================== 状态字段 ====================

    /** 当前停机状态（volatile保证线程可见性） */
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    /** 等待进行中请求完成的CountDownLatch */
    private final CountDownLatch drainLatch = new CountDownLatch(1);

    /** 进行中请求计数器 */
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /** 停机开始时间 */
    private volatile long shutdownStartTime;

    /** 排空超时时间（秒） */
    private final int drainTimeoutSeconds;

    /** JVM关闭钩子线程 */
    private Thread shutdownHookThread;

    // ==================== 资源引用 ====================

    /** HTTP服务器（可为null） */
    private volatile MemoryHttpServer httpServer;

    /** Metrics服务器（可为null） */
    private volatile MetricsHttpServer metricsServer;

    /** 向量存储（可为null） */
    private volatile VectorStore vectorStore;

    /** 图存储（可为null） */
    private volatile GraphStore graphStore;

    /** 元数据存储（可为null） */
    private volatile MetadataStore metadataStore;

    // ==================== 构造函数 ====================

    /**
     * 创建优雅停机管理器（使用默认超时30秒）
     */
    public GracefulShutdown() {
        this(DEFAULT_DRAIN_TIMEOUT_SECONDS);
    }

    /**
     * 创建优雅停机管理器
     *
     * @param drainTimeoutSeconds 排空等待超时时间（秒）
     */
    public GracefulShutdown(int drainTimeoutSeconds) {
        this.drainTimeoutSeconds = drainTimeoutSeconds;
    }

    // ==================== 初始化 ====================

    /**
     * 注册JVM关闭钩子和信号处理器
     *
     * <p>在应用启动完成后调用此方法。注册一个低优先级的JVM ShutdownHook，
     * 确保在应用退出时执行优雅关闭。</p>
     */
    public void register() {
        // 注册JVM ShutdownHook
        shutdownHookThread = new Thread(this::performShutdown, SHUTDOWN_HOOK_NAME);
        shutdownHookThread.setDaemon(false); // 非守护线程，确保完成
        shutdownHookThread.setPriority(Thread.NORM_PRIORITY - 1); // 略低优先级

        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        System.out.println("[GracefulShutdown] 已注册JVM ShutdownHook");

        // 注册信号处理器（SIGTERM / SIGINT）
        // 在Java中，SIGINT (Ctrl+C) 已由JVM自动处理触发ShutdownHook
        // 这里使用Runtime的信号API处理SIGTERM
        try {
            // Java 9+ 支持 Signal API
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
                System.out.println("[GracefulShutdown] 收到SIGTERM信号，触发优雅停机");
                initiateShutdown();
            });
            System.out.println("[GracefulShutdown] 已注册SIGTERM信号处理器");
        } catch (Exception e) {
            // 非Oracle/OpenJDK JDK可能不支持sun.misc.Signal
            System.out.println("[GracefulShutdown] 信号处理器注册失败（非致命）: " + e.getMessage());
            System.out.println("[GracefulShutdown] 将依赖JVM ShutdownHook处理停机");
        }

        System.out.println("[GracefulShutdown] 初始化完成，等待停机信号...");
    }

    // ==================== 资源绑定 ====================

    /**
     * 绑定HTTP服务器引用
     *
     * @param server HTTP服务器实例
     */
    public void bindHttpServer(MemoryHttpServer server) {
        this.httpServer = server;
    }

    /**
     * 绑定Metrics服务器引用
     *
     * @param server Metrics服务器实例
     */
    public void bindMetricsServer(MetricsHttpServer server) {
        this.metricsServer = server;
    }

    /**
     * 绑定存储层引用
     *
     * @param vs 向量存储（可为null）
     * @param gs 图存储（可为null）
     * @param ms 元数据存储（可为null）
     */
    public void bindStorage(VectorStore vs, GraphStore gs, MetadataStore ms) {
        this.vectorStore = vs;
        this.graphStore = gs;
        this.metadataStore = ms;
    }

    // ==================== 请求追踪 ====================

    /**
     * 请求进入时调用，增加活跃请求计数
     *
     * @return 当前活跃请求数
     */
    public int requestAcquired() {
        return activeRequests.incrementAndGet();
    }

    /**
     * 请求完成时调用，减少活跃请求计数
     * 如果处于DRAINING状态且无活跃请求，释放排闩
     *
     * @return 当前活跃请求数
     */
    public int requestReleased() {
        int count = activeRequests.decrementAndGet();
        if (count <= 0 && state.get() == State.DRAINING) {
            System.out.println("[GracefulShutdown] 所有请求已完成，准备停止");
            drainLatch.countDown();
        }
        return count;
    }

    /**
     * 检查是否可以接受新请求
     *
     * @return 如果处于RUNNING状态则返回true
     */
    public boolean canAcceptRequests() {
        return state.get() == State.RUNNING;
    }

    // ==================== 停机流程 ====================

    /**
     * 发起停机流程（将状态从RUNNING切换到DRAINING）
     *
     * <p>此方法是幂等的，多次调用只会在第一次生效。</p>
     */
    public void initiateShutdown() {
        if (!state.compareAndSet(State.RUNNING, State.DRAINING)) {
            // 已经在DRAINING或STOPPED状态，忽略
            return;
        }

        shutdownStartTime = System.currentTimeMillis();
        System.out.println("[GracefulShutdown] ============================================");
        System.out.println("[GracefulShutdown] 开始优雅停机流程");
        System.out.println("[GracefulShutdown] 状态: RUNNING → DRAINING");
        System.out.println("[GracefulShutdown] 排空超时: " + drainTimeoutSeconds + "秒");
        System.out.println("[GracefulShutdown] 当前活跃请求: " + activeRequests.get());
        System.out.println("[GracefulShutdown] ============================================");

        // 检查是否已有活跃请求
        if (activeRequests.get() <= 0) {
            System.out.println("[GracefulShutdown] 无活跃请求，直接进入停止阶段");
            drainLatch.countDown();
        }
    }

    /**
     * 执行完整的停机流程（由JVM ShutdownHook调用）
     */
    private void performShutdown() {
        // 如果还没有开始停机，先发起
        if (state.get() == State.RUNNING) {
            initiateShutdown();
        }

        long startTime = System.currentTimeMillis();

        try {
            // ===== 阶段1: 等待请求排空 =====
            System.out.println("[GracefulShutdown] [1/4] 等待进行中请求完成...");
            boolean drained = drainLatch.await(drainTimeoutSeconds, TimeUnit.SECONDS);

            if (drained) {
                System.out.println("[GracefulShutdown] 所有请求已完成排空");
            } else {
                int remaining = activeRequests.get();
                System.out.println("[GracefulShutdown] 排空超时，剩余 " + remaining + " 个活跃请求");
            }

            // ===== 阶段2: 停止HTTP服务器 =====
            System.out.println("[GracefulShutdown] [2/4] 停止HTTP服务器...");
            stopHttpServer();

            // ===== 阶段3: 关闭存储连接 =====
            System.out.println("[GracefulShutdown] [3/4] 关闭存储连接...");
            closeStorageConnections();

            // ===== 阶段4: 停止Metrics服务器 =====
            System.out.println("[GracefulShutdown] [4/4] 停止Metrics服务器...");
            stopMetricsServer();

            // 更新状态
            state.set(State.STOPPED);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("[GracefulShutdown] ============================================");
            System.out.println("[GracefulShutdown] 优雅停机完成");
            System.out.println("[GracefulShutdown] 状态: → STOPPED");
            System.out.println("[GracefulShutdown] 总耗时: " + elapsed + "ms");
            System.out.println("[GracefulShutdown] ============================================");

        } catch (Exception e) {
            System.err.println("[GracefulShutdown] 停机过程异常: " + e.getMessage());
            e.printStackTrace();
            state.set(State.STOPPED);
        }
    }

    // ==================== 资源关闭方法 ====================

    /**
     * 停止HTTP服务器
     */
    private void stopHttpServer() {
        if (httpServer != null && httpServer.isRunning()) {
            try {
                System.out.println("[GracefulShutdown] 正在停止HTTP服务器...");
                httpServer.stop();
                System.out.println("[GracefulShutdown] HTTP服务器已停止");
            } catch (Exception e) {
                System.err.println("[GracefulShutdown] 停止HTTP服务器异常: " + e.getMessage());
            }
        } else {
            System.out.println("[GracefulShutdown] HTTP服务器未运行或未绑定，跳过");
        }
    }

    /**
     * 停止Metrics服务器
     */
    private void stopMetricsServer() {
        if (metricsServer != null && metricsServer.isRunning()) {
            try {
                System.out.println("[GracefulShutdown] 正在停止Metrics服务器...");
                metricsServer.stop();
                System.out.println("[GracefulShutdown] Metrics服务器已停止");
            } catch (Exception e) {
                System.err.println("[GracefulShutdown] 停止Metrics服务器异常: " + e.getMessage());
            }
        } else {
            System.out.println("[GracefulShutdown] Metrics服务器未运行或未绑定，跳过");
        }
    }

    /**
     * 关闭所有存储连接
     *
     * <p>按照依赖关系关闭：先关闭图存储和向量存储，最后关闭元数据存储。</p>
     */
    private void closeStorageConnections() {
        // 关闭向量存储（Milvus）
        if (vectorStore != null) {
            try {
                if (vectorStore instanceof MilvusVectorStore) {
                    System.out.println("[GracefulShutdown] 正在关闭Milvus连接...");
                    ((MilvusVectorStore) vectorStore).close();
                    System.out.println("[GracefulShutdown] Milvus连接已关闭");
                } else {
                    System.out.println("[GracefulShutdown] 向量存储类型: "
                            + vectorStore.getClass().getSimpleName() + "（无自定义close方法）");
                }
            } catch (Exception e) {
                System.err.println("[GracefulShutdown] 关闭Milvus连接异常: " + e.getMessage());
            }
        }

        // 关闭图存储（Neo4j）
        if (graphStore != null) {
            try {
                if (graphStore instanceof Neo4jGraphStore) {
                    System.out.println("[GracefulShutdown] 正在关闭Neo4j连接...");
                    ((Neo4jGraphStore) graphStore).close();
                    System.out.println("[GracefulShutdown] Neo4j连接已关闭");
                } else {
                    System.out.println("[GracefulShutdown] 图存储类型: "
                            + graphStore.getClass().getSimpleName() + "（无自定义close方法）");
                }
            } catch (Exception e) {
                System.err.println("[GracefulShutdown] 关闭Neo4j连接异常: " + e.getMessage());
            }
        }

        // 关闭元数据存储
        if (metadataStore != null) {
            try {
                if (metadataStore instanceof java.io.Closeable) {
                    System.out.println("[GracefulShutdown] 正在关闭元数据存储...");
                    ((java.io.Closeable) metadataStore).close();
                    System.out.println("[GracefulShutdown] 元数据存储已关闭");
                } else {
                    System.out.println("[GracefulShutdown] 元数据存储类型: "
                            + metadataStore.getClass().getSimpleName() + "（无自定义close方法）");
                }
            } catch (Exception e) {
                System.err.println("[GracefulShutdown] 关闭元数据存储异常: " + e.getMessage());
            }
        }

        System.out.println("[GracefulShutdown] 所有存储连接已关闭");
    }

    // ==================== 状态查询 ====================

    /**
     * 获取当前停机状态
     *
     * @return 当前状态
     */
    public State getState() {
        return state.get();
    }

    /**
     * 检查是否正在运行
     *
     * @return 如果状态为RUNNING则返回true
     */
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /**
     * 检查是否已停止
     *
     * @return 如果状态为STOPPED则返回true
     */
    public boolean isStopped() {
        return state.get() == State.STOPPED;
    }

    /**
     * 获取当前活跃请求数
     *
     * @return 活跃请求数
     */
    public int getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * 获取停机已运行时间（毫秒）
     *
     * @return 如果未开始停机返回0，否则返回已过时间
     */
    public long getShutdownElapsed() {
        if (shutdownStartTime == 0) return 0;
        return System.currentTimeMillis() - shutdownStartTime;
    }

    /**
     * 获取停机摘要信息
     *
     * @return 格式化的状态字符串
     */
    public String getSummary() {
        return String.format("GracefulShutdown{state=%s, activeRequests=%d, elapsed=%dms}",
                state.get(), activeRequests.get(), getShutdownElapsed());
    }
}
