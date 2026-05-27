     1|# Agent 记忆系统深度技术调研与设计方案
     2|
     3|> 基于Java无框架实现的企业级Agent记忆中台  
> 基于Spring Boot 3.x + JDK 21虚拟线程的企业级Agent记忆中台  
> 版本: v5.0 | 更新时间: 2026-05-27
     5|
     6|---
     7|
     8|## 目录
     9|
    10|- [1. 背景与目标](#1-背景与目标)
    11|- [2. 技术选型：Java轻量级方案](#2-技术选型java轻量级方案)
    12|- [3. 行业对标：Mem0/Graphiti源码分析](#3-行业对标mem0graphiti源码分析)
    13|- [4. 插件化存储架构设计](#4-插件化存储架构设计)
    14|- [5. 核心架构设计](#5-核心架构设计)
    15|- [6. 记忆提取引擎](#6-记忆提取引擎)
    16|- [7. 混合检索引擎](#7-混合检索引擎)
    17|- [8. 知识图谱集成](#8-知识图谱集成)
    18|- [9. 高并发与极端场景处理](#9-高并发与极端场景处理)
    19|- [10. API接口设计](#10-api接口设计)
    20|- [11. 前端监控界面设计](#11-前端监控界面设计)
    21|- [12. Maven依赖配置](#12-maven依赖配置)
    22|- [13. 部署与实施](#13-部署与实施)
    23|
    24|---
    25|
    26|## 1. 背景与目标
    27|
    28|### 1.1 设计原则
    29|
    30|| 原则 | 说明 |
    31||------|------|
    32|| **无框架** | 不使用Spring Boot，使用JDK原生HttpServer + 手写路由 |
    33|| **插件化** | 存储层完全抽象，支持运行时切换向量库/图库/业务库 |
    34|| **高性能** | Netty/Vert.x异步IO，HikariCP连接池，Redis多级缓存 |
    35|| **可观测** | 内建Prometheus指标，前端Dashboard实时监控 |
    36|
    37|### 1.2 问题定义
    38|
    39|```
    40|┌─────────────────────────────────────────────────────────────┐
    41|│                    Agent 记忆中台核心目标                      │
    42|├─────────────────────────────────────────────────────────────┤
    43|│  ✓ 跨会话长期记忆      支持用户/Agent维度的记忆持久化         │
    44|│  ✓ 混合检索            语义 + BM25 + 实体boost多信号融合      │
    45|│  ✓ 知识图谱            实体关系推理 + 时间感知                │
    46|│  ✓ 插件化存储          运行时切换向量库/图库/业务库            │
    47|│  ✓ 高并发中台          万级QPS读写，多租户隔离               │
    48|│  ✓ 监控运维            Prometheus指标 + 实时Dashboard        │
    49|└─────────────────────────────────────────────────────────────┘
    50|```
    51|
    52|---
    53|
## 2. 技术选型：Spring Boot 3.x + JDK 21
    55|
    56|### 2.1 核心依赖（零框架）
    57|
    58|| 组件 | 选型 | 版本 | 说明 |
    59||------|------|------|------|
    60|| **HTTP服务器** | JDK HttpServer | 17+ | 零依赖，JDK内置 |
    61|| **JSON** | Gson | 2.10 | Google出品，零依赖 |
    62|| **异步IO** | Vert.x Core | 4.4 | 仅用事件循环，不用Web框架 |
    63|| **向量库** | Milvus SDK | 3.0 | Java原生SDK |
    64|| **图数据库** | Neo4j Driver | 5.x | Bolt协议直连 |
    65|| **元数据** | HikariCP + JDBC | 5.x | 连接池直连MySQL/PostgreSQL |
    66|| **缓存** | Lettuce | 6.x | Redis异步客户端 |
    67|| **搜索引擎** | Elasticsearch Client | 8.x | BM25全文检索 |
    68|| **构建** | Maven | 3.9 | 标准构建工具 |
    69|
    70|### 2.2 Maven核心依赖
    71|
    72|```xml
    73|<properties>
    74|    <java.version>17</java.version>
    75|    <maven.compiler.source>17</maven.compiler.source>
    76|    <maven.compiler.target>17</maven.compiler.target>
    77|</properties>
    78|
    79|<dependencies>
    80|    <!-- JSON处理 - Google Gson -->
    81|    <dependency>
    82|        <groupId>com.google.code.gson</groupId>
    83|        <artifactId>gson</artifactId>
    84|        <version>2.10.1</version>
    85|    </dependency>
    86|
    87|    <!-- HTTP服务器 - JDK内置，无需依赖 -->
    88|
    89|    <!-- 异步事件循环 - Vert.x Core (不用Web) -->
    90|    <dependency>
    91|        <groupId>io.vertx</groupId>
    92|        <artifactId>vertx-core</artifactId>
    93|        <version>4.4.6</version>
    94|    </dependency>
    95|
    96|    <!-- Redis异步客户端 -->
    97|    <dependency>
    98|        <groupId>io.lettuce</groupId>
    99|        <artifactId>lettuce-core</artifactId>
   100|        <version>6.3.2.RELEASE</version>
   101|    </dependency>
   102|
   103|    <!-- Milvus Java SDK -->
   104|    <dependency>
   105|        <groupId>io.milvus</groupId>
   106|        <artifactId>milvus-sdk-java</artifactId>
   107|        <version>3.0.1</version>
   108|    </dependency>
   109|
   110|    <!-- Neo4j Java Driver -->
   111|    <dependency>
   112|        <groupId>org.neo4j.driver</groupId>
   113|        <artifactId>neo4j-java-driver</artifactId>
   114|        <version>5.23.0</version>
   115|    </dependency>
   116|
   117|    <!-- Elasticsearch Java Client -->
   118|    <dependency>
   119|        <groupId>co.elastic.clients</groupId>
   120|        <artifactId>elasticsearch-java</artifactId>
   121|        <version>8.12.2</version>
   122|    </dependency>
   123|
   124|    <!-- JDBC连接池 -->
   125|    <dependency>
   126|        <groupId>com.zaxxer</groupId>
   127|        <artifactId>HikariCP</artifactId>
   128|        <version>5.1.0</version>
   129|    </dependency>
   130|
   131|    <!-- MySQL驱动 -->
   132|    <dependency>
   133|        <groupId>com.mysql</groupId>
   134|        <artifactId>mysql-connector-j</artifactId>
   135|        <version>8.3.0</version>
   136|    </dependency>
   137|
   138|    <!-- PostgreSQL驱动 -->
   139|    <dependency>
   140|        <groupId>org.postgresql</groupId>
   141|        <artifactId>postgresql</artifactId>
   142|        <version>42.7.2</version>
   143|    </dependency>
   144|
   145|    <!-- 腾讯云向量数据库SDK -->
   146|    <dependency>
   147|        <groupId>com.tencentcloudapi</groupId>
   148|        <artifactId>tencentcloud-sdk-java-vdb</artifactId>
   149|        <version>3.1.966</version>
   150|    </dependency>
   151|
   152|    <!-- Qdrant Java Client -->
   153|    <dependency>
   154|        <groupId>io.qdrant</groupId>
   155|        <artifactId>qdrant-java-client</artifactId>
   156|        <version>1.9.1</version>
   157|    </dependency>
   158|
   159|    <!-- Prometheus指标 -->
   160|    <dependency>
   161|        <groupId>io.prometheus</groupId>
   162|        <artifactId>simpleclient</artifactId>
   163|        <version>0.16.0</version>
   164|    </dependency>
   165|    <dependency>
   166|        <groupId>io.prometheus</groupId>
   167|        <artifactId>simpleclient_httpserver</artifactId>
   168|        <version>0.16.0</version>
   169|    </dependency>
   170|</dependencies>
   171|```
   172|
   173|---
   174|
   175|## 3. 行业对标：Mem0/Graphiti源码分析
   176|
   177|### 3.1 Mem0 v3 核心算法（Java实现参考）
   178|
   179|| 特性 | Python实现 | Java实现方案 |
   180||------|-----------|-------------|
   181|| **单次提取** | ADDITIVE_EXTRACTION_PROMPT | LLM REST调用 + Gson解析 |
   182|| **BM25归一化** | sigmoid函数 | Math.exp实现 |
   183|| **实体链接** | spaCy NER | Apache OpenNLP + LLM增强 |
   184|| **多信号融合** | 语义+BM25+entity boost | 并行Stream + CompletableFuture |
   185|
   186|### 3.2 Mem0评分公式（Java实现）
   187|
   188|```java
   189|public class FusionScorer {
   190|    
   191|    /**
   192|     * 三信号融合评分（参考Mem0 v3源码）
   193|     */
   194|    public static List<ScoredMemory> scoreAndRank(
   195|            List<SearchResult> semanticResults,
   196|            Map<String, Double> bm25Scores,
   197|            Map<String, Double> entityBoosts,
   198|            double threshold,
   199|            int topK) {
   200|        
   201|        // 动态计算最大可能分数
   202|        double maxPossible = 1.0;  // 基础: 语义分数
   203|        if (!bm25Scores.isEmpty()) maxPossible += 1.0;   // + BM25
   204|        if (!entityBoosts.isEmpty()) maxPossible += 0.5;  // + 实体boost
   205|        
   206|        return semanticResults.stream()
   207|            .filter(r -> r.score() >= threshold)
   208|            .map(r -> {
   209|                double semantic = r.score();
   210|                double bm25 = bm25Scores.getOrDefault(r.id(), 0.0);
   211|                double entity = entityBoosts.getOrDefault(r.id(), 0.0);
   212|                double combined = (semantic + bm25 + entity) / maxPossible;
   213|                return new ScoredMemory(r.id(), combined, semantic, bm25, entity);
   214|            })
   215|            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
   216|            .limit(topK)
   217|            .toList();
   218|    }
   219|    
   220|    /**
   221|     * BM25分数归一化（查询长度自适应sigmoid）
   222|     */
   223|    public static double normalizeBM25(double rawScore, int queryLength) {
   224|        double midpoint, steepness;
   225|        if (queryLength <= 3) {
   226|            midpoint = 5.0; steepness = 0.7;
   227|        } else if (queryLength <= 6) {
   228|            midpoint = 7.0; steepness = 0.6;
   229|        } else if (queryLength <= 9) {
   230|            midpoint = 9.0; steepness = 0.5;
   231|        } else if (queryLength <= 15) {
   232|            midpoint = 10.0; steepness = 0.5;
   233|        } else {
   234|            midpoint = 12.0; steepness = 0.5;
   235|        }
   236|        return 1.0 / (1.0 + Math.exp(-steepness * (rawScore - midpoint)));
   237|    }
   238|}
   239|```
   240|
   241|---
   242|
   243|## 4. 插件化存储架构设计
   244|
   245|### 4.1 统一接口（Java Interface）
   246|
   247|```java
   248|/**
   249| * 向量存储统一接口 - 所有向量库适配器实现此接口
   250| */
   251|public interface VectorStore {
   252|    
   253|    /** 创建集合/索引 */
   254|    boolean createCollection(String name, int dimension, String metric);
   255|    
   256|    /** 批量写入向量 */
   257|    boolean upsert(String collection, List<VectorRecord> records);
   258|    
   259|    /** 向量搜索 */
   260|    List<SearchResult> search(String collection, float[] queryVector, 
   261|                               int topK, Map<String, Object> filters);
   262|    
   263|    /** 删除向量 */
   264|    boolean delete(String collection, List<String> ids);
   265|    
   266|    /** 获取向量 */
   267|    List<VectorRecord> get(String collection, List<String> ids);
   268|    
   269|    /** 健康检查 */
   270|    boolean healthCheck();
   271|    
   272|    /** 统计信息 */
   273|    Map<String, Object> getStats(String collection);
   274|}
   275|
   276|/**
   277| * 图存储统一接口
   278| */
   279|public interface GraphStore {
   280|    
   281|    /** 创建节点 */
   282|    String createNode(GraphNode node);
   283|    
   284|    /** 创建边 */
   285|    String createEdge(GraphEdge edge);
   286|    
   287|    /** 获取节点 */
   288|    GraphNode getNode(String id);
   289|    
   290|    /** 图遍历 */
   291|    List<Map<String, Object>> traverse(String startNodeId, 
   292|                                        List<String> relationshipTypes,
   293|                                        String direction, int maxDepth);
   294|    
   295|    /** 搜索节点 */
   296|    List<GraphNode> searchNodes(String label, Map<String, Object> props, int limit);
   297|    
   298|    /** 删除 */
   299|    boolean delete(List<String> nodeIds, List<String> edgeIds);
   300|    
   301|    /** 健康检查 */
   302|    boolean healthCheck();
   303|}
   304|
   305|/**
   306| * 元数据存储统一接口
   307| */
   308|public interface MetadataStore {
   309|    
   310|    /** 插入记录 */
   311|    String insert(String table, MetadataRecord record);
   312|    
   313|    /** 批量插入 */
   314|    List<String> batchInsert(String table, List<MetadataRecord> records);
   315|    
   316|    /** 查询 */
   317|    List<MetadataRecord> find(String table, Map<String, Object> filters, 
   318|                               int limit, int offset);
   319|    
   320|    /** 更新 */
   321|    boolean update(String table, String id, Map<String, Object> updates);
   322|    
   323|    /** 删除 */
   324|    boolean delete(String table, List<String> ids);
   325|    
   326|    /** 计数 */
   327|    long count(String table, Map<String, Object> filters);
   328|    
   329|    /** 健康检查 */
   330|    boolean healthCheck();
   331|}
   332|```
   333|
   334|### 4.2 适配器工厂
   335|
   336|```java
   337|/**
   338| * 存储适配器工厂 - 支持运行时动态切换
   339| */
   340|public class StorageFactory {
   341|    
   342|    private static final Map<String, Supplier<VectorStore>> vectorAdapters = new ConcurrentHashMap<>();
   343|    private static final Map<String, Supplier<GraphStore>> graphAdapters = new ConcurrentHashMap<>();
   344|    private static final Map<String, Supplier<MetadataStore>> metadataAdapters = new ConcurrentHashMap<>();
   345|    
   346|    static {
   347|        // 预注册内置适配器
   348|        registerVectorAdapter("milvus", MilvusAdapter::new);
   349|        registerVectorAdapter("tencent", TencentVectorDBAdapter::new);
   350|        registerVectorAdapter("qdrant", QdrantAdapter::new);
   351|        registerVectorAdapter("chroma", ChromaAdapter::new);
   352|        registerVectorAdapter("pinecone", PineconeAdapter::new);
   353|        registerVectorAdapter("weaviate", WeaviateAdapter::new);
   354|        
   355|        registerGraphAdapter("neo4j", Neo4jAdapter::new);
   356|        registerGraphAdapter("falkordb", FalkorDBAdapter::new);
   357|        registerGraphAdapter("tigergraph", TigerGraphAdapter::new);
   358|        registerGraphAdapter("memory", InMemoryGraphAdapter::new);
   359|        
   360|        registerMetadataAdapter("mysql", MySQLAdapter::new);
   361|        registerMetadataAdapter("postgresql", PostgreSQLAdapter::new);
   362|        registerMetadataAdapter("mongodb", MongoDBAdapter::new);
   363|        registerMetadataAdapter("h2", H2Adapter::new);
   364|    }
   365|    
   366|    public static void registerVectorAdapter(String name, Supplier<VectorStore> factory) {
   367|        vectorAdapters.put(name, factory);
   368|    }
   369|    
   370|    public static VectorStore createVectorStore(String type, Map<String, Object> config) {
   371|        Supplier<VectorStore> supplier = vectorAdapters.get(type);
   372|        if (supplier == null) {
   373|            throw new IllegalArgumentException("Unknown vector store: " + type);
   374|        }
   375|        VectorStore store = supplier.get();
   376|        store.init(config);
   377|        return store;
   378|    }
   379|    
   380|    public static GraphStore createGraphStore(String type, Map<String, Object> config) {
   381|        return graphAdapters.get(type).get();
   382|    }
   383|    
   384|    public static MetadataStore createMetadataStore(String type, Map<String, Object> config) {
   385|        return metadataAdapters.get(type).get();
   386|    }
   387|}
   388|```
   389|
   390|### 4.3 支持的存储组合
   391|
   392|| 类型 | 可选方案 | Java SDK | 推荐场景 |
   393||------|----------|----------|----------|
   394|| **向量库** | Milvus | milvus-sdk-java 3.0 | 生产首选 |
   395|| | 腾讯云VectorDB | tencentcloud-sdk-java-vdb | 云托管 |
   396|| | Qdrant | qdrant-java-client | 高性能 |
   397|| | Weaviate | weaviate-java-client | 多模态 |
   398|| | Pinecone | REST直调 | SaaS快速上手 |
   399|| | Chroma | REST直调 | 本地开发 |
   400|| **图谱库** | Neo4j | neo4j-java-driver 5.x | 生产首选 |
   401|| | FalkorDB | Redis协议直连 | 轻量部署 |
   402|| | TigerGraph | REST直调 | 大规模图计算 |
   403|| | 内存图 | 自研 | 原型验证 |
   404|| **业务库** | MySQL | HikariCP + JDBC | 关系型首选 |
   405|| | PostgreSQL | HikariCP + JDBC | 高级特性 |
   406|| | MongoDB | MongoDB Driver | 文档型 |
   407|| | H2 | H2 JDBC | 嵌入式测试 |
   408|
   409|---
   410|
   411|## 5. 核心架构设计
   412|
   413|### 5.1 系统架构图
   414|
   415|```plantuml
   416|@startuml Java-Agent-Memory
   417|!theme cerulean
   418|title Java Agent 记忆中台架构（无框架）
   419|
   420|package "接入层" {
   421|  [Web 控制台
   422|(React/Vue)] as WebUI
   423|  [Java REST API
   424|(JDK HttpServer)] as API
   425|  [Java/Python SDK] as SDK
   426|}
   427|
   428|package "核心服务 (Java 17)" {
   429|  [RouterServlet
   430|路由分发] as Router
   431|  [记忆提取服务
   432|(Add-only Engine)] as Extractor
   433|  [混合检索服务
   434|(Hybrid Retrieval)] as Retriever
   435|  [实体链接服务
   436|(Entity Linking)] as EntityLink
   437|  [融合评分引擎
   438|(Fusion Scorer)] as Scorer
   439|  [衰减清理服务
   440|(Memory Decay)] as Decay
   441|}
   442|
   443|package "异步处理" {
   444|  [Vert.x EventLoop
   445|非阻塞IO] as EventLoop
   446|  queue "Kafka/RabbitMQ
   447|(写入队列)" as MQ
   448|}
   449|
   450|package "存储层 - 插件化" {
   451|  interface "VectorStore" as VS
   452|  interface "GraphStore" as GS
   453|  interface "MetadataStore" as MS
   454|  
   455|  database "Milvus/腾讯云/Qdrant
   456|(向量库)" as VDB
   457|  database "Neo4j/FalkorDB
   458|(图谱库)" as Graph
   459|  database "MySQL/PostgreSQL
   460|(元数据)" as MySQL
   461|  database "Redis
   462|(缓存+锁)" as Redis
   463|  database "Elasticsearch
   464|(BM25检索)" as ES
   465|}
   466|
   467|WebUI --> API
   468|SDK --> API
   469|API --> Router
   470|Router --> Extractor
   471|Router --> Retriever
   472|
   473|Extractor --> EventLoop
   474|EventLoop --> MQ
   475|MQ --> VDB
   476|MQ --> Graph
   477|
   478|Retriever --> VS
   479|Retriever --> GS
   480|Retriever --> Scorer
   481|VS --> VDB
   482|GS --> Graph
   483|MS --> MySQL
   484|Retriever --> Redis
   485|Retriever --> ES
   486|
   487|@enduml
   488|```
   489|
   490|### 5.2 JDK HttpServer 路由实现
   491|
   492|```java
   493|import com.sun.net.httpserver.HttpServer;
   494|import com.sun.net.httpserver.HttpExchange;
   495|
   496|/**
   497| * 无框架路由 - 使用JDK内置HttpServer
   498| */
   499|public class MemoryHttpServer {
   500|    
   501|    private final HttpServer server;
   502|    private final Map<String, Map<String, Handler>> routes = new ConcurrentHashMap<>();
   503|    private final Gson gson = new Gson();
   504|    
   505|    public MemoryHttpServer(int port) throws IOException {
   506|        this.server = HttpServer.create(new InetSocketAddress(port), 0);
   507|        this.server.createContext("/", this::handleRequest);
   508|        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
   509|    }
   510|    
   511|    // 注册路由
   512|    public void get(String path, Handler handler) {
   513|        routes.computeIfAbsent("GET", k -> new ConcurrentHashMap<>()).put(path, handler);
   514|    }
   515|    
   516|    public void post(String path, Handler handler) {
   517|        routes.computeIfAbsent("POST", k -> new ConcurrentHashMap<>()).put(path, handler);
   518|    }
   519|    
   520|    // 请求分发
   521|    private void handleRequest(HttpExchange exchange) {
   522|        String method = exchange.getRequestMethod();
   523|        String path = exchange.getRequestURI().getPath();
   524|        
   525|        Map<String, Handler> methodRoutes = routes.get(method);
   526|        Handler handler = methodRoutes != null ? methodRoutes.get(path) : null;
   527|        
   528|        if (handler == null) {
   529|            sendResponse(exchange, 404, Map.of("error", "Not Found"));
   530|            return;
   531|        }
   532|        
   533|        try {
   534|            String body = new String(exchange.getRequestBody().readAllBytes());
   535|            Request req = new Request(exchange, gson.fromJson(body, JsonObject.class));
   536|            Object result = handler.handle(req);
   537|            sendResponse(exchange, 200, result);
   538|        } catch (Exception e) {
   539|            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
   540|        }
   541|    }
   542|    
   543|    private void sendResponse(HttpExchange exchange, int status, Object body) throws IOException {
   544|        String json = gson.toJson(body);
   545|        exchange.getResponseHeaders().set("Content-Type", "application/json");
   546|        exchange.sendResponseHeaders(status, json.getBytes().length);
   547|        exchange.getResponseBody().write(json.getBytes());
   548|        exchange.getResponseBody().close();
   549|    }
   550|    
   551|    public void start() {
   552|        server.start();
   553|        System.out.println("Memory Server started on port " + server.getAddress().getPort());
   554|    }
   555|}
   556|
   557|// 使用示例
   558|public class Application {
   559|    public static void main(String[] args) throws IOException {
   560|        var server = new MemoryHttpServer(8080);
   561|        
   562|        // 注册路由
   563|        server.post("/api/v1/memories", req -> memoryService.add(req.body()));
   564|        server.post("/api/v1/memories/search", req -> memoryService.search(req.body()));
   565|        server.get("/api/v1/memories/{id}", req -> memoryService.get(req.param("id")));
   566|        server.get("/api/v1/health", req -> Map.of("status", "ok"));
   567|        
   568|        server.start();
   569|    }
   570|}
   571|```
   572|
   573|---
   574|
   575|## 6. 记忆提取引擎
   576|
   577|### 6.1 提取流程（Java实现）
   578|
   579|```java
   580|/**
   581| * 记忆提取服务 - 借鉴Mem0 v3 ADD-only算法
   582| */
   583|public class MemoryExtractionService {
   584|    
   585|    private final LLMClient llmClient;
   586|    private final EntityExtractor extractor;
   587|    private final VectorStore vectorStore;
   588|    private final GraphStore graphStore;
   589|    
   590|    // Mem0风格的提取Prompt
   591|    private static final String EXTRACTION_PROMPT = """
   592|        # Role
   593|        You are a memory extractor - precise, evidence-based processor.
   594|        
   595|        # Input
   596|        - New Messages: Current conversation turn (user + assistant)
   597|        - Summary: User historical profile
   598|        - Existing Memories: Already extracted (for dedup reference)
   599|        - Observation Date: Current date for temporal anchoring
   600|        
   601|        # Rules
   602|        1. Extract from user: personal facts, preferences, plans, relationships
   603|        2. Extract from assistant: suggestions, plans, solutions given
   604|        3. Time handling: Anchor all relative time references to observation date
   605|        4. Dedup: Skip if semantically duplicate with existing memories
   606|        5. Link: Reference existing memories in linked_memory_ids if related
   607|        
   608|        # Output Format (JSON)
   609|        {"memory": [{"text": "...", "entities": [{"name": "...", "type": "..."}], "linked_memory_ids": [...], "importance": 0.8, "created_at": "2026-05-27"}]}
   610|        """;
   611|    
   612|    /**
   613|     * 异步提取记忆
   614|     */
   615|    public CompletableFuture<ExtractionResult> extractAsync(
   616|            List<Message> messages,
   617|            String userId,
   618|            String agentId) {
   619|        
   620|        return CompletableFuture.supplyAsync(() -> {
   621|            // 1. 获取最近20条上下文
   622|            List<Message> context = getRecentContext(userId, 20);
   623|            
   624|            // 2. 获取已有记忆摘要
   625|            String existingSummary = getExistingSummary(userId);
   626|            
   627|            // 3. 获取已有记忆（去重参考）
   628|            List<VectorRecord> existingMemories = getExistingMemories(userId);
   629|            
   630|            // 4. 构建Prompt
   631|            String prompt = buildExtractionPrompt(
   632|                messages, context, existingSummary, existingMemories);
   633|            
   634|            // 5. 调用LLM提取
   635|            String llmResponse = llmClient.complete(prompt);
   636|            
   637|            // 6. 解析JSON
   638|            ExtractionResult result = parseExtractionResult(llmResponse);
   639|            
   640|            // 7. 实体提取增强
   641|            result = enhanceWithEntities(result);
   642|            
   643|            // 8. 异步写入存储
   644|            asyncWriteMemories(result, userId, agentId);
   645|            
   646|            return result;
   647|        });
   648|    }
   649|    
   650|    /**
   651|     * 异步写入存储（Kafka队列）
   652|     */
   653|    private void asyncWriteMemories(ExtractionResult result, String userId, String agentId) {
   654|        for (Memory memory : result.memories()) {
   655|            VectorRecord record = VectorRecord.builder()
   656|                .id(UUID.randomUUID().toString())
   657|                .text(memory.text())
   658|                .userId(userId)
   659|                .agentId(agentId)
   660|                .entities(memory.entities())
   661|                .importance(memory.importance())
   662|                .createdAt(Instant.now())
   663|                .build();
   664|            
   665|            kafkaProducer.send("memory_write", record);
   666|        }
   667|    }
   668|}
   669|```
   670|
   671|### 6.2 实体提取（混合策略）
   672|
   673|```java
   674|/**
   675| * 实体提取 - spaCy替代方案: Apache OpenNLP + LLM增强
   676| */
   677|public class EntityExtractor {
   678|    
   679|    public enum EntityType {
   680|        PERSON, ORG, PRODUCT, LOCATION, DATE, PREFERENCE, SKILL, PROJECT
   681|    }
   682|    
   683|    private final NERModel nerModel;
   684|    private final LLMClient llmClient;
   685|    
   686|    /**
   687|     * 混合实体提取
   688|     */
   689|    public List<Entity> extract(String text) {
   690|        // 1. OpenNLP快速提取（人名、地名、组织）
   691|        List<Entity> nerEntities = extractWithOpenNLP(text);
   692|        
   693|        // 2. LLM增强提取（业务实体：偏好、技能、项目）
   694|        List<Entity> llmEntities = extractWithLLM(text);
   695|        
   696|        // 3. 合并去重
   697|        return mergeAndDeduplicate(nerEntities, llmEntities);
   698|    }
   699|    
   700|    private List<Entity> extractWithOpenNLP(String text) {
   701|        Tokenizer tokenizer = new SimpleTokenizer();
   702|        String[] tokens = tokenizer.tokenize(text);
   703|        Span[] nameSpans = nerModel.findNames(tokens);
   704|        
   705|        return Arrays.stream(nameSpans)
   706|            .map(span -> new Entity(
   707|                EntityType.PERSON,
   708|                String.join(" ", Arrays.copyOfRange(tokens, span.getStart(), span.getEnd())),
   709|                0.9
   710|            ))
   711|            .toList();
   712|    }
   713|    
   714|    private List<Entity> extractWithLLM(String text) {
   715|        String prompt = """
   716|            Extract business entities from the following text.
   717|            Return JSON: [{"name": "...", "type": "PREFERENCE|SKILL|PROJECT|PRODUCT"}]
   718|            
   719|            Text: %s
   720|            """.formatted(text);
   721|        
   722|        String response = llmClient.complete(prompt);
   723|        return parseEntitiesFromJson(response);
   724|    }
   725|}
   726|```
   727|
   728|### 6.3 时间感知处理
   729|
   730|```java
   731|/**
   732| * 时间引用解析
   733| */
   734|public class TemporalResolver {
   735|    
   736|    private static final Map<String, TemporalAdjuster> PATTERNS = Map.of(
   737|        "yesterday", d -> d.minusDays(1),
   738|        "today", d -> d,
   739|        "tomorrow", d -> d.plusDays(1),
   740|        "last week", d -> d.minusWeeks(1),
   741|        "next week", d -> d.plusWeeks(1),
   742|        "last month", d -> d.minusMonths(1),
   743|        "next month", d -> d.plusMonths(1),
   744|        "last year", d -> d.minusYears(1),
   745|        "next year", d -> d.plusYears(1)
   746|    );
   747|    
   748|    public static String resolve(String text, LocalDate observationDate) {
   749|        String resolved = text;
   750|        for (Map.Entry<String, TemporalAdjuster> entry : PATTERNS.entrySet()) {
   751|            if (resolved.toLowerCase().contains(entry.getKey())) {
   752|                LocalDate absolute = observationDate.with(entry.getValue());
   753|                resolved = resolved.replace(
   754|                    entry.getKey(), 
   755|                    absolute.format(DateTimeFormatter.ISO_LOCAL_DATE)
   756|                );
   757|            }
   758|        }
   759|        return resolved;
   760|    }
   761|}
   762|```
   763|
   764|---
   765|
   766|## 7. 混合检索引擎
   767|
   768|### 7.1 多信号混合检索（Mem0 v3风格）
   769|
   770|```java
   771|/**
   772| * 混合检索服务 - 三信号融合
   773| */
   774|public class HybridRetrievalService {
   775|    
   776|    private final VectorStore vectorStore;
   777|    private final SearchClient esClient;
   778|    private final RedisCache cache;
   779|    
   780|    /**
   781|     * 混合检索主入口
   782|     */
   783|    public List<ScoredMemory> hybridSearch(SearchQuery query) {
   784|        // 并行获取三个信号
   785|        CompletableFuture<List<SearchResult>> semanticFuture = 
   786|            CompletableFuture.supplyAsync(() -> vectorSearch(query));
   787|        
   788|        CompletableFuture<Map<String, Double>> bm25Future = 
   789|            CompletableFuture.supplyAsync(() -> bm25Search(query));
   790|        
   791|        CompletableFuture<Map<String, Double>> entityFuture = 
   792|            CompletableFuture.supplyAsync(() -> entityBoostSearch(query));
   793|        
   794|        // 等待所有结果
   795|        CompletableFuture.allOf(semanticFuture, bm25Future, entityFuture).join();
   796|        
   797|        List<SearchResult> semanticResults = semanticFuture.join();
   798|        Map<String, Double> bm25Scores = bm25Future.join();
   799|        Map<String, Double> entityBoosts = entityFuture.join();
   800|        
   801|        // 融合评分
   802|        return FusionScorer.scoreAndRank(
   803|            semanticResults, bm25Scores, entityBoosts,
   804|            query.threshold(), query.topK()
   805|        );
   806|    }
   807|    
   808|    private List<SearchResult> vectorSearch(SearchQuery query) {
   809|        float[] embedding = llmClient.embed(query.text());
   810|        return vectorStore.search(
   811|            "memories", embedding, query.topK() * 2,
   812|            Map.of("userId", query.userId(), "agentId", query.agentId())
   813|        );
   814|    }
   815|    
   816|    private Map<String, Double> bm25Search(SearchQuery query) {
   817|        SearchRequest request = SearchRequest.of(s -> s
   818|            .index("memories")
   819|            .query(q -> q
   820|                .match(m -> m
   821|                    .field("content")
   822|                    .query(query.text())
   823|                )
   824|            )
   825|            .size(query.topK() * 2)
   826|        );
   827|        
   828|        SearchResponse<MemoryDocument> response = esClient.search(request, MemoryDocument.class);
   829|        
   830|        return response.hits().hits().stream()
   831|            .collect(Collectors.toMap(
   832|                hit -> hit.id(),
   833|                hit -> hit.score() != null ? hit.score() : 0.0
   834|            ));
   835|    }
   836|    
   837|    private Map<String, Double> entityBoostSearch(SearchQuery query) {
   838|        List<Entity> queryEntities = entityExtractor.extract(query.text());
   839|        if (queryEntities.isEmpty()) return Map.of();
   840|        
   841|        Map<String, Double> boosts = new HashMap<>();
   842|        
   843|        for (Entity entity : queryEntities) {
   844|            List<String> memoryIds = graphStore.findMemoriesByEntity(entity.name());
   845|            for (String memoryId : memoryIds) {
   846|                boosts.merge(memoryId, 0.15, Double::sum);
   847|            }
   848|        }
   849|        
   850|        return boosts;
   851|    }
   852|}
   853|```
   854|
   855|### 7.2 BM25分数归一化
   856|
   857|```java
   858|/**
   859| * BM25归一化 - 查询长度自适应sigmoid
   860| */
   861|public class BM25Normalizer {
   862|    
   863|    public static double normalize(double score, int queryWordCount) {
   864|        double midpoint, steepness;
   865|        
   866|        if (queryWordCount <= 3) {
   867|            midpoint = 5.0; steepness = 0.7;
   868|        } else if (queryWordCount <= 6) {
   869|            midpoint = 7.0; steepness = 0.6;
   870|        } else if (queryWordCount <= 9) {
   871|            midpoint = 9.0; steepness = 0.5;
   872|        } else if (queryWordCount <= 15) {
   873|            midpoint = 10.0; steepness = 0.5;
   874|        } else {
   875|            midpoint = 12.0; steepness = 0.5;
   876|        }
   877|        
   878|        return 1.0 / (1.0 + Math.exp(-steepness * (score - midpoint)));
   879|    }
   880|    
   881|    public static Map<String, Double> normalizeAll(Map<String, Double> rawScores, 
   882|                                                    int queryWordCount) {
   883|        return rawScores.entrySet().stream()
   884|            .collect(Collectors.toMap(
   885|                Map.Entry::getKey,
   886|                e -> normalize(e.getValue(), queryWordCount)
   887|            ));
   888|    }
   889|}
   890|```
   891|
   892|---
   893|
   894|## 8. 知识图谱集成
   895|
   896|### 8.1 Neo4j图存储适配器
   897|
   898|```java
   899|/**
   900| * Neo4j图存储适配器 - 实现GraphStore接口
   901| */
   902|public class Neo4jAdapter implements GraphStore {
   903|    
   904|    private final Driver driver;
   905|    
   906|    public Neo4jAdapter(String uri, String user, String password) {
   907|        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
   908|    }
   909|    
   910|    @Override
   911|    public String createNode(GraphNode node) {
   912|        try (var session = driver.session()) {
   913|            String cypher = """
   914|                CREATE (n:%s {
   915|                    id: $id,
   916|                    content: $content,
   917|                    userId: $userId,
   918|                    agentId: $agentId,
   919|                    createdAt: datetime($createdAt)
   920|                })
   921|                RETURN n.id
   922|                """.formatted(node.label());
   923|            
   924|            return session.run(cypher, Map.of(
   925|                "id", node.id(),
   926|                "content", node.content(),
   927|                "userId", node.userId(),
   928|                "agentId", node.agentId(),
   929|                "createdAt", node.createdAt().toString()
   930|            )).single().get(0).asString();
   931|        }
   932|    }
   933|    
   934|    @Override
   935|    public List<Map<String, Object>> traverse(String startNodeId, 
   936|                                               List<String> relationshipTypes,
   937|                                               String direction, int maxDepth) {
   938|        try (var session = driver.session()) {
   939|            String relationshipClause = relationshipTypes.isEmpty() ? "" :
   940|                String.join("|", relationshipTypes.stream()
   941|                    .map(r -> "`%s`".formatted(r))
   942|                    .toList());
   943|            
   944|            String cypher = """
   945|                MATCH path = (start {id: $startId})-[%s*1..%d]-(end)
   946|                RETURN [n IN nodes(path) | n.id] AS nodeIds,
   947|                       [r IN relationships(path) | type(r)] AS relTypes,
   948|                       length(path) AS depth
   949|                """.formatted(relationshipClause, maxDepth);
   950|            
   951|            return session.run(cypher, Map.of("startId", startNodeId))
   952|                .list(record -> Map.of(
   953|                    "nodeIds", record.get("nodeIds").asList(),
   954|                    "relTypes", record.get("relTypes").asList(),
   955|                    "depth", record.get("depth").asInt()
   956|                ));
   957|        }
   958|    }
   959|    
   960|    @Override
   961|    public boolean healthCheck() {
   962|        try (var session = driver.session()) {
   963|            session.run("RETURN 1");
   964|            return true;
   965|        } catch (Exception e) {
   966|            return false;
   967|        }
   968|    }
   969|}
   970|```
   971|
   972|### 8.2 图谱写入流程
   973|
   974|```java
   975|/**
   976| * 图谱写入服务
   977| */
   978|public class GraphWriteService {
   979|    
   980|    private final GraphStore graphStore;
   981|    
   982|    public void writeMemoryToGraph(Memory memory) {
   983|        // 1. 创建记忆节点
   984|        GraphNode memoryNode = GraphNode.builder()
   985|            .id(memory.id())
   986|            .label("Memory")
   987|            .content(memory.text())
   988|            .userId(memory.userId())
   989|            .agentId(memory.agentId())
   990|            .createdAt(memory.createdAt())
   991|            .build();
   992|        graphStore.createNode(memoryNode);
   993|        
   994|        // 2. 创建实体节点并建立关联
   995|        for (Entity entity : memory.entities()) {
   996|            String entityId = "entity:" + entity.name().toLowerCase();
   997|            graphStore.createNode(GraphNode.builder()
   998|                .id(entityId)
   999|                .label("Entity")
  1000|                .content(entity.name())
  1001|                .type(entity.type().name())
  1002|                .build());
  1003|            
  1004|            graphStore.createEdge(GraphEdge.builder()
  1005|                .id(UUID.randomUUID().toString())
  1006|                .sourceId(memory.id())
  1007|                .targetId(entityId)
  1008|                .type("HAS_ENTITY")
  1009|                .build());
  1010|        }
  1011|        
  1012|        // 3. 关联已有记忆
  1013|        if (memory.linkedMemoryIds() != null) {
  1014|            for (String linkedId : memory.linkedMemoryIds()) {
  1015|                graphStore.createEdge(GraphEdge.builder()
  1016|                    .id(UUID.randomUUID().toString())
  1017|                    .sourceId(memory.id())
  1018|                    .targetId(linkedId)
  1019|                    .type("RELATED_TO")
  1020|                    .build());
  1021|            }
  1022|        }
  1023|    }
  1024|    
  1025|    /**
  1026|     * 迭代BFS遍历（高性能高跳数方案）
  1027|     */
  1028|    private List<Map<String, Object>> iterativeBFS(String startId, int maxDepth) {
  1029|        Set<String> visited = new HashSet<>();
  1030|        Queue<String> queue = new LinkedList<>();
  1031|        List<Map<String, Object>> results = new ArrayList<>();
  1032|        
  1033|        queue.add(startId);
  1034|        visited.add(startId);
  1035|        
  1036|        for (int depth = 0; depth < maxDepth && !queue.isEmpty(); depth++) {
  1037|            int levelSize = queue.size();
  1038|            for (int i = 0; i < levelSize; i++) {
  1039|                String current = queue.poll();
  1040|                List<Map<String, Object>> neighbors = graphStore.traverse(
  1041|                    current, List.of("HAS_ENTITY", "RELATED_TO"), "BOTH", 1
  1042|                );
  1043|                for (Map<String, Object> neighbor : neighbors) {
  1044|                    String neighborId = (String) neighbor.get("id");
  1045|                    if (!visited.contains(neighborId)) {
  1046|                        visited.add(neighborId);
  1047|                        queue.add(neighborId);
  1048|                        results.add(Map.of(
  1049|                            "id", neighborId,
  1050|                            "depth", depth + 1,
  1051|                            "via", current
  1052|                        ));
  1053|                    }
  1054|                }
  1055|            }
  1056|        }
  1057|        
  1058|        return results;
  1059|    }
  1060|}
  1061|```
  1062|
  1063|---
  1064|
  1065|## 9. 高并发与极端场景处理
  1066|
  1067|### 9.1 高并发写入
  1068|
  1069|```java
  1070|/**
  1071| * 高并发写入服务 - 分片队列 + 批量写入
  1072| */
  1073|public class HighConcurrencyWriteService {
  1074|    
  1075|    private final VectorStore vectorStore;
  1076|    private final GraphStore graphStore;
  1077|    private final MetadataStore metadataStore;
  1078|    
  1079|    // 分片写入队列（按用户ID哈希分片）
  1080|    private final List<WriteQueue> shards;
  1081|    private static final int SHARD_COUNT = 16;
  1082|    
  1083|    public HighConcurrencyWriteService() {
  1084|        this.shards = IntStream.range(0, SHARD_COUNT)
  1085|            .mapToObj(i -> new WriteQueue(i, this::flushBatch))
  1086|            .toList();
  1087|    }
  1088|    
  1089|    /**
  1090|     * 分片写入 - 保证同一用户顺序
  1091|     */
  1092|    public CompletableFuture<Boolean> writeAsync(Memory memory) {
  1093|        int shard = Math.abs(memory.userId().hashCode()) % SHARD_COUNT;
  1094|        return shards.get(shard).enqueue(memory);
  1095|    }
  1096|    
  1097|    /**
  1098|     * 批量刷新写入
  1099|     */
  1100|    private void flushBatch(int shardId, List<VectorRecord> batch) {
  1101|        try {
  1102|            // 1. 批量写入向量库
  1103|            vectorStore.upsert("memories", batch);
  1104|            
  1105|            // 2. 批量写入元数据
  1106|            List<MetadataRecord> metadataRecords = batch.stream()
  1107|                .map(this::toMetadataRecord)
  1108|                .toList();
  1109|            metadataStore.batchInsert("memories", metadataRecords);
  1110|            
  1111|            // 3. 异步写入图库
  1112|            CompletableFuture.runAsync(() -> {
  1113|                for (VectorRecord record : batch) {
  1114|                    graphWriteService.writeMemoryToGraph(record);
  1115|                }
  1116|            });
  1117|            
  1118|            metrics.recordBatchWrite(batch.size());
  1119|            
  1120|        } catch (Exception e) {
  1121|            retryFailedBatch(batch, 3);
  1122|        }
  1123|    }
  1124|}
  1125|```
  1126|
  1127|### 9.2 极端场景处理
  1128|
  1129|| 场景 | 症状 | 解决方案 | Java实现 |
  1130||------|------|----------|----------|
  1131|| **Redis雪崩** | 缓存大面积失效 | 多级缓存 + 随机TTL | Caffeine本地 + Redis |
  1132|| **LLM高延迟** | 记忆提取卡住 | 超时熔断 + 降级 | CompletableFuture.orTimeout |
  1133|| **图数据库压力** | 高跳数遍历慢 | BFS优化 + 深度限制 | iterativeBFS实现 |
  1134|| **写入风暴** | 突发高并发写入 | 分片队列 + 批量写入 | 16分片 + 500ms刷盘 |
  1135|| **实体抽取瓶颈** | NER模型推理慢 | 缓存 + 异步 | Redis缓存实体结果 |
  1136|
  1137|```java
  1138|/**
  1139| * 熔断降级 - LLM调用保护
  1140| */
  1141|public class LLMCircuitBreaker {
  1142|    
  1143|    private final AtomicInteger failures = new AtomicInteger(0);
  1144|    private final AtomicLong lastFailureTime = new AtomicLong(0);
  1145|    private static final int FAILURE_THRESHOLD = 5;
  1146|    private static final long RESET_TIMEOUT = 60_000;
  1147|    
  1148|    public <T> CompletableFuture<T> executeWithFallback(
  1149|            Supplier<CompletableFuture<T>> llmCall,
  1150|            Supplier<T> fallback) {
  1151|        
  1152|        if (isCircuitOpen()) {
  1153|            return CompletableFuture.completedFuture(fallback.get());
  1154|        }
  1155|        
  1156|        return llmCall.get()
  1157|            .orTimeout(10, TimeUnit.SECONDS)
  1158|            .exceptionally(ex -> {
  1159|                failures.incrementAndGet();
  1160|                lastFailureTime.set(System.currentTimeMillis());
  1161|                return fallback.get();
  1162|            });
  1163|    }
  1164|    
  1165|    private boolean isCircuitOpen() {
  1166|        if (failures.get() < FAILURE_THRESHOLD) return false;
  1167|        
  1168|        if (System.currentTimeMillis() - lastFailureTime.get() > RESET_TIMEOUT) {
  1169|            failures.set(0);
  1170|            return false;
  1171|        }
  1172|        
  1173|        return true;
  1174|    }
  1175|}
  1176|```
  1177|
  1178|---
  1179|
  1180|## 10. API接口设计
  1181|
  1182|### 10.1 接口列表
  1183|
  1184|| 方法 | 路径 | 说明 | 参数 |
  1185||------|------|------|------|
  1186|| POST | /api/v1/memories | 添加记忆 | `{messages, userId, agentId, metadata}` |
  1187|| POST | /api/v1/memories/search | 混合检索 | `{query, userId, agentId, topK, threshold}` |
  1188|| GET | /api/v1/memories/{id} | 获取记忆 | - |
  1189|| PUT | /api/v1/memories/{id} | 更新记忆 | `{text, metadata}` |
  1190|| DELETE | /api/v1/memories/{id} | 删除记忆 | - |
  1191|| GET | /api/v1/users/{userId}/memories | 用户记忆列表 | `?limit=20&offset=0` |
  1192|| GET | /api/v1/health | 健康检查 | - |
  1193|| GET | /api/v1/metrics | Prometheus指标 | - |
  1194|
  1195|### 10.2 请求/响应示例
  1196|
  1197|**添加记忆：**
  1198|```bash
  1199|curl -X POST http://localhost:8080/api/v1/memories   -H "Content-Type: application/json"   -d '{
  1200|    "messages": [
  1201|      {"role": "user", "content": "我喜欢吃四川菜"},
  1202|      {"role": "assistant", "content": "好的，我记住了你的饮食偏好"}
  1203|    ],
  1204|    "userId": "user_001",
  1205|    "agentId": "agent_001"
  1206|  }'
  1207|```
  1208|
  1209|**响应：**
  1210|```json
  1211|{
  1212|  "code": 200,
  1213|  "data": {
  1214|    "memory_id": "mem_abc123",
  1215|    "memories_created": 1,
  1216|    "entities_extracted": ["四川菜"],
  1217|    "status": "pending_write"
  1218|  }
  1219|}
  1220|```
  1221|
  1222|**混合检索：**
  1223|```bash
  1224|curl -X POST http://localhost:8080/api/v1/memories/search   -d '{
  1225|    "query": "用户的饮食偏好是什么",
  1226|    "userId": "user_001",
  1227|    "agentId": "agent_001",
  1228|    "topK": 5,
  1229|    "threshold": 0.5
  1230|  }'
  1231|```
  1232|
  1233|**响应：**
  1234|```json
  1235|{
  1236|  "code": 200,
  1237|  "data": {
  1238|    "memories": [
  1239|      {
  1240|        "id": "mem_abc123",
  1241|        "content": "用户喜欢四川菜",
  1242|        "score": 0.92,
  1243|        "semantic_score": 0.88,
  1244|        "bm25_score": 0.72,
  1245|        "entity_boost": 0.15,
  1246|        "entities": [{"name": "四川菜", "type": "PREFERENCE"}]
  1247|      }
  1248|    ],
  1249|    "total": 1
  1250|  }
  1251|}
  1252|```
  1253|
  1254|---
  1255|
  1256|## 11. 前端监控界面设计
  1257|
  1258|### 11.1 Dashboard布局
  1259|
  1260|```plantuml
  1261|@startuml
  1262|!theme cerulean
  1263|title Agent 记忆系统监控 Dashboard
  1264|
  1265|skinparam backgroundColor #1e1e2e
  1266|skinparam defaultFontColor #e0e0e0
  1267|
  1268|rectangle "顶部指标栏" as TopBar {
  1269|  rectangle "总记忆数
  1270|125,847" as Metric1 #2d5a27
  1271|  rectangle "今日新增
  1272|2,341" as Metric2 #4a4a27
  1273|  rectangle "平均检索延迟
  1274|23ms" as Metric3 #2d4a27
  1275|  rectangle "活跃用户
  1276|1,234" as Metric4 #2d2d5a
  1277|}
  1278|
  1279|rectangle "存储组件状态" as StorageStatus {
  1280|  rectangle "Milvus
  1281|✅ 4/4节点" as Milvus #1a1a2e
  1282|  rectangle "Neo4j
  1283|✅ 集群正常" as Neo4j #1a1a2e
  1284|  rectangle "Redis
  1285|⚠️ 内存85%" as Redis #1a1a2e
  1286|  rectangle "MySQL
  1287|✅ 连接池45/100" as MySQL #1a1a2e
  1288|}
  1289|
  1290|rectangle "核心指标" as Charts {
  1291|  rectangle "检索延迟趋势
  1292|(P50/P95/P99折线图)" as LatencyChart
  1293|  rectangle "向量存储分布
  1294|(各组件容量饼图)" as StoragePie
  1295|  rectangle "记忆写入量
  1296|(每分钟柱状图)" as WriteChart
  1297|}
  1298|
  1299|TopBar -[hidden]down- StorageStatus
  1300|StorageStatus -[hidden]down- Charts
  1301|
  1302|@enduml
  1303|```
  1304|
  1305|### 11.2 实时监控数据
  1306|
  1307|| 指标 | 说明 | 告警阈值 |
  1308||------|------|----------|
  1309|| `memory_total` | 总记忆条数 | - |
  1310|| `memory_write_rate` | 写入速率（条/秒） | - |
  1311|| `search_latency_p50` | P50检索延迟 | > 100ms |
  1312|| `search_latency_p95` | P95检索延迟 | > 500ms |
  1313|| `search_latency_p99` | P99检索延迟 | > 1000ms |
  1314|| `cache_hit_rate` | 缓存命中率 | < 70% |
  1315|| `vector_store_size` | 向量库存储大小 | > 80%容量 |
  1316|| `graph_nodes_count` | 图节点数量 | - |
  1317|| `llm_call_failures` | LLM调用失败数 | > 5次/分钟 |
  1318|| `write_queue_size` | 写入队列积压 | > 10000条 |
  1319|
  1320|```java
  1321|/**
  1322| * Prometheus指标注册
  1323| */
  1324|public class MemoryMetrics {
  1325|    
  1326|    private static final Counter MEMORY_WRITE_TOTAL = Counter.build()
  1327|        .name("memory_write_total")
  1328|        .help("Total memories written")
  1329|        .labelNames("agent_id", "status")
  1330|        .register();
  1331|    
  1332|    private static final Counter SEARCH_REQUEST_TOTAL = Counter.build()
  1333|        .name("search_request_total")
  1334|        .help("Total search requests")
  1335|        .labelNames("agent_id")
  1336|        .register();
  1337|    
  1338|    private static final Histogram SEARCH_LATENCY = Histogram.build()
  1339|        .name("search_latency_seconds")
  1340|        .help("Search latency in seconds")
  1341|        .buckets(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
  1342|        .register();
  1343|    
  1344|    private static final Gauge WRITE_QUEUE_SIZE = Gauge.build()
  1345|        .name("memory_write_queue_size")
  1346|        .help("Current write queue size")
  1347|        .labelNames("shard_id")
  1348|        .register();
  1349|    
  1350|    public static void recordSearch(String agentId, double latencySeconds) {
  1351|        SEARCH_REQUEST_TOTAL.labels(agentId).inc();
  1352|        SEARCH_LATENCY.observe(latencySeconds);
  1353|    }
  1354|    
  1355|    public static void recordWrite(String agentId, boolean success) {
  1356|        MEMORY_WRITE_TOTAL.labels(agentId, success ? "success" : "failure").inc();
  1357|    }
  1358|    
  1359|    public static void startPrometheusServer(int port) throws IOException {
  1360|        HTTPServer server = new HTTPServer(port);
  1361|        System.out.println("Prometheus metrics server started on port " + port);
  1362|    }
  1363|}
  1364|```
  1365|
  1366|---
  1367|
  1368|## 12. Maven项目结构
  1369|
  1370|```
  1371|agent-memory-system/
  1372|├── pom.xml
  1373|├── src/
  1374|│   ├── main/
  1375|│   │   ├── java/
  1376|│   │   │   └── com/memoryplatform/
  1377|│   │   │       ├── Application.java
  1378|│   │   │       ├── server/
  1379|│   │   │       │   └── MemoryHttpServer.java
  1380|│   │   │       ├── storage/
  1381|│   │   │       │   ├── VectorStore.java
  1382|│   │   │       │   ├── GraphStore.java
  1383|│   │   │       │   ├── MetadataStore.java
  1384|│   │   │       │   ├── StorageFactory.java
  1385|│   │   │       │   └── adapters/
  1386|│   │   │       │       ├── MilvusAdapter.java
  1387|│   │   │       │       ├── Neo4jAdapter.java
  1388|│   │   │       │       └── MySQLAdapter.java
  1389|│   │   │       ├── service/
  1390|│   │   │       │   ├── MemoryExtractionService.java
  1391|│   │   │       │   ├── HybridRetrievalService.java
  1392|│   │   │       │   ├── GraphWriteService.java
  1393|│   │   │       │   └── HighConcurrencyWriteService.java
  1394|│   │   │       ├── scorer/
  1395|│   │   │       │   └── FusionScorer.java
  1396|│   │   │       ├── extractor/
  1397|│   │   │       │   ├── EntityExtractor.java
  1398|│   │   │       │   └── TemporalResolver.java
  1399|│   │   │       ├── llm/
  1400|│   │   │       │   └── LLMClient.java
  1401|│   │   │       ├── circuit/
  1402|│   │   │       │   └── LLMCircuitBreaker.java
  1403|│   │   │       └── metrics/
  1404|│   │   │           └── MemoryMetrics.java
  1405|│   │   └── resources/
  1406|│   │       └── application.properties
  1407|│   └── test/
  1408|│       └── java/
  1409|│           └── com/memoryplatform/
  1410|│               ├── FusionScorerTest.java
  1411|│               ├── BM25NormalizerTest.java
  1412|│               └── MemoryExtractionTest.java
  1413|└── web/
  1414|    └── dashboard/
  1415|        └── (React/Vue前端监控界面)
  1416|```
  1417|
  1418|---
  1419|
  1420|## 13. 部署与实施
  1421|
  1422|### 13.1 Docker Compose部署
  1423|
  1424|```yaml
  1425|version: '3.8'
  1426|services:
  1427|  agent-memory-java:
  1428|    build: .
  1429|    ports:
  1430|      - "8080:8080"
  1431|    environment:
  1432|      - MILVUS_HOST=milvus
  1433|      - NEO4J_URI=bolt://neo4j:7687
  1434|      - REDIS_HOST=redis
  1435|      - MYSQL_HOST=mysql
  1436|      - ES_HOST=elasticsearch
  1437|    depends_on:
  1438|      - milvus
  1439|      - neo4j
  1440|      - redis
  1441|      - mysql
  1442|      - elasticsearch
  1443|
  1444|  milvus:
  1445|    image: milvusdb/milvus:v2.4
  1446|    ports:
  1447|      - "19530:19530"
  1448|
  1449|  neo4j:
  1450|    image: neo4j:5.23-community
  1451|    ports:
  1452|      - "7474:7474"
  1453|      - "7687:7687"
  1454|    environment:
  1455|      - NEO4J_AUTH=neo4j/...
  1456|
  1457|  redis:
  1458|    image: redis:7-alpine
  1459|    ports:
  1460|      - "6379:6379"
  1461|
  1462|  mysql:
  1463|    image: mysql:8.0
  1464|    ports:
  1465|      - "3306:3306"
  1466|    environment:
  1467|      - MYSQL_ROOT_PASSWORD=***
  1468|      - MYSQL_DATABASE=memory_platform
  1469|
  1470|  elasticsearch:
  1471|    image: elasticsearch:8.12.2
  1472|    ports:
  1473|      - "9200:9200"
  1474|    environment:
  1475|      - discovery.type=single-node
  1476|      - xpack.security.enabled=false
  1477|
  1478|  kafka:
  1479|    image: confluentinc/cp-kafka:7.6.0
  1480|    ports:
  1481|      - "9092:9092"
  1482|    environment:
  1483|      - KAFKA_NODE_ID=1
  1484|      - KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT
  1485|      - KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092
  1486|```
  1487|
  1488|### 13.2 性能指标
  1489|
  1490|| 指标 | 目标 | 说明 |
  1491||------|------|------|
  1492|| 写入QPS | 10,000+ | 分片队列 + 批量写入 |
  1493|| 读取QPS | 50,000+ | 多级缓存 + 向量库直查 |
  1494|| P50延迟 | < 20ms | 本地缓存命中 |
  1495|| P95延迟 | < 100ms | 向量库查询 |
  1496|| P99延迟 | < 500ms | 图遍历 + 实体链接 |
  1497|
  1498|### 13.3 实施建议
  1499|
  1500|1. **Phase 1 (Week 1-2)**: 核心接口 + MySQL适配器 + 简单记忆CRUD
  1501|2. **Phase 2 (Week 3-4)**: Milvus向量检索 + 混合检索融合
  1502|3. **Phase 3 (Week 5-6)**: Neo4j图谱集成 + 实体关系推理
  1503|4. **Phase 4 (Week 7-8)**: 高并发优化 + 监控Dashboard + 压测
  1504|
  1505|---
  1506|
  1507|*文档版本: v4.0 | 作者: 技术架构团队 | 更新: 2026-05-27*
  1508|