package com.memoryplatform.service;

import com.memoryplatform.circuit.CircuitBreakedException;
import com.memoryplatform.circuit.ResilienceCircuitBreaker;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryType;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 高并发写入服务 - 分片队列 + 批量合并 + 熔断保护
 *
 * <p>核心设计:
 * <ul>
 *   <li><b>分片队列</b>: 按 user_id hash 分为 N 个队列 (默认8个), 避免热点写入竞争</li>
 *   <li><b>批量合并</b>: 同一队列内 50ms 窗口内的多次写入合并为批量操作, 减少IO次数</li>
 *   <li><b>熔断保护</b>: 各存储层独立熔断器, 快速失败降级</li>
 *   <li><b>指数退避重试</b>: 写入失败自动重试, 最多3次</li>
 *   <li><b>异步编排</b>: CompletableFuture编排写入流程, 非阻塞</li>
 *   <li><b>优雅关闭</b>: 等待所有队列消费完毕再关闭</li>
 * </ul>
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
 * @see ResilienceCircuitBreaker
 * @see WriteResult
 */
@Slf4j
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
    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final MetadataStore metadataStore;

    // ============ 配置属性 ============

    @Value("${app.concurrent-write.shard-count:" + DEFAULT_SHARD_COUNT + "}")
    private final int shardCount = DEFAULT_SHARD_COUNT;

    @Value("${app.concurrent-write.batch-window-ms:" + DEFAULT_BATCH_WINDOW_MS + "}")
    private final long batchWindowMs = DEFAULT_BATCH_WINDOW_MS;

    @Value("${app.concurrent-write.max-retries:" + DEFAULT_MAX_RETRIES + "}")
    private final int maxRetries = DEFAULT_MAX_RETRIES;

    @Value("${app.concurrent-write.initial-retry-delay-ms:" + DEFAULT_INITIAL_RETRY_DELAY_MS + "}")
    private final long initialRetryDelayMs = DEFAULT_INITIAL_RETRY_DELAY_MS;



    // ============ 分片队列 ============

    /** 每个分片一个队列, 存储待写入的任务单元 */
    private final ConcurrentLinkedQueue<WriteTask>[] shards;

    /** 有界线程池 —— 消费分片批量写入任务 */
    private final Executor boundedExecutor;

    /** 调度线程池 —— 定时批量flush */
    private final ScheduledExecutorService scheduledExecutor;

    /** 每个分片的flush调度Future */
    private final java.util.concurrent.ScheduledFuture<?>[] flushFutures;

    // ============ 熔断器 ============

    /** Resilience4j熔断器适配层 */
    private final ResilienceCircuitBreaker resilienceCircuitBreaker;

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
     * Spring依赖注入构造器
     */
    @SuppressWarnings("unchecked")
    public ConcurrentWriteService(
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            GraphStore graphStore,
            MetadataStore metadataStore,
            ResilienceCircuitBreaker resilienceCircuitBreaker,
            @Qualifier("boundedPoolExecutor") Executor boundedExecutor,
            @Qualifier("scheduledExecutor") ScheduledExecutorService scheduledExecutor
    ) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.metadataStore = metadataStore;
        this.resilienceCircuitBreaker = resilienceCircuitBreaker;
        this.boundedExecutor = boundedExecutor;
        this.scheduledExecutor = scheduledExecutor;

        // 初始化分片队列
        this.shards = new ConcurrentLinkedQueue[shardCount];
        this.flushFutures = new java.util.concurrent.ScheduledFuture<?>[shardCount];

        for (int i = 0; i < shardCount; i++) {
            shards[i] = new ConcurrentLinkedQueue<>();
            final int shardIndex = i;
            flushFutures[i] = scheduledExecutor.scheduleAtFixedRate(
                    () -> flushShard(shardIndex),
                    batchWindowMs,
                    batchWindowMs,
                    TimeUnit.MILLISECONDS
            );
        }



        log.info("[ConcurrentWriteService] 初始化完成: 分片数={}, 批量窗口={}ms, 最大重试={}",
                shardCount, batchWindowMs, maxRetries);
    }

    /**
     * 写入一条记忆
     *
     * <p>写入流程:
     * <ol>
     *   <li>按 userId hash 选择队列分片</li>
     *   <li>创建 WriteTask 并加入队列, 返回 CompletableFuture</li>
     *   <li>消费线程在批量窗口到期后统一执行写入</li>
     *   <li>批量写入向量库 -> 并行写入图库 -> 批量写入元数据库</li>
     *   <li>完成 Future, 通知调用方</li>
     * </ol>
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

        log.debug("写入请求入队: memoryId={}, userId={}, 分片={}, 队列深度={}",
                memory.getId(), memory.getUserId(), shardIndex, shards[shardIndex].size());

        return future;
    }

    /**
     * 批量flush指定分片的待写入任务
     *
     * <p>从分片队列中取出所有待处理任务, 批量执行写入。
     * 同一分片内的多个向量记录合并为一次 upsert 调用。
     *
     * @param shardIndex 分片索引
     */
    private void flushShard(int shardIndex) {
        if (!running.get() && shards[shardIndex].isEmpty()) {
            return;
        }

        List<WriteTask> tasks = new ArrayList<>();
        WriteTask task;
        while ((task = shards[shardIndex].poll()) != null) {
            tasks.add(task);
        }

        if (tasks.isEmpty()) {
            return;
        }

        totalBatchWrites.incrementAndGet();
        log.debug("分片{}批量flush: {}条任务", shardIndex, tasks.size());

        boundedExecutor.execute(() -> executeBatch(tasks, shardIndex));
    }

    /**
     * 执行批量写入操作
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

        CompletableFuture<Boolean> vectorFuture = CompletableFuture.supplyAsync(() ->
                retryWithBackoff(() -> resilienceCircuitBreaker.execute("vector-store", () ->
                        vectorStore.upsert(VECTOR_COLLECTION, vectorRecords)
                )), boundedExecutor
        );

        CompletableFuture<List<String>> graphFuture = CompletableFuture.supplyAsync(() -> {
            List<String> nodeIds = new ArrayList<>();
            for (WriteTaskWithGraph gt : finalGraphTasks) {
                for (GraphNode node : gt.nodes) {
                    String nodeId = retryWithBackoff(() -> resilienceCircuitBreaker.execute("graph-store", () ->
                            graphStore.createNode(node)
                    ));
                    nodeIds.add(nodeId);
                }
                for (GraphEdge edge : gt.edges) {
                    retryWithBackoff(() -> resilienceCircuitBreaker.execute("graph-store", () ->
                            graphStore.createEdge(edge)
                    ));
                }
            }
            return nodeIds;
        }, boundedExecutor);

        CompletableFuture<List<String>> metadataFuture = CompletableFuture.supplyAsync(() ->
                retryWithBackoff(() -> resilienceCircuitBreaker.execute("metadata-store", () ->
                        metadataStore.batchInsert(METADATA_TABLE, metadataRecords)
                )), boundedExecutor
        );

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
                        WriteTask wt = tasks.get(i);
                        Memory mem = wt.memory;
                        long taskLatency = System.currentTimeMillis() - wt.enqueueTime;

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
                        wt.future.complete(result);
                    }

                    log.debug("分片{}批量写入完成: {}条, 耗时={}ms, 成功={}",
                            shardIndex, tasks.size(), latency, overallSuccess);
                });
    }

    /**
     * 构建图节点列表
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
                .properties(new HashMap<>() {{
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
                        .properties(new HashMap<>() {{
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
     */
    private List<GraphEdge> buildGraphEdges(Memory memory) {
        List<GraphEdge> edges = new ArrayList<>();

        // 记忆 -> 实体 的 CONTAINS 关系
        if (memory.getEntities() != null) {
            for (Entity entity : memory.getEntities()) {
                GraphEdge edge = GraphEdge.builder()
                        .id(memory.getId() + "-contains-" + entity.getNormalizedId())
                        .sourceId(memory.getId())
                        .targetId(entity.getNormalizedId())
                        .type("CONTAINS")
                        .weight(entity.getConfidence())
                        .properties(new HashMap<>() {{
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
     */
    private <T> T retryWithBackoff(Callable<T> action) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (CircuitBreakedException e) {
                log.error("熔断器拒绝: {}", e.getMessage());
                throw new RuntimeException("服务被熔断, 降级处理", e);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long delay = initialRetryDelayMs * (1L << attempt);
                    totalRetries.incrementAndGet();
                    log.warn("写入失败(第{}次), {}ms后重试: {}", attempt + 1, delay, e.getMessage());
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
     *
     * <p>关闭流程:
     * <ol>
     *   <li>标记服务为非运行状态 (拒绝新写入)</li>
     *   <li>停止批量flush调度器</li>
     *   <li>手动触发最后一次flush清空队列</li>
     *   <li>等待消费线程池完成当前任务</li>
     *   <li>关闭所有线程池</li>
     * </ol>
     *
     * @param timeoutMs 等待超时 (毫秒)
     * @return 是否在超时内完全关闭
     */
    @PreDestroy
    public boolean shutdown(long timeoutMs) {
        log.info("开始优雅关闭...");
        running.set(false);

        for (java.util.concurrent.ScheduledFuture<?> future : flushFutures) {
            if (future != null) {
                future.cancel(false);
            }
        }

        for (int i = 0; i < shardCount; i++) {
            flushShard(i);
        }

        for (ExecutorService executor : consumerExecutors) {
            executor.shutdown();
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean allTerminated = true;

        // 关闭调度线程池
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            allTerminated = false;
        }
        }

        if (!allTerminated) {
            for (ExecutorService executor : consumerExecutors) {
                executor.shutdownNow();
            }
            for (ScheduledExecutorService scheduler : flushSchedulers) {
                scheduler.shutdownNow();
            }
        }

        log.info("关闭完成, 完全终止={}, 最终统计: {}", allTerminated, getStats());
        return allTerminated;
    }

    /**
     * 无参关闭（默认超时10秒）
     */
    @PreDestroy
    public void shutdown() {
        shutdown(10000);
    }

    // ============ 监控 API ============

    /**
     * 获取服务统计信息
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
        sb.append("  vectorCB=").append(resilienceCircuitBreaker.getStats("vector-store")).append("\n");
        sb.append("  graphCB=").append(resilienceCircuitBreaker.getStats("graph-store")).append("\n");
        sb.append("  metadataCB=").append(resilienceCircuitBreaker.getStats("metadata-store")).append("\n");
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
     */
    public Map<Integer, Integer> getShardDepths() {
        Map<Integer, Integer> depths = new LinkedHashMap<>();
        for (int i = 0; i < shardCount; i++) {
            depths.put(i, shards[i].size());
        }
        return depths;
    }

    /**
     * 获取Resilience4j熔断器适配层
     */
    public ResilienceCircuitBreaker getResilienceCircuitBreaker() { return resilienceCircuitBreaker; }

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
}
