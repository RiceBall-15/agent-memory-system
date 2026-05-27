package com.memoryplatform;

import com.memoryplatform.config.ApiConfig;
import com.memoryplatform.config.ApplicationConfig;
import com.memoryplatform.extractor.EntityExtractor;
import com.memoryplatform.extractor.TimeParser;
import com.memoryplatform.handler.AdminHandler;
import com.memoryplatform.handler.BatchHandler;
import com.memoryplatform.handler.ImportExportHandler;
import com.memoryplatform.handler.HealthHandler;
import com.memoryplatform.handler.MemoryHandler;
import com.memoryplatform.handler.SearchHandler;
import com.memoryplatform.llm.LlmClient;
import com.memoryplatform.metrics.MetricsHttpServer;
import com.memoryplatform.server.CorsMiddleware;
import com.memoryplatform.server.AuthMiddleware;
import com.memoryplatform.server.LoggingMiddleware;
import com.memoryplatform.server.MemoryHttpServer;
import com.memoryplatform.server.Router;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.EmbeddingService;
import com.memoryplatform.service.HybridRetrievalService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.service.MemoryDeduplicationService;
import com.memoryplatform.service.MemoryTtlService;
import com.memoryplatform.service.MemoryDecayService;
import com.memoryplatform.service.MemorySharingService;
import com.memoryplatform.storage.StorageFactory;
import com.memoryplatform.storage.VectorStore;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;

import com.memoryplatform.server.GracefulShutdown;
import com.memoryplatform.cache.LRUCache;
import com.memoryplatform.websocket.WebSocketServer;

import java.io.IOException;

/**
 * Agent Memory Platform - 应用主入口
 *
 * <p>负责整个系统的初始化、装配和生命周期管理。启动流程：</p>
 * <ol>
 *   <li>加载配置文件 application.json</li>
 *   <li>初始化存储层（向量库、图库、元数据库）</li>
 *   <li>创建LLM客户端和Embedding服务</li>
 *   <li>创建核心服务层（记忆提取、高并发写入、混合检索）</li>
 *   <li>创建处理器层（记忆CRUD、搜索、健康检查）</li>
 *   <li>创建Router并注册所有API路由</li>
 *   <li>启动HTTP服务器和Prometheus指标服务器</li>
 *   <li>注册JVM ShutdownHook进行优雅关闭</li>
 * </ol>
 *
 * <h3>架构分层</h3>
 * <pre>
 *   Application (入口 & 组装)
 *       ├── StorageFactory  → VectorStore, GraphStore, MetadataStore
 *       ├── LlmClient      → LLM调用
 *       ├── EmbeddingService → 向量化
 *       ├── Services        → Extraction, ConcurrentWrite, HybridRetrieval
 *       ├── Handlers        → Memory, Search, Health
 *       ├── Router          → 路由 & 中间件
 *       └── Servers         → HTTP, Metrics
 * </pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class Application {

    /** 应用启动时间 */
    private static final long START_TIME = System.currentTimeMillis();

    /** 应用版本 */
    private static final String VERSION = "1.0.0";

    /** HTTP服务器实例 */
    private MemoryHttpServer httpServer;

    /** Metrics服务器实例 */
    private MetricsHttpServer metricsServer;

    /** 优雅停机管理器 */
    private GracefulShutdown gracefulShutdown;

    /** WebSocket服务器 */
    private WebSocketServer webSocketServer;

    /** 记忆处理器（用于WebSocket绑定） */
    private MemoryHandler memoryHandler;

    /** LRU缓存（用于元数据缓存） */
    private LRUCache<String, Object> metadataCache;

    /** JVM内存监控线程 */
    private Thread memoryMonitorThread;
    /** 记忆去重服务 */
    private MemoryDeduplicationService deduplicationService;
    /** TTL过期服务 */
    private MemoryTtlService ttlService;
    /** 记忆衰减服务 */
    private MemoryDecayService decayService;
    /** 记忆共享服务 */
    private MemorySharingService sharingService;

    /**
     * 主入口方法
     *
     * @param args 命令行参数（暂不支持）
     */
    public static void main(String[] args) {
        Application app = new Application();
        try {
            app.start(args);
        } catch (Exception e) {
            System.err.println("[Application] 启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 启动应用
     *
     * @param args 命令行参数
     */
    private void start(String[] args) throws Exception {
        printBanner();

        // 1. 加载配置
        System.out.println("\n[1/8] 加载配置...");
        ApplicationConfig config = loadConfig(args);
        config.printSummary();

        // 2. 初始化存储层
        System.out.println("\n[2/8] 初始化存储层...");
        StorageFactory.StorageBundle stores = initStorage(config);

        VectorStore vectorStore = stores.getVectorStore();
        GraphStore graphStore = stores.getGraphStore();
        MetadataStore metadataStore = stores.getMetadataStore();

        // 3. 创建LLM客户端
        System.out.println("\n[3/8] 初始化LLM客户端...");
        LlmClient llmClient = createLlmClient(config);

        // 4. 创建Embedding服务
        System.out.println("\n[4/8] 初始化Embedding服务...");
        EmbeddingService embeddingService = createEmbeddingService(config);

        // 5. 创建服务层
        System.out.println("\n[5/8] 初始化服务层...");
        MemoryExtractionService extractionService = createExtractionService(
                llmClient, vectorStore, graphStore, metadataStore, embeddingService);
        ConcurrentWriteService writeService = createWriteService(
                vectorStore, graphStore, metadataStore, embeddingService, config);
        HybridRetrievalService retrievalService = createRetrievalService(
                vectorStore, graphStore, metadataStore, embeddingService);

        // 6. 创建去重和TTL服务
        System.out.println("\n[6/10] 初始化去重与TTL服务...");
        deduplicationService = createDeduplicationService(metadataStore, vectorStore);
        ttlService = createTtlService(metadataStore);

        // 6.5 创建衰减和共享服务
        System.out.println("\n[6.5/10] 初始化衰减与共享服务...");
        decayService = createDecayService(metadataStore);
        sharingService = createSharingService(metadataStore);

        // 7. 创建处理器 & 注册路由
        System.out.println("\n[7/10] 初始化处理器与路由...");
        Router router = createRouter(extractionService, writeService, retrievalService,
                metadataStore, vectorStore, graphStore, config);

        // 8. 启动服务器
        System.out.println("\n[8/11] 启动服务器...");
        startServers(router, config);

        // 8.5 初始化WebSocket服务器
        System.out.println("\n[8.5/11] 初始化WebSocket服务器...");
        initWebSocket(memoryHandler, config);

        // 9. 启动后台服务
        System.out.println("\n[9/11] 启动后台服务...");
        startBackgroundServices();

        // 10. 初始化优雅停机
        System.out.println("\n[10/11] 初始化优雅停机...");
        initGracefulShutdown(vectorStore, graphStore, metadataStore);

        // 11. 启动JVM内存监控
        System.out.println("\n[11/11] 启动JVM内存监控...");
        startMemoryMonitor();

        printStartupComplete(config);
    }

    // ==================== 1. 配置加载 ====================

    /**
     * 加载应用配置
     *
     * @param args 命令行参数（支持 --config=xxx 指定配置文件）
     * @return 应用配置
     */
    private ApplicationConfig loadConfig(String[] args) {
        String configPath = "application.json";

        // 支持命令行指定配置文件
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
                System.out.println("[Application] 命令行指定配置文件: " + configPath);
            }
        }

        return ApplicationConfig.load(configPath);
    }

    // ==================== 2. 存储层初始化 ====================

    /**
     * 初始化存储层
     *
     * @param config 应用配置
     * @return 存储组件包
     */
    private StorageFactory.StorageBundle initStorage(ApplicationConfig config) {
        StorageFactory factory = StorageFactory.getInstance();
        StorageFactory.StorageBundle bundle;

        try {
            bundle = factory.fromConfig(config.getStorageGlobalConfig());
        } catch (Exception e) {
            System.err.println("[Application] 存储层初始化失败: " + e.getMessage());
            System.err.println("[Application] 将使用空存储（降级模式）");
            e.printStackTrace();
            bundle = new StorageFactory.StorageBundle(null, null, null);
        }

        // 初始化LRU缓存（最大500条，默认TTL 5分钟）
        metadataCache = new LRUCache<>(500, 300_000);
        System.out.println("[Application] LRU缓存初始化完成: maxSize=500, ttl=5min");

        System.out.println("[Application] 存储层初始化完成: " + bundle);
        return bundle;
    }

    // ==================== 3. LLM客户端 ====================

    /**
     * 创建LLM客户端
     *
     * @param config 应用配置
     * @return LLM客户端实例
     */
    private LlmClient createLlmClient(ApplicationConfig config) {
        String type = config.getLlmType();
        String baseUrl = config.getLlmBaseUrl();
        String model = config.getLlmModel();
        String apiKey = config.getLlmApiKey();

        LlmClient client;
        if ("openai".equals(type)) {
            client = LlmClient.createOpenAiClient(baseUrl, apiKey, model);
        } else {
            // 默认使用Ollama
            client = LlmClient.createOllamaClient(baseUrl, model);
        }

        System.out.println("[Application] LLM客户端创建完成: type=" + type + ", model=" + model);
        return client;
    }

    // ==================== 4. Embedding服务 ====================

    /**
     * 创建Embedding服务
     *
     * <p>当前使用 noOp 实现（随机向量），后续可接入真实Embedding模型。</p>
     *
     * @param config 应用配置
     * @return Embedding服务实例
     */
    private EmbeddingService createEmbeddingService(ApplicationConfig config) {
        // TODO: 根据 config.getEmbeddingType() 创建真实的Embedding服务
        // 当前使用 noOp 实现用于开发测试
        EmbeddingService service = EmbeddingService.noOp();
        System.out.println("[Application] Embedding服务创建完成: type=" + config.getEmbeddingType()
                + " (使用noOp实现)");
        return service;
    }

    // ==================== 5. 服务层 ====================

    /**
     * 创建记忆提取服务
     *
     * @param llmClient         LLM客户端
     * @param vectorStore       向量存储
     * @param graphStore        图存储
     * @param metadataStore     元数据存储
     * @param embeddingService  Embedding服务
     * @return 记忆提取服务实例
     */
    private MemoryExtractionService createExtractionService(
            LlmClient llmClient,
            VectorStore vectorStore,
            GraphStore graphStore,
            MetadataStore metadataStore,
            EmbeddingService embeddingService) {

        MemoryExtractionService service = new MemoryExtractionService(
                llmClient, null, null, embeddingService);

        // 注入存储层
        service.setVectorStore(vectorStore);
        service.setGraphStore(graphStore);
        service.setMetadataStore(metadataStore);

        System.out.println("[Application] 记忆提取服务创建完成");
        return service;
    }

    /**
     * 创建高并发写入服务
     *
     * @param vectorStore       向量存储（可为null）
     * @param graphStore        图存储（可为null）
     * @param metadataStore     元数据存储（可为null）
     * @param embeddingService  Embedding服务
     * @param config            应用配置
     * @return 高并发写入服务实例，存储不可用时返回null
     */
    private ConcurrentWriteService createWriteService(
            VectorStore vectorStore,
            GraphStore graphStore,
            MetadataStore metadataStore,
            EmbeddingService embeddingService,
            ApplicationConfig config) {

        // ConcurrentWriteService要求所有存储非空
        if (vectorStore == null || graphStore == null || metadataStore == null) {
            System.out.println("[Application] 部分存储层不可用，跳过高并发写入服务创建");
            System.out.println("[Application] 可用存储: vector=" + (vectorStore != null) +
                    ", graph=" + (graphStore != null) +
                    ", metadata=" + (metadataStore != null));
            return null;
        }

        ConcurrentWriteService service = ConcurrentWriteService.builder()
                .vectorStore(vectorStore)
                .graphStore(graphStore)
                .metadataStore(metadataStore)
                .embeddingService(embeddingService)
                .circuitFailureThreshold(config.getCircuitFailureThreshold())
                .circuitRecoveryTimeoutMs(config.getCircuitRecoveryTimeout())
                .circuitSuccessThreshold(config.getCircuitSuccessThreshold())
                .build();

        System.out.println("[Application] 高并发写入服务创建完成");
        return service;
    }

    /**
     * 创建混合检索服务
     *
     * @param vectorStore       向量存储
     * @param graphStore        图存储
     * @param metadataStore     元数据存储
     * @param embeddingService  Embedding服务
     * @return 混合检索服务实例
     */
    private HybridRetrievalService createRetrievalService(
            VectorStore vectorStore,
            GraphStore graphStore,
            MetadataStore metadataStore,
            EmbeddingService embeddingService) {

        HybridRetrievalService service = new HybridRetrievalService(
                vectorStore, graphStore, metadataStore, embeddingService);

        System.out.println("[Application] 混合检索服务创建完成");
        return service;
    }

    // ==================== 6. 去重与TTL服务 ====================

    /**
     * 创建记忆去重服务
     *
     * @param metadataStore 元数据存储
     * @param vectorStore   向量存储
     * @return 去重服务实例
     */
    private MemoryDeduplicationService createDeduplicationService(
            MetadataStore metadataStore, VectorStore vectorStore) {
        if (metadataStore == null) {
            System.out.println("[Application] 元数据存储不可用，跳过去重服务创建");
            return null;
        }
        MemoryDeduplicationService service = new MemoryDeduplicationService(metadataStore, vectorStore);
        System.out.println("[Application] 记忆去重服务创建完成: 阈值=0.95, 扫描间隔=1小时");
        return service;
    }

    /**
     * 创建TTL过期服务
     *
     * @param metadataStore 元数据存储
     * @return TTL服务实例
     */
    private MemoryTtlService createTtlService(MetadataStore metadataStore) {
        if (metadataStore == null) {
            System.out.println("[Application] 元数据存储不可用，跳过TTL服务创建");
            return null;
        }
        MemoryTtlService service = new MemoryTtlService(metadataStore);
        System.out.println("[Application] TTL过期服务创建完成: 默认TTL=30天, 扫描间隔=5分钟");
        return service;
    }

    /**
     * 启动后台服务（去重扫描和TTL扫描）
     */
    private void startBackgroundServices() {
        if (deduplicationService != null) {
            deduplicationService.start();
        }
        if (ttlService != null) {
            ttlService.start();
        }
        if (decayService != null) {
            decayService.start();
        }
    }

    // ==================== 7. 路由注册 ====================

    /**
     * 创建Router并注册所有API路由
     *
     * @param extractionService 记忆提取服务
     * @param writeService      高并发写入服务
     * @param retrievalService  混合检索服务
     * @param metadataStore     元数据存储
     * @param vectorStore       向量存储
     * @param graphStore        图存储
     * @param config            应用配置
     * @return 配置好的Router实例
     */
    private Router createRouter(
            MemoryExtractionService extractionService,
            ConcurrentWriteService writeService,
            HybridRetrievalService retrievalService,
            MetadataStore metadataStore,
            VectorStore vectorStore,
            GraphStore graphStore,
            ApplicationConfig config) {

        Router router = new Router();

        // 添加中间件（按优先级排序）
        router.addMiddleware(new LoggingMiddleware());
        router.addMiddleware(new CorsMiddleware());
        if (config.isAuthEnabled() && !config.getAuthApiKey().isEmpty()) {
            router.addMiddleware(new AuthMiddleware(config.getAuthApiKey()));
            System.out.println("[Application] 已启用API Key认证");
        }

        // 创建处理器
        memoryHandler = new MemoryHandler(extractionService, writeService, metadataStore);
        memoryHandler.setDeduplicationService(deduplicationService);
        memoryHandler.setTtlService(ttlService);
        memoryHandler.setDecayService(decayService);
        memoryHandler.setSharingService(sharingService);
        SearchHandler searchHandler = new SearchHandler(retrievalService);
        HealthHandler healthHandler = new HealthHandler(vectorStore, graphStore, metadataStore);
        BatchHandler batchHandler = new BatchHandler(extractionService, writeService, metadataStore, retrievalService);
        AdminHandler adminHandler = new AdminHandler(vectorStore, graphStore, metadataStore, config);
        ImportExportHandler importExportHandler = new ImportExportHandler(metadataStore, writeService);

        // 注册路由
        ApiConfig.registerRoutes(router, memoryHandler, searchHandler, healthHandler);

        // 注册批量操作路由
        router.post("/api/memories/batch/search", batchHandler);
        router.post("/api/memories/batch", batchHandler);
        router.delete("/api/memories/batch", batchHandler);

        // 注册管理接口路由
        router.get("/admin/stats", adminHandler);
        router.post("/admin/cache/clear", adminHandler);
        router.get("/admin/storage/health", adminHandler);
        router.post("/admin/maintenance/compact", adminHandler);

        // 注册导入/导出路由
        router.post("/api/memories/export", importExportHandler);
        router.post("/api/memories/import", importExportHandler);
        router.get("/api/memories/export/file", importExportHandler);

        // 打印路由表
        ApiConfig.printRoutes(router);

        System.out.println("[Application] 路由注册完成");
        return router;
    }

    // ==================== 7. 服务器启动 ====================

    /**
     * 启动HTTP服务器和Metrics服务器
     *
     * @param router 路由管理器
     * @param config 应用配置
     */
    private void startServers(Router router, ApplicationConfig config) throws IOException {
        // 启动主HTTP服务器
        httpServer = new MemoryHttpServer(
                config.getServerHost(),
                config.getServerPort(),
                config.getServerThreadCount(),
                config.getServerTimeoutSeconds()
        );
        httpServer.start(config.getServerPort(), router);

        // 启动Prometheus Metrics服务器
        if (config.isMetricsEnabled()) {
            try {
                metricsServer = new MetricsHttpServer(config.getMetricsPort());
                metricsServer.start();
            } catch (Exception e) {
                System.err.println("[Application] Metrics服务器启动失败: " + e.getMessage());
                System.err.println("[Application] 继续运行，Metrics功能不可用");
            }
        }
    }

    // ==================== WebSocket初始化 ====================

    /**
     * 初始化WebSocket服务器并注册到HTTP服务器
     *
     * @param memoryHandler 记忆处理器
     * @param config 应用配置
     */
    private void initWebSocket(MemoryHandler memoryHandler, ApplicationConfig config) {
        try {
            // 创建WebSocket服务器
            webSocketServer = new WebSocketServer();
            webSocketServer.setPathPrefix("/ws");

            // 绑定到MemoryHandler（用于事件广播）
            if (memoryHandler != null) {
                memoryHandler.setWebSocketServer(webSocketServer);
            }

            // 注册WebSocket上下文到HTTP服务器
            httpServer.registerWebSocketContext("/ws", webSocketServer);

            // 启动心跳检测
            webSocketServer.start();

            System.out.println("[Application] WebSocket服务器初始化完成");
            System.out.println("[Application] WebSocket端点: ws://localhost:"
                    + config.getServerPort() + "/ws");
        } catch (Exception e) {
            System.err.println("[Application] WebSocket服务器初始化失败: " + e.getMessage());
            System.err.println("[Application] 继续运行，WebSocket功能不可用");
        }
    }

    // ==================== 优雅关闭 ====================

    /**
     * 初始化优雅停机管理器
     *
     * <p>创建GracefulShutdown实例，绑定所有资源引用，并注册JVM ShutdownHook。</p>
     *
     * @param vectorStore   向量存储
     * @param graphStore    图存储
     * @param metadataStore 元数据存储
     */
    private void initGracefulShutdown(VectorStore vectorStore, GraphStore graphStore,
                                       MetadataStore metadataStore) {
        gracefulShutdown = new GracefulShutdown();

        // 绑定资源
        gracefulShutdown.bindHttpServer(httpServer);
        gracefulShutdown.bindMetricsServer(metricsServer);
        gracefulShutdown.bindStorage(vectorStore, graphStore, metadataStore);

        // 绑定WebSocket服务器
        if (webSocketServer != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (webSocketServer != null) {
                    webSocketServer.shutdown();
                }
            }));
        }

        // 注册JVM ShutdownHook和信号处理器
        gracefulShutdown.register();

        System.out.println("[Application] 优雅停机管理器初始化完成");
        System.out.println("[Application] 停机流程: RUNNING → DRAINING → STOPPED");
        System.out.println("[Application] 排空超时: 30秒");
    }

    /**
     * 启动JVM内存监控线程
     *
     * <p>每60秒打印一次JVM内存使用情况，用于2核2G资源环境下的监控。</p>
     */
    private void startMemoryMonitor() {
        Runtime runtime = Runtime.getRuntime();

        memoryMonitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60_000); // 每60秒

                    long totalMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    long maxMemory = runtime.maxMemory();

                    double usedPercent = (double) usedMemory / maxMemory * 100;

                    System.out.printf("[MemoryMonitor] 堆内存: %dMB / %dMB (%.1f%%), 空闲: %dMB%n",
                            usedMemory / (1024 * 1024),
                            maxMemory / (1024 * 1024),
                            usedPercent,
                            freeMemory / (1024 * 1024));

                    // 如果内存使用超过80%，触发GC并记录警告
                    if (usedPercent > 80.0) {
                        System.err.printf("[MemoryMonitor] ⚠️ 内存使用率过高: %.1f%%，建议关注%n", usedPercent);
                        System.gc(); // 建议GC（不保证执行）
                    }

                    // 打印停机状态
                    if (gracefulShutdown != null) {
                        System.out.println("[MemoryMonitor] 停机状态: " + gracefulShutdown.getSummary());
                    }

                    // 打印LRU缓存统计
                    if (metadataCache != null) {
                        System.out.println("[MemoryMonitor] 缓存: " + metadataCache.getStats());
                    }

                    // 打印WebSocket统计
                    if (webSocketServer != null && webSocketServer.isRunning()) {
                        System.out.println("[MemoryMonitor] WebSocket连接: " + webSocketServer.getConnectionCount()
                                + ", 订阅: " + webSocketServer.getSubscriptionCount());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "MemoryMonitor");

        memoryMonitorThread.setDaemon(true);
        memoryMonitorThread.start();

        System.out.println("[Application] JVM内存监控已启动（每60秒）");
    }

    // ==================== Banner ====================

    /**
     * 打印启动Banner
     */
    private void printBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                           ║");
        System.out.println("║     █████╗  ██████╗ ███████╗███╗   ███╗███████╗███╗   ███╗║");
        System.out.println("║    ██╔══██╗██╔════╝ ██╔════╝████╗ ████║██╔════╝████╗ ████║║");
        System.out.println("║    ███████║██║  ███╗█████╗  ██╔████╔██║█████╗  ██╔████╔██║║");
        System.out.println("║    ██╔══██║██║   ██║██╔══╝  ██║╚██╔╝██║██╔══╝  ██║╚██╔╝██║║");
        System.out.println("║    ██║  ██║╚██████╔╝███████╗██║ ╚═╝ ██║███████╗██║ ╚═╝ ██║║");
        System.out.println("║    ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝     ╚═╝╚══════╝╚═╝     ╚═╝║");
        System.out.println("║                                                           ║");
        System.out.println("║           Agent Memory Platform  v" + VERSION + "                ║");
        System.out.println("║          企业级Agent记忆中台 - Java无框架实现            ║");
        System.out.println("║                                                           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 打印启动完成信息
     *
     * @param config 应用配置
     */
    private void printStartupComplete(ApplicationConfig config) {
        long elapsed = System.currentTimeMillis() - START_TIME;
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║              ✅ Agent Memory Platform 启动成功!          ║");
        System.out.println("╠═══════════════════════════════════════════════════════════╣");
        System.out.printf("║  HTTP Server:     http://%s:%-23d║%n",
                config.getServerHost(), config.getServerPort());
        System.out.printf("║  Health Check:    http://%s:%d/health%s%n",
                config.getServerHost(), config.getServerPort(),
                padRight("║", 31 - String.valueOf(config.getServerPort()).length()));
        if (config.isMetricsEnabled()) {
            System.out.printf("║  Metrics:         http://127.0.0.1:%-23d║%n", config.getMetricsPort());
        }
        System.out.printf("║  WebSocket:       ws://localhost:%-24d║%n", config.getServerPort());
        System.out.printf("║  Startup Time:    %-38d║%n", elapsed);
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("[Application] 按 Ctrl+C 停止服务");
        System.out.println();
    }

    /**
     * 右侧填充空格
     *
     * @param text  原文本
     * @param count 需要填充的总宽度
     * @return 填充后的文本
     */
    private String padRight(String text, int count) {
        if (text.length() >= count) return text;
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < count) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
