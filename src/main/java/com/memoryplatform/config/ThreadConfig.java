package com.memoryplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 统一线程池配置
 * <p>
 * 所有异步任务通过 Spring 管理的线程池执行，替代手动创建 ThreadPoolExecutor 的方式。
 * 提供三种执行器：
 * <ul>
 *   <li><b>virtualTaskExecutor</b>: 虚拟线程执行器，适合 I/O 密集型任务（Webhook发送、LLM调用等）</li>
 *   <li><b>boundedPoolExecutor</b>: 有界线程池，适合需要控制并发数的场景（并发写入消费等）</li>
 *   <li><b>scheduledExecutor</b>: 调度线程池，适合定时/周期任务（连接池驱逐、批量flush等）</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
@Configuration
public class ThreadConfig {

    /**
     * 虚拟线程执行器 —— 每个任务一个虚拟线程
     * <p>
     * 适用于：Webhook异步发送、LLM异步调用、WebSocket处理等 I/O 密集型场景。
     * 虚拟线程创建和切换开销极低，可以安全地创建大量并发任务。
     * </p>
     *
     * @return 虚拟线程执行器
     */
    @Bean("virtualTaskExecutor")
    public VirtualThreadTaskExecutor virtualTaskExecutor() {
        return new VirtualThreadTaskExecutor();
    }

    /**
     * 有界线程池执行器 —— 控制并发度
     * <p>
     * 适用于：并发写入服务的分片消费线程，需要限制并发数以保护下游存储服务。
     * 当队列满时使用 CallerRunsPolicy 策略，由调用线程执行任务，实现反压。
     * </p>
     *
     * @return 有界线程池执行器
     */
    @Bean("boundedPoolExecutor")
    public Executor boundedPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("bounded-");
        executor.setRejectedExecutionHandler(new ThreadPoolTaskExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 调度线程池执行器 —— 定时/周期任务
     * <p>
     * 适用于：连接池空闲检测、批量flush调度、心跳检测等周期性任务。
     * 使用虚拟线程实现调度器，降低线程资源占用。
     * </p>
     *
     * @return 调度线程池执行器
     */
    @Bean("scheduledExecutor")
    public ScheduledExecutorService scheduledExecutor() {
        return Executors.newScheduledThreadPool(
                4,
                Thread.ofVirtual().name("scheduled-", 0).factory()
        );
    }
}
