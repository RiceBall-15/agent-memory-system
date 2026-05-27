package com.memoryplatform.config;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 虚拟线程任务执行器
 * <p>
 * 基于 {@link Executors#newVirtualThreadPerTaskExecutor()} 的轻量级包装，
 * 为每个提交的任务创建一个虚拟线程，适合 I/O 密集型异步任务。
 * </p>
 *
 * <p>虚拟线程的优势：</p>
 * <ul>
 *   <li>极低的创建开销（~几KB，而平台线程~1MB）</li>
 *   <li>可创建数百万个并发虚拟线程</li>
 *   <li>自动适配 I/O 阻塞场景</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 */
public class VirtualThreadTaskExecutor implements Executor {

    private final Executor delegate = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    /**
     * 提交 Callable 任务并返回 CompletableFuture
     *
     * @param task 要执行的任务
     * @param <T>  返回值类型
     * @return CompletableFuture 结果
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, delegate);
    }

    /**
     * 关闭执行器（释放虚拟线程资源）
     */
    public void shutdown() {
        if (delegate instanceof java.util.concurrent.ExecutorService es) {
            es.shutdown();
        }
    }
}
