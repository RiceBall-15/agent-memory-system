package com.memoryplatform.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.prometheus.client.CounterMetricFamily;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义Prometheus指标收集器
 * <p>
 * 实现 {@link Collector} 接口，提供JVM和应用层面的自定义指标。
 * 在每次Prometheus抓取时动态收集最新数据。
 * </p>
 *
 * <h3>收集的指标</h3>
 * <ul>
 *   <li><b>JVM内存指标</b>: 堆内存使用、非堆内存使用、最大堆内存</li>
 *   <li><b>JVM线程指标</b>: 活跃线程数、守护线程数、峰值线程数</li>
 *   <li><b>GC指标</b>: 各GC收集器的收集次数和耗时</li>
 *   <li><b>应用指标</b>: 队列深度、活跃连接数等</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 注册自定义收集器
 * MetricsCollector collector = new MetricsCollector();
 * collector.register(CollectorRegistry.defaultRegistry);
 *
 * // 或者在MetricsManager中自动注册
 * MetricsManager.registerCustomCollector();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @see MetricsManager
 * @see Collector
 */
public class MetricsCollector extends Collector {

    /** 堆内存使用 Gauge */
    private static final GaugeMetricFamily JVM_HEAP_USED = new GaugeMetricFamily(
            "jvm_memory_heap_used_bytes",
            "JVM heap memory used in bytes",
            List.of()
    );

    /** 堆内存提交 Gauge */
    private static final GaugeMetricFamily JVM_HEAP_COMMITTED = new GaugeMetricFamily(
            "jvm_memory_heap_committed_bytes",
            "JVM heap memory committed in bytes",
            List.of()
    );

    /** 堆内存最大值 Gauge */
    private static final GaugeMetricFamily JVM_HEAP_MAX = new GaugeMetricFamily(
            "jvm_memory_heap_max_bytes",
            "JVM heap memory max in bytes",
            List.of()
    );

    /** 非堆内存使用 Gauge */
    private static final GaugeMetricFamily JVM_NON_HEAP_USED = new GaugeMetricFamily(
            "jvm_memory_non_heap_used_bytes",
            "JVM non-heap memory used in bytes",
            List.of()
    );

    /** 活跃线程数 Gauge */
    private static final GaugeMetricFamily JVM_THREADS_CURRENT = new GaugeMetricFamily(
            "jvm_threads_current",
            "Current number of live threads",
            List.of()
    );

    /** 守护线程数 Gauge */
    private static final GaugeMetricFamily JVM_THREADS_DAEMON = new GaugeMetricFamily(
            "jvm_threads_daemon",
            "Current number of daemon threads",
            List.of()
    );

    /** 峰值线程数 Gauge */
    private static final GaugeMetricFamily JVM_THREADS_PEAK = new GaugeMetricFamily(
            "jvm_threads_peak",
            "Peak number of live threads",
            List.of()
    );

    /** 队列深度 Gauge */
    private static final GaugeMetricFamily APP_QUEUE_DEPTH = new GaugeMetricFamily(
            "app_queue_depth",
            "Application processing queue depth",
            List.of()
    );

    /** 活跃连接数 Gauge */
    private static final GaugeMetricFamily APP_ACTIVE_CONNECTIONS = new GaugeMetricFamily(
            "app_active_connections",
            "Application active connections",
            List.of()
    );

    /** JVM启动时间 Gauge */
    private static final GaugeMetricFamily JVM_START_TIME = new GaugeMetricFamily(
            "jvm_start_time_seconds",
            "JVM start time in seconds since epoch",
            List.of()
    );

    /** JVM运行时间 Gauge */
    private static final GaugeMetricFamily JVM_UPTIME = new GaugeMetricFamily(
            "jvm_uptime_seconds",
            "JVM uptime in seconds",
            List.of()
    );

    /** JVM进程CPU使用时间 Gauge */
    private static final GaugeMetricFamily JVM_PROCESS_CPU_TIME = new GaugeMetricFamily(
            "jvm_process_cpu_time_seconds",
            "JVM process CPU time in seconds",
            List.of()
    );

    /**
     * 收集所有自定义指标
     * <p>
     * 在每次Prometheus抓取时被调用，动态获取JVM和应用的最新指标值。
     * </p>
     *
     * @return 指标样本列表
     */
    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> samples = new ArrayList<>();

        try {
            // 收集JVM内存指标
            collectMemoryMetrics(samples);

            // 收集JVM线程指标
            collectThreadMetrics(samples);

            // 收集JVM运行时间指标
            collectUptimeMetrics(samples);

            // 收集GC指标
            collectGarbageCollectorMetrics(samples);

            // 收集应用指标
            collectApplicationMetrics(samples);

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集指标异常: " + e.getMessage());
            e.printStackTrace();
        }

        return samples;
    }

    /**
     * 收集JVM内存指标
     *
     * @param samples 指标样本列表
     */
    private void collectMemoryMetrics(List<MetricFamilySamples> samples) {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

            // 堆内存
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            GaugeMetricFamily heapUsed = new GaugeMetricFamily(
                    "jvm_memory_heap_used_bytes",
                    "JVM heap memory used in bytes",
                    List.of()
            );
            heapUsed.addMetric(List.of(), heapUsage.getUsed());
            samples.add(heapUsed);

            GaugeMetricFamily heapCommitted = new GaugeMetricFamily(
                    "jvm_memory_heap_committed_bytes",
                    "JVM heap memory committed in bytes",
                    List.of()
            );
            heapCommitted.addMetric(List.of(), heapUsage.getCommitted());
            samples.add(heapCommitted);

            GaugeMetricFamily heapMax = new GaugeMetricFamily(
                    "jvm_memory_heap_max_bytes",
                    "JVM heap memory max in bytes",
                    List.of()
            );
            heapMax.addMetric(List.of(), heapUsage.getMax());
            samples.add(heapMax);

            // 非堆内存
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            GaugeMetricFamily nonHeapUsed = new GaugeMetricFamily(
                    "jvm_memory_non_heap_used_bytes",
                    "JVM non-heap memory used in bytes",
                    List.of()
            );
            nonHeapUsed.addMetric(List.of(), nonHeapUsage.getUsed());
            samples.add(nonHeapUsed);

            GaugeMetricFamily nonHeapCommitted = new GaugeMetricFamily(
                    "jvm_memory_non_heap_committed_bytes",
                    "JVM non-heap memory committed in bytes",
                    List.of()
            );
            nonHeapCommitted.addMetric(List.of(), nonHeapUsage.getCommitted());
            samples.add(nonHeapCommitted);

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集内存指标异常: " + e.getMessage());
        }
    }

    /**
     * 收集JVM线程指标
     *
     * @param samples 指标样本列表
     */
    private void collectThreadMetrics(List<MetricFamilySamples> samples) {
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

            GaugeMetricFamily threadsCurrent = new GaugeMetricFamily(
                    "jvm_threads_current",
                    "Current number of live threads",
                    List.of()
            );
            threadsCurrent.addMetric(List.of(), threadBean.getThreadCount());
            samples.add(threadsCurrent);

            GaugeMetricFamily threadsDaemon = new GaugeMetricFamily(
                    "jvm_threads_daemon",
                    "Current number of daemon threads",
                    List.of()
            );
            threadsDaemon.addMetric(List.of(), threadBean.getDaemonThreadCount());
            samples.add(threadsDaemon);

            GaugeMetricFamily threadsPeak = new GaugeMetricFamily(
                    "jvm_threads_peak",
                    "Peak number of live threads",
                    List.of()
            );
            threadsPeak.addMetric(List.of(), threadBean.getPeakThreadCount());
            samples.add(threadsPeak);

            // 线程状态统计
            long[] threadIds = threadBean.getAllThreadIds();
            int runnable = 0;
            int blocked = 0;
            int waiting = 0;
            int timedWaiting = 0;

            for (long id : threadIds) {
                var info = threadBean.getThreadInfo(id);
                if (info != null) {
                    switch (info.getThreadState()) {
                        case RUNNABLE:
                            runnable++;
                            break;
                        case BLOCKED:
                            blocked++;
                            break;
                        case WAITING:
                            waiting++;
                            break;
                        case TIMED_WAITING:
                            timedWaiting++;
                            break;
                        default:
                            break;
                    }
                }
            }

            GaugeMetricFamily threadsRunnable = new GaugeMetricFamily(
                    "jvm_threads_runnable",
                    "Number of threads in RUNNABLE state",
                    List.of()
            );
            threadsRunnable.addMetric(List.of(), runnable);
            samples.add(threadsRunnable);

            GaugeMetricFamily threadsBlocked = new GaugeMetricFamily(
                    "jvm_threads_blocked",
                    "Number of threads in BLOCKED state",
                    List.of()
            );
            threadsBlocked.addMetric(List.of(), blocked);
            samples.add(threadsBlocked);

            GaugeMetricFamily threadsWaiting = new GaugeMetricFamily(
                    "jvm_threads_waiting",
                    "Number of threads in WAITING state",
                    List.of()
            );
            threadsWaiting.addMetric(List.of(), waiting);
            samples.add(threadsWaiting);

            GaugeMetricFamily threadsTimedWaiting = new GaugeMetricFamily(
                    "jvm_threads_timed_waiting",
                    "Number of threads in TIMED_WAITING state",
                    List.of()
            );
            threadsTimedWaiting.addMetric(List.of(), timedWaiting);
            samples.add(threadsTimedWaiting);

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集线程指标异常: " + e.getMessage());
        }
    }

    /**
     * 收集JVM运行时间指标
     *
     * @param samples 指标样本列表
     */
    private void collectUptimeMetrics(List<MetricFamilySamples> samples) {
        try {
            // JVM启动时间
            long startTimeMs = ManagementFactory.getRuntimeMXBean().getStartTime();
            GaugeMetricFamily startTime = new GaugeMetricFamily(
                    "jvm_start_time_seconds",
                    "JVM start time in seconds since epoch",
                    List.of()
            );
            startTime.addMetric(List.of(), startTimeMs / 1000.0);
            samples.add(startTime);

            // JVM运行时间
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            GaugeMetricFamily uptime = new GaugeMetricFamily(
                    "jvm_uptime_seconds",
                    "JVM uptime in seconds",
                    List.of()
            );
            uptime.addMetric(List.of(), uptimeMs / 1000.0);
            samples.add(uptime);

            // 进程CPU时间（如果可用）
            try {
                long cpuTimeNs = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                if (cpuTimeNs >= 0) {
                    GaugeMetricFamily cpuTime = new GaugeMetricFamily(
                            "jvm_process_cpu_time_seconds",
                            "JVM process CPU time in seconds",
                            List.of()
                    );
                    cpuTime.addMetric(List.of(), cpuTimeNs / 1_000_000_000.0);
                    samples.add(cpuTime);
                }
            } catch (UnsupportedOperationException e) {
                // CPU时间不可用，忽略
            }

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集运行时间指标异常: " + e.getMessage());
        }
    }

    /**
     * 收集GC指标
     *
     * @param samples 指标样本列表
     */
    private void collectGarbageCollectorMetrics(List<MetricFamilySamples> samples) {
        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            for (GarbageCollectorMXBean gcBean : gcBeans) {
                String gcName = gcBean.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();

                // GC收集次数
                CounterMetricFamily gcCount = new CounterMetricFamily(
                        "jvm_gc_collection_count",
                        "Number of GC collections",
                        List.of("gc")
                );
                long collectionCount = gcBean.getCollectionCount();
                if (collectionCount >= 0) {
                    gcCount.addMetric(List.of(gcName), collectionCount);
                }
                samples.add(gcCount);

                // GC收集耗时
                CounterMetricFamily gcTime = new CounterMetricFamily(
                        "jvm_gc_collection_time_seconds",
                        "Total GC collection time in seconds",
                        List.of("gc")
                );
                long collectionTimeMs = gcBean.getCollectionTime();
                if (collectionTimeMs >= 0) {
                    gcTime.addMetric(List.of(gcName), collectionTimeMs / 1000.0);
                }
                samples.add(gcTime);
            }

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集GC指标异常: " + e.getMessage());
        }
    }

    /**
     * 收集应用指标
     *
     * @param samples 指标样本列表
     */
    private void collectApplicationMetrics(List<MetricFamilySamples> samples) {
        try {
            // 队列深度
            GaugeMetricFamily queueDepth = new GaugeMetricFamily(
                    "app_queue_depth",
                    "Application processing queue depth",
                    List.of()
            );
            // 从MetricsManager获取当前队列深度
            queueDepth.addMetric(List.of(), MetricsManager.getQueueDepthValue());
            samples.add(queueDepth);

            // 活跃连接数
            GaugeMetricFamily activeConns = new GaugeMetricFamily(
                    "app_active_connections",
                    "Application active connections",
                    List.of()
            );
            activeConns.addMetric(List.of(), MetricsManager.getActiveConnectionsValue());
            samples.add(activeConns);

        } catch (Exception e) {
            System.err.println("[MetricsCollector] 收集应用指标异常: " + e.getMessage());
        }
    }

    /**
     * 注册此收集器到默认注册表
     *
     * @return 注册后的收集器实例
     */
    public MetricsCollector register() {
        return (MetricsCollector) this.register(io.prometheus.client.CollectorRegistry.defaultRegistry);
    }
}
