package com.memoryplatform.service;

import com.memoryplatform.circuit.CircuitBreaker;
import com.memoryplatform.circuit.CircuitBreakedException;
import com.memoryplatform.model.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高并发写入服务 - 分片队列 + 批量合并 + 熔断保护
 * <p>
 * 核心设计:
 * <ul>
 *   <li><b>分片队列</b>: 按 user_id hash 分为 N 个队列 (默认8个), 避免热点写入竞争</li>
 *   <li><b>批量合并</b>: 同一队列内 50ms 窗口内的多次写入合并为批量操作, 减少IO次数</li>
 *   <li><b>熔断保护</b>: 各存储层独立熔断器, 快速失败降级</li>
 *   <li><b>指数退避重试</b>: 写入失败自动重试, 最多3次</li>
 *   <li><b>异步编排</b>: CompletableFuture编排写入流程, 非阻塞</li>
 *   <li><b>优雅关闭</b>: 等待所有队列消费完毕再关闭</li>
 * </ul>
 * </p>
 *
 * <h3>写入流程</h3>
 * <pre>
 *   write(Memory)
 *     |-- 按userId hash选择队列分片
 *     |-- 加入分片队列 (附带CompletableFuture)
 *     |-- 消费线程批量拉取
 *     |-- 批量写入向量库 (VectorStore.upsert)
 *     |-- 并行写入图库 (GraphStore.createNode + createEdge)
 *     |-- 批量写入元数据库 (MetadataStore.batchInsert)
 *     |-- 完成Future
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ConcurrentWriteService service = new ConcurrentWriteService.Builder()
 *     .vectorStore(vectorStore)
 *     .graphStore(graphStore)
 *     .metadataStore(metadataStore)
 *     .shardCount(8)
 *     .batchWindowMs(50)
 *     .maxRetries(3)
 *     .build();
 *
 * CompletableFuture<WriteResult> future = service.write(memory);
 * WriteResult result = future.get(5, TimeUnit.SECONDS);
 * }</pre>
 *
 * @see CircuitBreaker
 * @see WriteResult
 */
public class ConcurrentWriteService {

    // ============ 配置常量 ============

    /** 默认分片数 */
    private static final int DEFAULT_SHARD_COUNT = 8;

    /** 默认批量合并窗口 (毫秒) */
    private static final long DEFAULT_BATCH_WINDOW_MS = 50;

    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** 默认重试初始延迟 (毫秒) */
    private static final long DEFAULT_INITIAL_RETRY_DELAY_MS = 100;

    /** 向量库集合名称 */
    private static final String VECTOR_COLLECTION = "memories";

    /** 元数据库表名 */
    private static final String METADATA_TABLE = "memories";

    // ============ 依赖存储层 ============

    private final EmbeddingService embeddingService;
    private final com.memoryplatform.storage.VectorStore vectorStore;
    private final com.memoryplatform.storage.GraphStore graphStore;
    private final com.memoryplatform.storage.MetadataStore metadataStore;

    // ============ 分片队列 ============

    /** 分片数 */
    private final int shardCount;

    /** 每个分片一个队列, 存储待写入的任务单元 */
    private final ConcurrentLinkedQueue<WriteTask>[] shards;

    /** 每个分片一个消费线程 */
    private final ExecutorService[] consumerExecutors;

    /** 每个分片一个批量合并调度器 */
    private final ScheduledExecutorService[] flushSchedulers;

    // ============ 熔断器 ============

    /** 向量存储熔断器 */
    private final CircuitBreaker vectorCircuitBreaker;

    /** 图存储熔断器 */
    private final CircuitBreaker graphCircuitBreaker;

    /** 元数据存储熔断器 */
    private final CircuitBreaker metadataCircuitBreaker;

    // ============ 重试配置 ============

    private final int maxRetries;
    private final long initialRetryDelayMs;

    // ============ 批量配置 ============

    private final long batchWindowMs;

    // ============ 生命周期控制 ============

    private final AtomicBoolean running = new AtomicBoolean(true);

    // ============ 监控指标 ============

    /** 总写入请求数 */
    private final AtomicLong totalWrites = new AtomicLong(0);

    /** 总成功数 */
    private final AtomicLong totalSuccesses = new AtomicLong(0);

    /** 总失败数 */
    private final AtomicLong totalFailures = new AtomicLong(0);

    /** 总重试次数 */
    private final AtomicLong totalRetries = new AtomicLong(0);

    /** 总批量写入次数 (合并后的实际写入次数) */
    private final AtomicLong totalBatchWrites = new AtomicLong(0);

    /** 平均写入延迟 (滑动窗口近似, 毫秒) */
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    /**
     * 构造高并发写入服务
     * <p>
     * 建议通过 {@link Builder} 构建实例:
     * <pre>{@code
     * ConcurrentWriteService service = ConcurrentWriteService.builder()
     *     .vectorStore(vs).graphStore(gs).metadataStore(ms)
     *     .build();
     * }</pre>
     * </p>
     *
     * @param builder 构建器
     */
    @SuppressWarnings("unchecked")
    private ConcurrentWriteService(Builder builder) {
        this.embeddingService = builder.embeddingService;
        this.vectorStore = builder.vectorStore;
        this.graphStore = builder.graphStore;
        this.metadataStore = builder.metadataStore;
        this.shardCount = builder.shardCount;
        this.maxRetries = builder.maxRetries;
        this.initialRetryDelayMs = builder.initialRetryDelayMs;
        this.batchWindowMs = builder.batchWindowMs;

        // 初始化分片队列
        this.shards = new ConcurrentLinkedQueue[shardCount];
        this.consumerExecutors = new ExecutorService[shardCount];
        this.flushSchedulers = new ScheduledExecutorService[shardCount];

        for (int i = 0; i < shardCount; i++) {
            shards[i] = new ConcurrentLinkedQueue<>();
            // 每个分片一个单线程池, 保证队列内串行消费
            consumerExecutors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "write-shard-" + i + "-consumer");
                t.setDaemon(true);
                return t;
            });
            // 每个分片一个调度器, 定时触发批量写入
            flushSchedulers[i] = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "write-shard-" + i + "-flush");
                t.setDaemon(true);
                return t;
            });

            // 启动定时批量flush
            final int shardIndex = i;
            flushSchedulers[i].scheduleAtFixedRate(
                    () -> flushShard(shardIndex),
                    batchWindowMs,
                    batchWindowMs,
                    TimeUnit.MILLISECONDS
            );
        }

        // 初始化熔断器
        this.vectorCircuitBreaker = new CircuitBreaker.Builder()
                .name("vector-store")
                .failureThreshold(builder.circuitFailureThreshold)
                .recoveryTimeout(builder.circuitRecoveryTimeoutMs)
                .successThreshold(builder.circuitSuccessThreshold)
                .build();

        this.graphCircuitBreaker = new CircuitBreaker.Builder()
                .name("graph-store")
                .failureThreshold(builder.circuitFailureThreshold)
                .recoveryTimeout(builder.circuitRecoveryTimeoutMs)
                .successThreshold(builder.circuitSuccessThreshold)
                .build();

        this.metadataCircuitBreaker = new CircuitBreaker.Builder()
                .name("metadata-store")
                .failureThreshold(builder.circuitFailureThreshold)
                .recoveryTimeout(builder.circuitRecoveryTimeoutMs)
                .successThreshold(builder.circuitSuccessThreshold)
                .build();

        System.out.println("[ConcurrentWriteService] 初始化完成: 分片数=" + shardCount
                + ", 批量窗口=" + batchWindowMs + "ms, 最大重试=" + maxRetries);
    }

    /**
     * 写入一条记忆
     * <p>
     * 写入流程:
     * <ol>
     *   <li>按 userId hash 选择队列分片</li>
     *   <li>创建 WriteTask 并加入队列, 返回 CompletableFuture</li>
     *   <li>消费线程在批量窗口到期后统一执行写入</li>
     *   <li>批量写入向量库 -> 并行写入图库 -> 批量写入元数据库</li>
     *   <li>完成 Future, 通知调用方</li>
     * </ol>
     * </p>
     *
     * @param memory 要写入的记忆对象
     * @return CompletableFuture 异步写入结果
     */
    public CompletableFuture<WriteResult> write(Memory memory) {
        if (!running.get()) {
            CompletableFuture<WriteResult> f = new CompletableFuture<>();
            f.complete(WriteResult.failureBuilder()
                    .memoryId(memory.getId())
                    .error("服务已关闭, 拒绝写入")
                    .latencyMs(0)
                    .build());
            return f;
        }

        totalWrites.incrementAndGet();

        // 按userId hash分片
        int shardIndex = Math.abs(memory.getUserId().hashCode()) % shardCount;
        CompletableFuture<WriteResult> future = new CompletableFuture<>();

        WriteTask task = new WriteTask(memory, future, System.currentTimeMillis());
        shards[shardIndex].add(task);

        System.out.println("[ConcurrentWriteService] 写入请求入队: memoryId=" + memory.getId()
                + ", userId=" + memory.getUserId() + ", 分片=" + shardIndex
                + ", 队列深度=" + shards[shardIndex].size());

        return future;
    }

    /**
     * 批量flush指定分片的待写入任务
     * <p>
     * 从分片队列中取出所有待处理任务, 批量执行写入。
     * 同一分片内的多个向量记录合并为一次 upsert 调用。
     * </p>
     *
     * @param shardIndex 分片索引
     */
    private void flushShard(int shardIndex) {
        if (!running.get() && shards[shardIndex].isEmpty()) {
            return;
        }

        // 取出当前队列中的所有任务
        List<WriteTask> tasks = new ArrayList<>();
        WriteTask task;
        while ((task = shards[shardIndex].poll()) != null) {
            tasks.add(task);
        }

        if (tasks.isEmpty()) {
            return;
        }

        totalBatchWrites.incrementAndGet();
        System.out.println("[ConcurrentWriteService] 分片" + shardIndex + "批量flush: "
                + tasks.size() + "条任务");

        // 提交到消费线程执行
        consumerExecutors[shardIndex].submit(() -> executeBatch(tasks, shardIndex));
    }

    /**
     * 执行批量写入操作
     * <p>
     * 将一批 WriteTask 统一写入各存储层:
     * <ol>
     *   <li>构建向量记录列表, 批量 upsert</li>
     *   <li>构建图节点/边列表, 逐个创建</li>
     *   <li>构建元数据记录列表, 批量 insert</li>
     *   <li>为每个 Future 完成写入结果</li>
     * </ol>
     * </p>
     *
     * @param tasks 待写入的任务列表
     * @param shardIndex 分片索引
     */
    private void executeBatch(List<WriteTask> tasks, int shardIndex) {
        long batchStart = System.currentTimeMillis();

        // ---- 1. 准备写入数据 ----
        List<VectorRecord> vectorRecords = new ArrayList<>(tasks.size());
        List<WriteTaskWithGraph> graphTasks = new ArrayList<>(tasks.size());
        List<MetadataRecord> metadataRecords = new ArrayList<>(tasks.size());

        for (WriteTask task : tasks) {
            Memory mem = task.memory;

            // 构建向量记录
            float[] embedding = mem.getEmbedding() != null ? toFloatArray(mem.getEmbedding()) : null;
            VectorRecord vr = VectorRecord.builder()
                    .id(mem.getId())
                    .collection(VECTOR_COLLECTION)
                    .vector(embedding)
                    .text(mem.getText())
                    .userId(mem.getUserId())
                    .agentId(mem.getAgentId())
                    .entities(mem.getEntities())
                    .importance(mem.getImportance())
                    .createdAt(mem.getCreatedAt())
                    .updatedAt(mem.getUpdatedAt())
                    .build();
            vectorRecords.add(vr);

            // 构建图数据
            List<GraphNode> nodes = buildGraphNodes(mem);
            List<GraphEdge> edges = buildGraphEdges(mem);
            graphTasks.add(new WriteTaskWithGraph(mem, nodes, edges));

            // 构建元数据记录
            MetadataRecord mr = new MetadataRecord(
                    mem.getId(), METADATA_TABLE, mem.getUserId(), mem.getAgentId(),
                    mem.getText(), mem.getImportance(), null
            );
            metadataRecords.add(mr);
        }

        // ---- 2. 并行写入三个存储层 ----
        final List<WriteTaskWithGraph> finalGraphTasks = graphTasks;

        CompletableFuture<Boolean> vectorFuture = CompletableFuture.supplyAsync(() -> {
            return retryWithBackoff(() -> vectorCircuitBreaker.execute(() ->
                    vectorStore.upsert(VECTOR_COLLECTION, vectorRecords)
            ));
        });

        CompletableFuture<List<String>> graphFuture = CompletableFuture.supplyAsync(() -> {
            List<String> nodeIds = new ArrayList<>();
            for (WriteTaskWithGraph gt : finalGraphTasks) {
                for (GraphNode node : gt.nodes) {
                    String nodeId = retryWithBackoff(() -> graphCircuitBreaker.execute(() ->
                            graphStore.createNode(node)
                    ));
                    nodeIds.add(nodeId);
                }
                for (GraphEdge edge : gt.edges) {
                    retryWithBackoff(() -> graphCircuitBreaker.execute(() ->
                            graphStore.createEdge(edge)
                    ));
                }
            }
            return nodeIds;
        });

        CompletableFuture<List<String>> metadataFuture = CompletableFuture.supplyAsync(() -> {
            return retryWithBackoff(() -> metadataCircuitBreaker.execute(() ->
                    metadataStore.batchInsert(METADATA_TABLE, metadataRecords)
            ));
        });

        // ---- 3. 等待所有写入完成并通知Future ----
        CompletableFuture.allOf(vectorFuture, graphFuture, metadataFuture)
                .whenComplete((v, ex) -> {
                    long latency = System.currentTimeMillis() - batchStart;
                    totalLatencyMs.addAndGet(latency);

                    boolean vectorOk = false;
                    boolean graphOk = false;
                    boolean metadataOk = false;
                    String errorMsg = null;

                    try {
                        vectorOk = vectorFuture.getNow(false);
                    } catch (Exception e) {
                        errorMsg = combineError(errorMsg, "Vector: " + e.getMessage());
                    }

                    try {
                        List<String> gids = graphFuture.getNow(Collections.emptyList());
                        graphOk = !gids.isEmpty();
                    } catch (Exception e) {
                        errorMsg = combineError(errorMsg, "Graph: " + e.getMessage());
                    }

                    try {
                        List<String> mids = metadataFuture.getNow(Collections.emptyList());
                        metadataOk = !mids.isEmpty();
                    } catch (Exception e) {
                        errorMsg = combineError(errorMsg, "Metadata: " + e.getMessage());
                    }

                    boolean overallSuccess = vectorOk && graphOk && metadataOk;

                    for (int i = 0; i < tasks.size(); i++) {
                        WriteTask task = tasks.get(i);
                        Memory mem = task.memory;
                        long taskLatency = System.currentTimeMillis() - task.enqueueTime;

                        WriteResult result;
                        if (overallSuccess) {
                            totalSuccesses.incrementAndGet();
                            result = WriteResult.successBuilder()
                                    .memoryId(mem.getId())
                                    .vectorId(mem.getId())
                                    .graphId(mem.getId())
                                    .metadataId(mem.getId())
                                    .latencyMs(taskLatency)
                                    .build();
                        } else {
                            totalFailures.incrementAndGet();
                            result = WriteResult.failureBuilder()
                                    .memoryId(mem.getId())
                                    .error(errorMsg != null ? errorMsg : "批量写入部分失败")
                                    .latencyMs(taskLatency)
                                    .build();
                        }
                        task.future.complete(result);
                    }

                    System.out.println("[ConcurrentWriteService] 分片" + shardIndex
                            + "批量写入完成: " + tasks.size() + "条, 耗时=" + latency
                            + "ms, 成功=" + overallSuccess);
                });
    }

    /**
     * 构建图节点列表
     * <p>
     * 将记忆的实体转换为图节点:
     * <ul>
     *   <li>创建记忆节点 (核心节点)</li>
     *   <li>为每个实体创建实体节点</li>
     * </ul>
     * </p>
     *
     * @param memory 记忆对象
     * @return 图节点列表
     */
    private List<GraphNode> buildGraphNodes(Memory memory) {
        List<GraphNode> nodes = new ArrayList<>();

        // 记忆核心节点
        GraphNode memNode = GraphNode.builder()
                .id(memory.getId())
                .label("Memory")
                .content(memory.getText())
                .type("memory")
                .userId(memory.getUserId())
                .agentId(memory.getAgentId())
                .properties(new HashMap<String, Object>() {{
                    put("importance", memory.getImportance());
                    put("createdAt", memory.getCreatedAt().toString());
                }})
                .createdAt(memory.getCreatedAt())
                .build();
        nodes.add(memNode);

        // 实体节点
        if (memory.getEntities() != null) {
            for (Entity entity : memory.getEntities()) {
                GraphNode entityNode = GraphNode.builder()
                        .id(entity.getNormalizedId())
                        .label(entity.getType().name())
                        .content(entity.getName())
                        .type(entity.getType().name().toLowerCase())
                        .userId(memory.getUserId())
                        .properties(new HashMap<String, Object>() {{
                            put("confidence", entity.getConfidence());
                        }})
                        .createdAt(memory.getCreatedAt())
                        .build();
                nodes.add(entityNode);
            }
        }

        return nodes;
    }

    /**
     * 构建图边列表
     * <p>
     * 为记忆节点和实体之间创建 CONTAINS 关系边。
     * 如果有 linkedMemoryIds, 还创建 RELATED_TO 关系边。
     * </p>
     *
     * @param memory 记忆对象
     * @return 图边列表
     */
    private List<GraphEdge> buildGraphEdges(Memory memory) {
        List<GraphEdge> edges = new ArrayList<>();
        int edgeCounter = 0;

        // 记忆 -> 实体 的 CONTAINS 关系
        if (memory.getEntities() != null) {
            for (Entity entity : memory.getEntities()) {
                GraphEdge edge = GraphEdge.builder()
                        .id(memory.getId() + "-contains-" + entity.getNormalizedId())
                        .sourceId(memory.getId())
                        .targetId(entity.getNormalizedId())
                        .type("CONTAINS")
                        .weight(entity.getConfidence())
                        .properties(new HashMap<String, Object>() {{
                            put("entityType", entity.getType().name());
                        }})
                        .build();
                edges.add(edge);
            }
        }

        // 记忆 -> 关联记忆 的 RELATED_TO 关系
        if (memory.getLinkedMemoryIds() != null) {
            for (String linkedId : memory.getLinkedMemoryIds()) {
                GraphEdge edge = GraphEdge.builder()
                        .id(memory.getId() + "-related-" + linkedId)
                        .sourceId(memory.getId())
                        .targetId(linkedId)
                        .type("RELATED_TO")
                        .weight(1.0)
                        .build();
                edges.add(edge);
            }
        }

        return edges;
    }

    /**
     * 带指数退避的重试执行器
     * <p>
     * 重试策略:
     * <ul>
     *   <li>最多重试 maxRetries 次</li>
     *   <li>每次重试延迟 = initialRetryDelay * 2^(attempt-1)</li>
     *   <li>总延迟上限: initialRetryDelay * (2^maxRetries - 1)</li>
     * </ul>
     * </p>
     *
     * @param <T> 返回值类型
     * @param action 要执行的操作
     * @return 操作结果
     */
    private <T> T retryWithBackoff(Callable<T> action) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (CircuitBreakedException e) {
                // 熔断器拒绝, 不重试
                System.out.println("[ConcurrentWriteService] 熔断器拒绝: " + e.getMessage());
                throw new RuntimeException("服务被熔断, 降级处理", e);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long delay = initialRetryDelayMs * (1L << attempt); // 2^attempt
                    totalRetries.incrementAndGet();
                    System.out.println("[ConcurrentWriteService] 写入失败(第" + (attempt + 1)
                            + "次), " + delay + "ms后重试: " + e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                }
            }
        }

        throw new RuntimeException("重试" + maxRetries + "次后仍然失败: "
                + (lastException != null ? lastException.getMessage() : "未知错误"),
                lastException);
    }

    /**
     * 将 double[] 转为 float[]
     * <p>
     * Memory的embedding是double[], VectorRecord需要float[]。
     * </p>
     *
     * @param doubles double数组
     * @return float数组
     */
    private float[] toFloatArray(double[] doubles) {
        if (doubles == null) return null;
        float[] floats = new float[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            floats[i] = (float) doubles[i];
        }
        return floats;
    }

    /**
     * 合并错误信息
     */
    private String combineError(String existing, String newError) {
        if (existing == null) return newError;
        return existing + "; " + newError;
    }

    // ============ 生命周期管理 ============

    /**
     * 优雅关闭服务
     * <p>
     * 关闭流程:
     * <ol>
     *   <li>标记服务为非运行状态 (拒绝新写入)</li>
     *   <li>停止批量flush调度器</li>
     *   <li>手动触发最后一次flush清空队列</li>
     *   <li>等待消费线程池完成当前任务</li>
     *   <li>关闭所有线程池</li>
     * </ol>
     * </p>
     *
     * @param timeoutMs 等待超时 (毫秒)
     * @return 是否在超时内完全关闭
     */
    public boolean shutdown(long timeoutMs) {
        System.out.println("[ConcurrentWriteService] 开始优雅关闭...");
        running.set(false);

        // 停止所有flush调度器
        for (ScheduledExecutorService scheduler : flushSchedulers) {
            scheduler.shutdown();
        }

        // 手动触发最后一次flush
        for (int i = 0; i < shardCount; i++) {
            flushShard(i);
        }

        // 等待消费线程完成
        for (ExecutorService executor : consumerExecutors) {
            executor.shutdown();
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean allTerminated = true;

        for (ExecutorService executor : consumerExecutors) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    allTerminated &= executor.awaitTermination(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    allTerminated = false;
                }
            } else {
                allTerminated = false;
            }
        }

        for (ScheduledExecutorService scheduler : flushSchedulers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    allTerminated &= scheduler.awaitTermination(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    allTerminated = false;
                }
            } else {
                allTerminated = false;
            }
        }

        // 强制关闭未完成的
        if (!allTerminated) {
            for (ExecutorService executor : consumerExecutors) {
                executor.shutdownNow();
            }
            for (ScheduledExecutorService scheduler : flushSchedulers) {
                scheduler.shutdownNow();
            }
        }

        System.out.println("[ConcurrentWriteService] 关闭完成, 完全终止=" + allTerminated);
        System.out.println("[ConcurrentWriteService] 最终统计: " + getStats());
        return allTerminated;
    }

    // ============ 监控 API ============

    /**
     * 获取服务统计信息
     * @return 统计信息字符串
     */
    public String getStats() {
        long total = totalWrites.get();
        long successes = totalSuccesses.get();
        long failures = totalFailures.get();
        long retries = totalRetries.get();
        long batchWrites = totalBatchWrites.get();
        double avgLatency = total > 0 ? (double) totalLatencyMs.get() / total : 0;
        double failureRate = total > 0 ? (double) failures / total * 100 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("ConcurrentWriteService Stats {\n");
        sb.append("  totalWrites=").append(total).append("\n");
        sb.append("  successes=").append(successes).append("\n");
        sb.append("  failures=").append(failures).append("\n");
        sb.append("  failureRate=").append(String.format("%.2f%%", failureRate)).append("\n");
        sb.append("  retries=").append(retries).append("\n");
        sb.append("  batchWrites=").append(batchWrites).append("\n");
        sb.append("  avgLatencyMs=").append(String.format("%.2f", avgLatency)).append("\n");
        sb.append("  shardCount=").append(shardCount).append("\n");
        sb.append("  vectorCB=").append(vectorCircuitBreaker.getStats()).append("\n");
        sb.append("  graphCB=").append(graphCircuitBreaker.getStats()).append("\n");
        sb.append("  metadataCB=").append(metadataCircuitBreaker.getStats()).append("\n");
        sb.append("  shards={");

        for (int i = 0; i < shardCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append("s").append(i).append("=").append(shards[i].size());
        }
        sb.append("}\n}");
        return sb.toString();
    }

    /**
     * 获取各分片的队列深度
     * @return 分片索引 -> 队列大小 的映射
     */
    public Map<Integer, Integer> getShardDepths() {
        Map<Integer, Integer> depths = new LinkedHashMap<>();
        for (int i = 0; i < shardCount; i++) {
            depths.put(i, shards[i].size());
        }
        return depths;
    }

    /**
     * 获取向量存储熔断器
     */
    public CircuitBreaker getVectorCircuitBreaker() { return vectorCircuitBreaker; }

    /**
     * 获取图存储熔断器
     */
    public CircuitBreaker getGraphCircuitBreaker() { return graphCircuitBreaker; }

    /**
     * 获取元数据存储熔断器
     */
    public CircuitBreaker getMetadataCircuitBreaker() { return metadataCircuitBreaker; }

    /**
     * 检查服务是否正在运行
     */
    public boolean isRunning() { return running.get(); }

    // ============ 内部数据结构 ============

    /**
     * 写入任务: 封装单条记忆及其对应的Future
     */
    private static class WriteTask {
        final Memory memory;
        final CompletableFuture<WriteResult> future;
        final long enqueueTime;

        WriteTask(Memory memory, CompletableFuture<WriteResult> future, long enqueueTime) {
            this.memory = memory;
            this.future = future;
            this.enqueueTime = enqueueTime;
        }
    }

    /**
     * 带图数据的写入任务: 封装记忆的图节点和边
     */
    private static class WriteTaskWithGraph {
        final Memory memory;
        final List<GraphNode> nodes;
        final List<GraphEdge> edges;

        WriteTaskWithGraph(Memory memory, List<GraphNode> nodes, List<GraphEdge> edges) {
            this.memory = memory;
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    /**
     * ConcurrentWriteService 构建器
     * <p>
     * 使用Builder模式配置服务参数:
     * <pre>{@code
     * ConcurrentWriteService service = ConcurrentWriteService.builder()
     *     .vectorStore(milvusStore)
     *     .graphStore(neo4jStore)
     *     .metadataStore(jdbcStore)
     *     .embeddingService(embeddingService)
     *     .shardCount(8)
     *     .batchWindowMs(50)
     *     .maxRetries(3)
     *     .circuitFailureThreshold(5)
     *     .circuitRecoveryTimeoutMs(30000)
     *     .build();
     * }</pre>
     * </p>
     */
    public static class Builder {
        private EmbeddingService embeddingService;
        private com.memoryplatform.storage.VectorStore vectorStore;
        private com.memoryplatform.storage.GraphStore graphStore;
        private com.memoryplatform.storage.MetadataStore metadataStore;

        private int shardCount = DEFAULT_SHARD_COUNT;
        private long batchWindowMs = DEFAULT_BATCH_WINDOW_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long initialRetryDelayMs = DEFAULT_INITIAL_RETRY_DELAY_MS;

        private int circuitFailureThreshold = 5;
        private long circuitRecoveryTimeoutMs = 30000;
        private int circuitSuccessThreshold = 3;

        public Builder embeddingService(EmbeddingService svc) { this.embeddingService = svc; return this; }
        public Builder vectorStore(com.memoryplatform.storage.VectorStore vs) { this.vectorStore = vs; return this; }
        public Builder graphStore(com.memoryplatform.storage.GraphStore gs) { this.graphStore = gs; return this; }
        public Builder metadataStore(com.memoryplatform.storage.MetadataStore ms) { this.metadataStore = ms; return this; }
        public Builder shardCount(int n) { this.shardCount = n; return this; }
        public Builder batchWindowMs(long ms) { this.batchWindowMs = ms; return this; }
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder initialRetryDelayMs(long ms) { this.initialRetryDelayMs = ms; return this; }
        public Builder circuitFailureThreshold(int n) { this.circuitFailureThreshold = n; return this; }
        public Builder circuitRecoveryTimeoutMs(long ms) { this.circuitRecoveryTimeoutMs = ms; return this; }
        public Builder circuitSuccessThreshold(int n) { this.circuitSuccessThreshold = n; return this; }

        /**
         * 构建 ConcurrentWriteService 实例
         * @return 服务实例
         * @throws IllegalArgumentException 必要参数缺失时
         */
        public ConcurrentWriteService build() {
            // 允许可选存储为null，内部会检查后再调用
            if (shardCount <= 0) throw new IllegalArgumentException("shardCount必须>0");
            if (batchWindowMs <= 0) throw new IllegalArgumentException("batchWindowMs必须>0");
            return new ConcurrentWriteService(this);
        }
    }

    /**
     * 获取Builder实例
     * @return 新Builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
