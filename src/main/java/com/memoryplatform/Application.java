package com.memoryplatform;

import com.memoryplatform.config.ApiConfig;
import com.memoryplatform.config.ApplicationConfig;
import com.memoryplatform.extractor.EntityExtractor;
import com.memoryplatform.extractor.TimeParser;
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
import com.memoryplatform.storage.StorageFactory;
import com.memoryplatform.storage.VectorStore;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;

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
        System.out.println("\n[1/7] 加载配置...");
        ApplicationConfig config = loadConfig(args);
        config.printSummary();

        // 2. 初始化存储层
        System.out.println("\n[2/7] 初始化存储层...");
        StorageFactory.StorageBundle stores = initStorage(config);

        VectorStore vectorStore = stores.getVectorStore();
        GraphStore graphStore = stores.getGraphStore();
        MetadataStore metadataStore = stores.getMetadataStore();

        // 3. 创建LLM客户端
        System.out.println("\n[3/7] 初始化LLM客户端...");
        LlmClient llmClient = createLlmClient(config);

        // 4. 创建Embedding服务
        System.out.println("\n[4/7] 初始化Embedding服务...");
        EmbeddingService embeddingService = createEmbeddingService(config);

        // 5. 创建服务层
        System.out.println("\n[5/7] 初始化服务层...");
        MemoryExtractionService extractionService = createExtractionService(
                llmClient, vectorStore, graphStore, metadataStore, embeddingService);
        ConcurrentWriteService writeService = createWriteService(
                vectorStore, graphStore, metadataStore, embeddingService, config);
        HybridRetrievalService retrievalService = createRetrievalService(
                vectorStore, graphStore, metadataStore, embeddingService);

        // 6. 创建处理器 & 注册路由
        System.out.println("\n[6/7] 初始化处理器与路由...");
        Router router = createRouter(extractionService, writeService, retrievalService,
                metadataStore, vectorStore, graphStore, config);

        // 7. 启动服务器
        System.out.println("\n[7/7] 启动服务器...");
        startServers(router, config);

        // 注册优雅关闭钩子
        registerShutdownHook(writeService);

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

    // ==================== 6. 路由注册 ====================

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
        MemoryHandler memoryHandler = new MemoryHandler(extractionService, writeService, metadataStore);
        SearchHandler searchHandler = new SearchHandler(retrievalService);
        HealthHandler healthHandler = new HealthHandler(vectorStore, graphStore, metadataStore);

        // 注册路由
        ApiConfig.registerRoutes(router, memoryHandler, searchHandler, healthHandler);

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

    // ==================== 优雅关闭 ====================

    /**
     * 注册JVM ShutdownHook进行优雅关闭
     *
     * @param writeService 高并发写入服务（需要flush）
     */
    private void registerShutdownHook(ConcurrentWriteService writeService) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n============================================");
            System.out.println("  正在关闭 Agent Memory Platform...");
            System.out.println("============================================");

            long shutdownStart = System.currentTimeMillis();

            // 1. 停止接收新请求
            if (httpServer != null && httpServer.isRunning()) {
                System.out.println("[Shutdown] 停止HTTP服务器...");
                httpServer.stop();
            }

            // 2. 等待写入队列flush完成
            System.out.println("[Shutdown] 等待写入队列flush...");
            try {
                if (writeService != null) {
                    writeService.shutdown(5000);
                    System.out.println("[Shutdown] 写入队列已flush");
                } else {
                    System.out.println("[Shutdown] 写入服务未初始化，跳过flush");
                }
            } catch (Exception e) {
                System.err.println("[Shutdown] 写入队列flush异常: " + e.getMessage());
            }

            // 3. 停止Metrics服务器
            if (metricsServer != null && metricsServer.isRunning()) {
                System.out.println("[Shutdown] 停止Metrics服务器...");
                metricsServer.stop();
            }

            long elapsed = System.currentTimeMillis() - shutdownStart;
            System.out.println("[Shutdown] 应用已关闭 (耗时 " + elapsed + "ms)");
        }));
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
