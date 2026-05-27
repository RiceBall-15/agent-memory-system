# Agent 记忆系统深度技术调研与设计方案

> 基于Java无框架实现的企业级Agent记忆中台  
> 版本: v4.0 | 更新时间: 2026-05-27

---

## 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 技术选型：Java轻量级方案](#2-技术选型java轻量级方案)
- [3. 行业对标：Mem0/Graphiti源码分析](#3-行业对标mem0graphiti源码分析)
- [4. 插件化存储架构设计](#4-插件化存储架构设计)
- [5. 核心架构设计](#5-核心架构设计)
- [6. 记忆提取引擎](#6-记忆提取引擎)
- [7. 混合检索引擎](#7-混合检索引擎)
- [8. 知识图谱集成](#8-知识图谱集成)
- [9. 高并发与极端场景处理](#9-高并发与极端场景处理)
- [10. API接口设计](#10-api接口设计)
- [11. 前端监控界面设计](#11-前端监控界面设计)
- [12. Maven依赖配置](#12-maven依赖配置)
- [13. 部署与实施](#13-部署与实施)

---

## 1. 背景与目标

### 1.1 设计原则

| 原则 | 说明 |
|------|------|
| **无框架** | 不使用Spring Boot，使用JDK原生HttpServer + 手写路由 |
| **插件化** | 存储层完全抽象，支持运行时切换向量库/图库/业务库 |
| **高性能** | Netty/Vert.x异步IO，HikariCP连接池，Redis多级缓存 |
| **可观测** | 内建Prometheus指标，前端Dashboard实时监控 |

### 1.2 问题定义

```
┌─────────────────────────────────────────────────────────────┐
│                    Agent 记忆中台核心目标                      │
├─────────────────────────────────────────────────────────────┤
│  ✓ 跨会话长期记忆      支持用户/Agent维度的记忆持久化         │
│  ✓ 混合检索            语义 + BM25 + 实体boost多信号融合      │
│  ✓ 知识图谱            实体关系推理 + 时间感知                │
│  ✓ 插件化存储          运行时切换向量库/图库/业务库            │
│  ✓ 高并发中台          万级QPS读写，多租户隔离               │
│  ✓ 监控运维            Prometheus指标 + 实时Dashboard        │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 技术选型：Java轻量级方案

### 2.1 核心依赖（零框架）

| 组件 | 选型 | 版本 | 说明 |
|------|------|------|------|
| **HTTP服务器** | JDK HttpServer | 17+ | 零依赖，JDK内置 |
| **JSON** | Gson | 2.10 | Google出品，零依赖 |
| **异步IO** | Vert.x Core | 4.4 | 仅用事件循环，不用Web框架 |
| **向量库** | Milvus SDK | 3.0 | Java原生SDK |
| **图数据库** | Neo4j Driver | 5.x | Bolt协议直连 |
| **元数据** | HikariCP + JDBC | 5.x | 连接池直连MySQL/PostgreSQL |
| **缓存** | Lettuce | 6.x | Redis异步客户端 |
| **搜索引擎** | Elasticsearch Client | 8.x | BM25全文检索 |
| **构建** | Maven | 3.9 | 标准构建工具 |

### 2.2 Maven核心依赖

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>

<dependencies>
    <!-- JSON处理 - Google Gson -->
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>

    <!-- HTTP服务器 - JDK内置，无需依赖 -->

    <!-- 异步事件循环 - Vert.x Core (不用Web) -->
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-core</artifactId>
        <version>4.4.6</version>
    </dependency>

    <!-- Redis异步客户端 -->
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>
        <version>6.3.2.RELEASE</version>
    </dependency>

    <!-- Milvus Java SDK -->
    <dependency>
        <groupId>io.milvus</groupId>
        <artifactId>milvus-sdk-java</artifactId>
        <version>3.0.1</version>
    </dependency>

    <!-- Neo4j Java Driver -->
    <dependency>
        <groupId>org.neo4j.driver</groupId>
        <artifactId>neo4j-java-driver</artifactId>
        <version>5.23.0</version>
    </dependency>

    <!-- Elasticsearch Java Client -->
    <dependency>
        <groupId>co.elastic.clients</groupId>
        <artifactId>elasticsearch-java</artifactId>
        <version>8.12.2</version>
    </dependency>

    <!-- JDBC连接池 -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- MySQL驱动 -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>8.3.0</version>
    </dependency>

    <!-- PostgreSQL驱动 -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.2</version>
    </dependency>

    <!-- 腾讯云向量数据库SDK -->
    <dependency>
        <groupId>com.tencentcloudapi</groupId>
        <artifactId>tencentcloud-sdk-java-vdb</artifactId>
        <version>3.1.966</version>
    </dependency>

    <!-- Qdrant Java Client -->
    <dependency>
        <groupId>io.qdrant</groupId>
        <artifactId>qdrant-java-client</artifactId>
        <version>1.9.1</version>
    </dependency>

    <!-- Prometheus指标 -->
    <dependency>
        <groupId>io.prometheus</groupId>
        <artifactId>simpleclient</artifactId>
        <version>0.16.0</version>
    </dependency>
    <dependency>
        <groupId>io.prometheus</groupId>
        <artifactId>simpleclient_httpserver</artifactId>
        <version>0.16.0</version>
    </dependency>
</dependencies>
```

---

## 3. 行业对标：Mem0/Graphiti源码分析

### 3.1 Mem0 v3 核心算法（Java实现参考）

| 特性 | Python实现 | Java实现方案 |
|------|-----------|-------------|
| **单次提取** | ADDITIVE_EXTRACTION_PROMPT | LLM REST调用 + Gson解析 |
| **BM25归一化** | sigmoid函数 | Math.exp实现 |
| **实体链接** | spaCy NER | Apache OpenNLP + LLM增强 |
| **多信号融合** | 语义+BM25+entity boost | 并行Stream + CompletableFuture |

### 3.2 Mem0评分公式（Java实现）

```java
public class FusionScorer {
    
    /**
     * 三信号融合评分（参考Mem0 v3源码）
     */
    public static List<ScoredMemory> scoreAndRank(
            List<SearchResult> semanticResults,
            Map<String, Double> bm25Scores,
            Map<String, Double> entityBoosts,
            double threshold,
            int topK) {
        
        // 动态计算最大可能分数
        double maxPossible = 1.0;  // 基础: 语义分数
        if (!bm25Scores.isEmpty()) maxPossible += 1.0;   // + BM25
        if (!entityBoosts.isEmpty()) maxPossible += 0.5;  // + 实体boost
        
        return semanticResults.stream()
            .filter(r -> r.score() >= threshold)
            .map(r -> {
                double semantic = r.score();
                double bm25 = bm25Scores.getOrDefault(r.id(), 0.0);
                double entity = entityBoosts.getOrDefault(r.id(), 0.0);
                double combined = (semantic + bm25 + entity) / maxPossible;
                return new ScoredMemory(r.id(), combined, semantic, bm25, entity);
            })
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(topK)
            .toList();
    }
    
    /**
     * BM25分数归一化（查询长度自适应sigmoid）
     */
    public static double normalizeBM25(double rawScore, int queryLength) {
        double midpoint, steepness;
        if (queryLength <= 3) {
            midpoint = 5.0; steepness = 0.7;
        } else if (queryLength <= 6) {
            midpoint = 7.0; steepness = 0.6;
        } else if (queryLength <= 9) {
            midpoint = 9.0; steepness = 0.5;
        } else if (queryLength <= 15) {
            midpoint = 10.0; steepness = 0.5;
        } else {
            midpoint = 12.0; steepness = 0.5;
        }
        return 1.0 / (1.0 + Math.exp(-steepness * (rawScore - midpoint)));
    }
}
```

---

## 4. 插件化存储架构设计

### 4.1 统一接口（Java Interface）

```java
/**
 * 向量存储统一接口 - 所有向量库适配器实现此接口
 */
public interface VectorStore {
    
    /** 创建集合/索引 */
    boolean createCollection(String name, int dimension, String metric);
    
    /** 批量写入向量 */
    boolean upsert(String collection, List<VectorRecord> records);
    
    /** 向量搜索 */
    List<SearchResult> search(String collection, float[] queryVector, 
                               int topK, Map<String, Object> filters);
    
    /** 删除向量 */
    boolean delete(String collection, List<String> ids);
    
    /** 获取向量 */
    List<VectorRecord> get(String collection, List<String> ids);
    
    /** 健康检查 */
    boolean healthCheck();
    
    /** 统计信息 */
    Map<String, Object> getStats(String collection);
}

/**
 * 图存储统一接口
 */
public interface GraphStore {
    
    /** 创建节点 */
    String createNode(GraphNode node);
    
    /** 创建边 */
    String createEdge(GraphEdge edge);
    
    /** 获取节点 */
    GraphNode getNode(String id);
    
    /** 图遍历 */
    List<Map<String, Object>> traverse(String startNodeId, 
                                        List<String> relationshipTypes,
                                        String direction, int maxDepth);
    
    /** 搜索节点 */
    List<GraphNode> searchNodes(String label, Map<String, Object> props, int limit);
    
    /** 删除 */
    boolean delete(List<String> nodeIds, List<String> edgeIds);
    
    /** 健康检查 */
    boolean healthCheck();
}

/**
 * 元数据存储统一接口
 */
public interface MetadataStore {
    
    /** 插入记录 */
    String insert(String table, MetadataRecord record);
    
    /** 批量插入 */
    List<String> batchInsert(String table, List<MetadataRecord> records);
    
    /** 查询 */
    List<MetadataRecord> find(String table, Map<String, Object> filters, 
                               int limit, int offset);
    
    /** 更新 */
    boolean update(String table, String id, Map<String, Object> updates);
    
    /** 删除 */
    boolean delete(String table, List<String> ids);
    
    /** 计数 */
    long count(String table, Map<String, Object> filters);
    
    /** 健康检查 */
    boolean healthCheck();
}
```

### 4.2 适配器工厂

```java
/**
 * 存储适配器工厂 - 支持运行时动态切换
 */
public class StorageFactory {
    
    private static final Map<String, Supplier<VectorStore>> vectorAdapters = new ConcurrentHashMap<>();
    private static final Map<String, Supplier<GraphStore>> graphAdapters = new ConcurrentHashMap<>();
    private static final Map<String, Supplier<MetadataStore>> metadataAdapters = new ConcurrentHashMap<>();
    
    static {
        // 预注册内置适配器
        registerVectorAdapter("milvus", MilvusAdapter::new);
        registerVectorAdapter("tencent", TencentVectorDBAdapter::new);
        registerVectorAdapter("qdrant", QdrantAdapter::new);
        registerVectorAdapter("chroma", ChromaAdapter::new);
        registerVectorAdapter("pinecone", PineconeAdapter::new);
        registerVectorAdapter("weaviate", WeaviateAdapter::new);
        
        registerGraphAdapter("neo4j", Neo4jAdapter::new);
        registerGraphAdapter("falkordb", FalkorDBAdapter::new);
        registerGraphAdapter("tigergraph", TigerGraphAdapter::new);
        registerGraphAdapter("memory", InMemoryGraphAdapter::new);
        
        registerMetadataAdapter("mysql", MySQLAdapter::new);
        registerMetadataAdapter("postgresql", PostgreSQLAdapter::new);
        registerMetadataAdapter("mongodb", MongoDBAdapter::new);
        registerMetadataAdapter("h2", H2Adapter::new);
    }
    
    public static void registerVectorAdapter(String name, Supplier<VectorStore> factory) {
        vectorAdapters.put(name, factory);
    }
    
    public static VectorStore createVectorStore(String type, Map<String, Object> config) {
        Supplier<VectorStore> supplier = vectorAdapters.get(type);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown vector store: " + type);
        }
        VectorStore store = supplier.get();
        store.init(config);
        return store;
    }
    
    public static GraphStore createGraphStore(String type, Map<String, Object> config) {
        return graphAdapters.get(type).get();
    }
    
    public static MetadataStore createMetadataStore(String type, Map<String, Object> config) {
        return metadataAdapters.get(type).get();
    }
}
```

### 4.3 支持的存储组合

| 类型 | 可选方案 | Java SDK | 推荐场景 |
|------|----------|----------|----------|
| **向量库** | Milvus | milvus-sdk-java 3.0 | 生产首选 |
| | 腾讯云VectorDB | tencentcloud-sdk-java-vdb | 云托管 |
| | Qdrant | qdrant-java-client | 高性能 |
| | Weaviate | weaviate-java-client | 多模态 |
| | Pinecone | REST直调 | SaaS快速上手 |
| | Chroma | REST直调 | 本地开发 |
| **图谱库** | Neo4j | neo4j-java-driver 5.x | 生产首选 |
| | FalkorDB | Redis协议直连 | 轻量部署 |
| | TigerGraph | REST直调 | 大规模图计算 |
| | 内存图 | 自研 | 原型验证 |
| **业务库** | MySQL | HikariCP + JDBC | 关系型首选 |
| | PostgreSQL | HikariCP + JDBC | 高级特性 |
| | MongoDB | MongoDB Driver | 文档型 |
| | H2 | H2 JDBC | 嵌入式测试 |

---

## 5. 核心架构设计

### 5.1 系统架构图

```plantuml
@startuml Java-Agent-Memory
!theme cerulean
title Java Agent 记忆中台架构（无框架）

package "接入层" {
  [Web 控制台
(React/Vue)] as WebUI
  [Java REST API
(JDK HttpServer)] as API
  [Java/Python SDK] as SDK
}

package "核心服务 (Java 17)" {
  [RouterServlet
路由分发] as Router
  [记忆提取服务
(Add-only Engine)] as Extractor
  [混合检索服务
(Hybrid Retrieval)] as Retriever
  [实体链接服务
(Entity Linking)] as EntityLink
  [融合评分引擎
(Fusion Scorer)] as Scorer
  [衰减清理服务
(Memory Decay)] as Decay
}

package "异步处理" {
  [Vert.x EventLoop
非阻塞IO] as EventLoop
  queue "Kafka/RabbitMQ
(写入队列)" as MQ
}

package "存储层 - 插件化" {
  interface "VectorStore" as VS
  interface "GraphStore" as GS
  interface "MetadataStore" as MS
  
  database "Milvus/腾讯云/Qdrant
(向量库)" as VDB
  database "Neo4j/FalkorDB
(图谱库)" as Graph
  database "MySQL/PostgreSQL
(元数据)" as MySQL
  database "Redis
(缓存+锁)" as Redis
  database "Elasticsearch
(BM25检索)" as ES
}

WebUI --> API
SDK --> API
API --> Router
Router --> Extractor
Router --> Retriever

Extractor --> EventLoop
EventLoop --> MQ
MQ --> VDB
MQ --> Graph

Retriever --> VS
Retriever --> GS
Retriever --> Scorer
VS --> VDB
GS --> Graph
MS --> MySQL
Retriever --> Redis
Retriever --> ES

@enduml
```

### 5.2 JDK HttpServer 路由实现

```java
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

/**
 * 无框架路由 - 使用JDK内置HttpServer
 */
public class MemoryHttpServer {
    
    private final HttpServer server;
    private final Map<String, Map<String, Handler>> routes = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    
    public MemoryHttpServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handleRequest);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
    
    // 注册路由
    public void get(String path, Handler handler) {
        routes.computeIfAbsent("GET", k -> new ConcurrentHashMap<>()).put(path, handler);
    }
    
    public void post(String path, Handler handler) {
        routes.computeIfAbsent("POST", k -> new ConcurrentHashMap<>()).put(path, handler);
    }
    
    // 请求分发
    private void handleRequest(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        
        Map<String, Handler> methodRoutes = routes.get(method);
        Handler handler = methodRoutes != null ? methodRoutes.get(path) : null;
        
        if (handler == null) {
            sendResponse(exchange, 404, Map.of("error", "Not Found"));
            return;
        }
        
        try {
            String body = new String(exchange.getRequestBody().readAllBytes());
            Request req = new Request(exchange, gson.fromJson(body, JsonObject.class));
            Object result = handler.handle(req);
            sendResponse(exchange, 200, result);
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }
    
    private void sendResponse(HttpExchange exchange, int status, Object body) throws IOException {
        String json = gson.toJson(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.getBytes().length);
        exchange.getResponseBody().write(json.getBytes());
        exchange.getResponseBody().close();
    }
    
    public void start() {
        server.start();
        System.out.println("Memory Server started on port " + server.getAddress().getPort());
    }
}

// 使用示例
public class Application {
    public static void main(String[] args) throws IOException {
        var server = new MemoryHttpServer(8080);
        
        // 注册路由
        server.post("/api/v1/memories", req -> memoryService.add(req.body()));
        server.post("/api/v1/memories/search", req -> memoryService.search(req.body()));
        server.get("/api/v1/memories/{id}", req -> memoryService.get(req.param("id")));
        server.get("/api/v1/health", req -> Map.of("status", "ok"));
        
        server.start();
    }
}
```

---

## 6. 记忆提取引擎

### 6.1 提取流程（Java实现）

```java
/**
 * 记忆提取服务 - 借鉴Mem0 v3 ADD-only算法
 */
public class MemoryExtractionService {
    
    private final LLMClient llmClient;
    private final EntityExtractor extractor;
    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    
    // Mem0风格的提取Prompt
    private static final String EXTRACTION_PROMPT = """
        # Role
        You are a memory extractor - precise, evidence-based processor.
        
        # Input
        - New Messages: Current conversation turn (user + assistant)
        - Summary: User historical profile
        - Existing Memories: Already extracted (for dedup reference)
        - Observation Date: Current date for temporal anchoring
        
        # Rules
        1. Extract from user: personal facts, preferences, plans, relationships
        2. Extract from assistant: suggestions, plans, solutions given
        3. Time handling: Anchor all relative time references to observation date
        4. Dedup: Skip if semantically duplicate with existing memories
        5. Link: Reference existing memories in linked_memory_ids if related
        
        # Output Format (JSON)
        {"memory": [{"text": "...", "entities": [{"name": "...", "type": "..."}], "linked_memory_ids": [...], "importance": 0.8, "created_at": "2026-05-27"}]}
        """;
    
    /**
     * 异步提取记忆
     */
    public CompletableFuture<ExtractionResult> extractAsync(
            List<Message> messages,
            String userId,
            String agentId) {
        
        return CompletableFuture.supplyAsync(() -> {
            // 1. 获取最近20条上下文
            List<Message> context = getRecentContext(userId, 20);
            
            // 2. 获取已有记忆摘要
            String existingSummary = getExistingSummary(userId);
            
            // 3. 获取已有记忆（去重参考）
            List<VectorRecord> existingMemories = getExistingMemories(userId);
            
            // 4. 构建Prompt
            String prompt = buildExtractionPrompt(
                messages, context, existingSummary, existingMemories);
            
            // 5. 调用LLM提取
            String llmResponse = llmClient.complete(prompt);
            
            // 6. 解析JSON
            ExtractionResult result = parseExtractionResult(llmResponse);
            
            // 7. 实体提取增强
            result = enhanceWithEntities(result);
            
            // 8. 异步写入存储
            asyncWriteMemories(result, userId, agentId);
            
            return result;
        });
    }
    
    /**
     * 异步写入存储（Kafka队列）
     */
    private void asyncWriteMemories(ExtractionResult result, String userId, String agentId) {
        for (Memory memory : result.memories()) {
            VectorRecord record = VectorRecord.builder()
                .id(UUID.randomUUID().toString())
                .text(memory.text())
                .userId(userId)
                .agentId(agentId)
                .entities(memory.entities())
                .importance(memory.importance())
                .createdAt(Instant.now())
                .build();
            
            kafkaProducer.send("memory_write", record);
        }
    }
}
```

### 6.2 实体提取（混合策略）

```java
/**
 * 实体提取 - spaCy替代方案: Apache OpenNLP + LLM增强
 */
public class EntityExtractor {
    
    public enum EntityType {
        PERSON, ORG, PRODUCT, LOCATION, DATE, PREFERENCE, SKILL, PROJECT
    }
    
    private final NERModel nerModel;
    private final LLMClient llmClient;
    
    /**
     * 混合实体提取
     */
    public List<Entity> extract(String text) {
        // 1. OpenNLP快速提取（人名、地名、组织）
        List<Entity> nerEntities = extractWithOpenNLP(text);
        
        // 2. LLM增强提取（业务实体：偏好、技能、项目）
        List<Entity> llmEntities = extractWithLLM(text);
        
        // 3. 合并去重
        return mergeAndDeduplicate(nerEntities, llmEntities);
    }
    
    private List<Entity> extractWithOpenNLP(String text) {
        Tokenizer tokenizer = new SimpleTokenizer();
        String[] tokens = tokenizer.tokenize(text);
        Span[] nameSpans = nerModel.findNames(tokens);
        
        return Arrays.stream(nameSpans)
            .map(span -> new Entity(
                EntityType.PERSON,
                String.join(" ", Arrays.copyOfRange(tokens, span.getStart(), span.getEnd())),
                0.9
            ))
            .toList();
    }
    
    private List<Entity> extractWithLLM(String text) {
        String prompt = """
            Extract business entities from the following text.
            Return JSON: [{"name": "...", "type": "PREFERENCE|SKILL|PROJECT|PRODUCT"}]
            
            Text: %s
            """.formatted(text);
        
        String response = llmClient.complete(prompt);
        return parseEntitiesFromJson(response);
    }
}
```

### 6.3 时间感知处理

```java
/**
 * 时间引用解析
 */
public class TemporalResolver {
    
    private static final Map<String, TemporalAdjuster> PATTERNS = Map.of(
        "yesterday", d -> d.minusDays(1),
        "today", d -> d,
        "tomorrow", d -> d.plusDays(1),
        "last week", d -> d.minusWeeks(1),
        "next week", d -> d.plusWeeks(1),
        "last month", d -> d.minusMonths(1),
        "next month", d -> d.plusMonths(1),
        "last year", d -> d.minusYears(1),
        "next year", d -> d.plusYears(1)
    );
    
    public static String resolve(String text, LocalDate observationDate) {
        String resolved = text;
        for (Map.Entry<String, TemporalAdjuster> entry : PATTERNS.entrySet()) {
            if (resolved.toLowerCase().contains(entry.getKey())) {
                LocalDate absolute = observationDate.with(entry.getValue());
                resolved = resolved.replace(
                    entry.getKey(), 
                    absolute.format(DateTimeFormatter.ISO_LOCAL_DATE)
                );
            }
        }
        return resolved;
    }
}
```

---

## 7. 混合检索引擎

### 7.1 多信号混合检索（Mem0 v3风格）

```java
/**
 * 混合检索服务 - 三信号融合
 */
public class HybridRetrievalService {
    
    private final VectorStore vectorStore;
    private final SearchClient esClient;
    private final RedisCache cache;
    
    /**
     * 混合检索主入口
     */
    public List<ScoredMemory> hybridSearch(SearchQuery query) {
        // 并行获取三个信号
        CompletableFuture<List<SearchResult>> semanticFuture = 
            CompletableFuture.supplyAsync(() -> vectorSearch(query));
        
        CompletableFuture<Map<String, Double>> bm25Future = 
            CompletableFuture.supplyAsync(() -> bm25Search(query));
        
        CompletableFuture<Map<String, Double>> entityFuture = 
            CompletableFuture.supplyAsync(() -> entityBoostSearch(query));
        
        // 等待所有结果
        CompletableFuture.allOf(semanticFuture, bm25Future, entityFuture).join();
        
        List<SearchResult> semanticResults = semanticFuture.join();
        Map<String, Double> bm25Scores = bm25Future.join();
        Map<String, Double> entityBoosts = entityFuture.join();
        
        // 融合评分
        return FusionScorer.scoreAndRank(
            semanticResults, bm25Scores, entityBoosts,
            query.threshold(), query.topK()
        );
    }
    
    private List<SearchResult> vectorSearch(SearchQuery query) {
        float[] embedding = llmClient.embed(query.text());
        return vectorStore.search(
            "memories", embedding, query.topK() * 2,
            Map.of("userId", query.userId(), "agentId", query.agentId())
        );
    }
    
    private Map<String, Double> bm25Search(SearchQuery query) {
        SearchRequest request = SearchRequest.of(s -> s
            .index("memories")
            .query(q -> q
                .match(m -> m
                    .field("content")
                    .query(query.text())
                )
            )
            .size(query.topK() * 2)
        );
        
        SearchResponse<MemoryDocument> response = esClient.search(request, MemoryDocument.class);
        
        return response.hits().hits().stream()
            .collect(Collectors.toMap(
                hit -> hit.id(),
                hit -> hit.score() != null ? hit.score() : 0.0
            ));
    }
    
    private Map<String, Double> entityBoostSearch(SearchQuery query) {
        List<Entity> queryEntities = entityExtractor.extract(query.text());
        if (queryEntities.isEmpty()) return Map.of();
        
        Map<String, Double> boosts = new HashMap<>();
        
        for (Entity entity : queryEntities) {
            List<String> memoryIds = graphStore.findMemoriesByEntity(entity.name());
            for (String memoryId : memoryIds) {
                boosts.merge(memoryId, 0.15, Double::sum);
            }
        }
        
        return boosts;
    }
}
```

### 7.2 BM25分数归一化

```java
/**
 * BM25归一化 - 查询长度自适应sigmoid
 */
public class BM25Normalizer {
    
    public static double normalize(double score, int queryWordCount) {
        double midpoint, steepness;
        
        if (queryWordCount <= 3) {
            midpoint = 5.0; steepness = 0.7;
        } else if (queryWordCount <= 6) {
            midpoint = 7.0; steepness = 0.6;
        } else if (queryWordCount <= 9) {
            midpoint = 9.0; steepness = 0.5;
        } else if (queryWordCount <= 15) {
            midpoint = 10.0; steepness = 0.5;
        } else {
            midpoint = 12.0; steepness = 0.5;
        }
        
        return 1.0 / (1.0 + Math.exp(-steepness * (score - midpoint)));
    }
    
    public static Map<String, Double> normalizeAll(Map<String, Double> rawScores, 
                                                    int queryWordCount) {
        return rawScores.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> normalize(e.getValue(), queryWordCount)
            ));
    }
}
```

---

## 8. 知识图谱集成

### 8.1 Neo4j图存储适配器

```java
/**
 * Neo4j图存储适配器 - 实现GraphStore接口
 */
public class Neo4jAdapter implements GraphStore {
    
    private final Driver driver;
    
    public Neo4jAdapter(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
    
    @Override
    public String createNode(GraphNode node) {
        try (var session = driver.session()) {
            String cypher = """
                CREATE (n:%s {
                    id: $id,
                    content: $content,
                    userId: $userId,
                    agentId: $agentId,
                    createdAt: datetime($createdAt)
                })
                RETURN n.id
                """.formatted(node.label());
            
            return session.run(cypher, Map.of(
                "id", node.id(),
                "content", node.content(),
                "userId", node.userId(),
                "agentId", node.agentId(),
                "createdAt", node.createdAt().toString()
            )).single().get(0).asString();
        }
    }
    
    @Override
    public List<Map<String, Object>> traverse(String startNodeId, 
                                               List<String> relationshipTypes,
                                               String direction, int maxDepth) {
        try (var session = driver.session()) {
            String relationshipClause = relationshipTypes.isEmpty() ? "" :
                String.join("|", relationshipTypes.stream()
                    .map(r -> "`%s`".formatted(r))
                    .toList());
            
            String cypher = """
                MATCH path = (start {id: $startId})-[%s*1..%d]-(end)
                RETURN [n IN nodes(path) | n.id] AS nodeIds,
                       [r IN relationships(path) | type(r)] AS relTypes,
                       length(path) AS depth
                """.formatted(relationshipClause, maxDepth);
            
            return session.run(cypher, Map.of("startId", startNodeId))
                .list(record -> Map.of(
                    "nodeIds", record.get("nodeIds").asList(),
                    "relTypes", record.get("relTypes").asList(),
                    "depth", record.get("depth").asInt()
                ));
        }
    }
    
    @Override
    public boolean healthCheck() {
        try (var session = driver.session()) {
            session.run("RETURN 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### 8.2 图谱写入流程

```java
/**
 * 图谱写入服务
 */
public class GraphWriteService {
    
    private final GraphStore graphStore;
    
    public void writeMemoryToGraph(Memory memory) {
        // 1. 创建记忆节点
        GraphNode memoryNode = GraphNode.builder()
            .id(memory.id())
            .label("Memory")
            .content(memory.text())
            .userId(memory.userId())
            .agentId(memory.agentId())
            .createdAt(memory.createdAt())
            .build();
        graphStore.createNode(memoryNode);
        
        // 2. 创建实体节点并建立关联
        for (Entity entity : memory.entities()) {
            String entityId = "entity:" + entity.name().toLowerCase();
            graphStore.createNode(GraphNode.builder()
                .id(entityId)
                .label("Entity")
                .content(entity.name())
                .type(entity.type().name())
                .build());
            
            graphStore.createEdge(GraphEdge.builder()
                .id(UUID.randomUUID().toString())
                .sourceId(memory.id())
                .targetId(entityId)
                .type("HAS_ENTITY")
                .build());
        }
        
        // 3. 关联已有记忆
        if (memory.linkedMemoryIds() != null) {
            for (String linkedId : memory.linkedMemoryIds()) {
                graphStore.createEdge(GraphEdge.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceId(memory.id())
                    .targetId(linkedId)
                    .type("RELATED_TO")
                    .build());
            }
        }
    }
    
    /**
     * 迭代BFS遍历（高性能高跳数方案）
     */
    private List<Map<String, Object>> iterativeBFS(String startId, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        
        queue.add(startId);
        visited.add(startId);
        
        for (int depth = 0; depth < maxDepth && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                List<Map<String, Object>> neighbors = graphStore.traverse(
                    current, List.of("HAS_ENTITY", "RELATED_TO"), "BOTH", 1
                );
                for (Map<String, Object> neighbor : neighbors) {
                    String neighborId = (String) neighbor.get("id");
                    if (!visited.contains(neighborId)) {
                        visited.add(neighborId);
                        queue.add(neighborId);
                        results.add(Map.of(
                            "id", neighborId,
                            "depth", depth + 1,
                            "via", current
                        ));
                    }
                }
            }
        }
        
        return results;
    }
}
```

---

## 9. 高并发与极端场景处理

### 9.1 高并发写入

```java
/**
 * 高并发写入服务 - 分片队列 + 批量写入
 */
public class HighConcurrencyWriteService {
    
    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final MetadataStore metadataStore;
    
    // 分片写入队列（按用户ID哈希分片）
    private final List<WriteQueue> shards;
    private static final int SHARD_COUNT = 16;
    
    public HighConcurrencyWriteService() {
        this.shards = IntStream.range(0, SHARD_COUNT)
            .mapToObj(i -> new WriteQueue(i, this::flushBatch))
            .toList();
    }
    
    /**
     * 分片写入 - 保证同一用户顺序
     */
    public CompletableFuture<Boolean> writeAsync(Memory memory) {
        int shard = Math.abs(memory.userId().hashCode()) % SHARD_COUNT;
        return shards.get(shard).enqueue(memory);
    }
    
    /**
     * 批量刷新写入
     */
    private void flushBatch(int shardId, List<VectorRecord> batch) {
        try {
            // 1. 批量写入向量库
            vectorStore.upsert("memories", batch);
            
            // 2. 批量写入元数据
            List<MetadataRecord> metadataRecords = batch.stream()
                .map(this::toMetadataRecord)
                .toList();
            metadataStore.batchInsert("memories", metadataRecords);
            
            // 3. 异步写入图库
            CompletableFuture.runAsync(() -> {
                for (VectorRecord record : batch) {
                    graphWriteService.writeMemoryToGraph(record);
                }
            });
            
            metrics.recordBatchWrite(batch.size());
            
        } catch (Exception e) {
            retryFailedBatch(batch, 3);
        }
    }
}
```

### 9.2 极端场景处理

| 场景 | 症状 | 解决方案 | Java实现 |
|------|------|----------|----------|
| **Redis雪崩** | 缓存大面积失效 | 多级缓存 + 随机TTL | Caffeine本地 + Redis |
| **LLM高延迟** | 记忆提取卡住 | 超时熔断 + 降级 | CompletableFuture.orTimeout |
| **图数据库压力** | 高跳数遍历慢 | BFS优化 + 深度限制 | iterativeBFS实现 |
| **写入风暴** | 突发高并发写入 | 分片队列 + 批量写入 | 16分片 + 500ms刷盘 |
| **实体抽取瓶颈** | NER模型推理慢 | 缓存 + 异步 | Redis缓存实体结果 |

```java
/**
 * 熔断降级 - LLM调用保护
 */
public class LLMCircuitBreaker {
    
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private static final int FAILURE_THRESHOLD = 5;
    private static final long RESET_TIMEOUT = 60_000;
    
    public <T> CompletableFuture<T> executeWithFallback(
            Supplier<CompletableFuture<T>> llmCall,
            Supplier<T> fallback) {
        
        if (isCircuitOpen()) {
            return CompletableFuture.completedFuture(fallback.get());
        }
        
        return llmCall.get()
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                failures.incrementAndGet();
                lastFailureTime.set(System.currentTimeMillis());
                return fallback.get();
            });
    }
    
    private boolean isCircuitOpen() {
        if (failures.get() < FAILURE_THRESHOLD) return false;
        
        if (System.currentTimeMillis() - lastFailureTime.get() > RESET_TIMEOUT) {
            failures.set(0);
            return false;
        }
        
        return true;
    }
}
```

---

## 10. API接口设计

### 10.1 接口列表

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| POST | /api/v1/memories | 添加记忆 | `{messages, userId, agentId, metadata}` |
| POST | /api/v1/memories/search | 混合检索 | `{query, userId, agentId, topK, threshold}` |
| GET | /api/v1/memories/{id} | 获取记忆 | - |
| PUT | /api/v1/memories/{id} | 更新记忆 | `{text, metadata}` |
| DELETE | /api/v1/memories/{id} | 删除记忆 | - |
| GET | /api/v1/users/{userId}/memories | 用户记忆列表 | `?limit=20&offset=0` |
| GET | /api/v1/health | 健康检查 | - |
| GET | /api/v1/metrics | Prometheus指标 | - |

### 10.2 请求/响应示例

**添加记忆：**
```bash
curl -X POST http://localhost:8080/api/v1/memories   -H "Content-Type: application/json"   -d '{
    "messages": [
      {"role": "user", "content": "我喜欢吃四川菜"},
      {"role": "assistant", "content": "好的，我记住了你的饮食偏好"}
    ],
    "userId": "user_001",
    "agentId": "agent_001"
  }'
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "memory_id": "mem_abc123",
    "memories_created": 1,
    "entities_extracted": ["四川菜"],
    "status": "pending_write"
  }
}
```

**混合检索：**
```bash
curl -X POST http://localhost:8080/api/v1/memories/search   -d '{
    "query": "用户的饮食偏好是什么",
    "userId": "user_001",
    "agentId": "agent_001",
    "topK": 5,
    "threshold": 0.5
  }'
```

**响应：**
```json
{
  "code": 200,
  "data": {
    "memories": [
      {
        "id": "mem_abc123",
        "content": "用户喜欢四川菜",
        "score": 0.92,
        "semantic_score": 0.88,
        "bm25_score": 0.72,
        "entity_boost": 0.15,
        "entities": [{"name": "四川菜", "type": "PREFERENCE"}]
      }
    ],
    "total": 1
  }
}
```

---

## 11. 前端监控界面设计

### 11.1 Dashboard布局

```plantuml
@startuml
!theme cerulean
title Agent 记忆系统监控 Dashboard

skinparam backgroundColor #1e1e2e
skinparam defaultFontColor #e0e0e0

rectangle "顶部指标栏" as TopBar {
  rectangle "总记忆数
125,847" as Metric1 #2d5a27
  rectangle "今日新增
2,341" as Metric2 #4a4a27
  rectangle "平均检索延迟
23ms" as Metric3 #2d4a27
  rectangle "活跃用户
1,234" as Metric4 #2d2d5a
}

rectangle "存储组件状态" as StorageStatus {
  rectangle "Milvus
✅ 4/4节点" as Milvus #1a1a2e
  rectangle "Neo4j
✅ 集群正常" as Neo4j #1a1a2e
  rectangle "Redis
⚠️ 内存85%" as Redis #1a1a2e
  rectangle "MySQL
✅ 连接池45/100" as MySQL #1a1a2e
}

rectangle "核心指标" as Charts {
  rectangle "检索延迟趋势
(P50/P95/P99折线图)" as LatencyChart
  rectangle "向量存储分布
(各组件容量饼图)" as StoragePie
  rectangle "记忆写入量
(每分钟柱状图)" as WriteChart
}

TopBar -[hidden]down- StorageStatus
StorageStatus -[hidden]down- Charts

@enduml
```

### 11.2 实时监控数据

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| `memory_total` | 总记忆条数 | - |
| `memory_write_rate` | 写入速率（条/秒） | - |
| `search_latency_p50` | P50检索延迟 | > 100ms |
| `search_latency_p95` | P95检索延迟 | > 500ms |
| `search_latency_p99` | P99检索延迟 | > 1000ms |
| `cache_hit_rate` | 缓存命中率 | < 70% |
| `vector_store_size` | 向量库存储大小 | > 80%容量 |
| `graph_nodes_count` | 图节点数量 | - |
| `llm_call_failures` | LLM调用失败数 | > 5次/分钟 |
| `write_queue_size` | 写入队列积压 | > 10000条 |

```java
/**
 * Prometheus指标注册
 */
public class MemoryMetrics {
    
    private static final Counter MEMORY_WRITE_TOTAL = Counter.build()
        .name("memory_write_total")
        .help("Total memories written")
        .labelNames("agent_id", "status")
        .register();
    
    private static final Counter SEARCH_REQUEST_TOTAL = Counter.build()
        .name("search_request_total")
        .help("Total search requests")
        .labelNames("agent_id")
        .register();
    
    private static final Histogram SEARCH_LATENCY = Histogram.build()
        .name("search_latency_seconds")
        .help("Search latency in seconds")
        .buckets(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
        .register();
    
    private static final Gauge WRITE_QUEUE_SIZE = Gauge.build()
        .name("memory_write_queue_size")
        .help("Current write queue size")
        .labelNames("shard_id")
        .register();
    
    public static void recordSearch(String agentId, double latencySeconds) {
        SEARCH_REQUEST_TOTAL.labels(agentId).inc();
        SEARCH_LATENCY.observe(latencySeconds);
    }
    
    public static void recordWrite(String agentId, boolean success) {
        MEMORY_WRITE_TOTAL.labels(agentId, success ? "success" : "failure").inc();
    }
    
    public static void startPrometheusServer(int port) throws IOException {
        HTTPServer server = new HTTPServer(port);
        System.out.println("Prometheus metrics server started on port " + port);
    }
}
```

---

## 12. Maven项目结构

```
agent-memory-system/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/memoryplatform/
│   │   │       ├── Application.java
│   │   │       ├── server/
│   │   │       │   └── MemoryHttpServer.java
│   │   │       ├── storage/
│   │   │       │   ├── VectorStore.java
│   │   │       │   ├── GraphStore.java
│   │   │       │   ├── MetadataStore.java
│   │   │       │   ├── StorageFactory.java
│   │   │       │   └── adapters/
│   │   │       │       ├── MilvusAdapter.java
│   │   │       │       ├── Neo4jAdapter.java
│   │   │       │       └── MySQLAdapter.java
│   │   │       ├── service/
│   │   │       │   ├── MemoryExtractionService.java
│   │   │       │   ├── HybridRetrievalService.java
│   │   │       │   ├── GraphWriteService.java
│   │   │       │   └── HighConcurrencyWriteService.java
│   │   │       ├── scorer/
│   │   │       │   └── FusionScorer.java
│   │   │       ├── extractor/
│   │   │       │   ├── EntityExtractor.java
│   │   │       │   └── TemporalResolver.java
│   │   │       ├── llm/
│   │   │       │   └── LLMClient.java
│   │   │       ├── circuit/
│   │   │       │   └── LLMCircuitBreaker.java
│   │   │       └── metrics/
│   │   │           └── MemoryMetrics.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/
│           └── com/memoryplatform/
│               ├── FusionScorerTest.java
│               ├── BM25NormalizerTest.java
│               └── MemoryExtractionTest.java
└── web/
    └── dashboard/
        └── (React/Vue前端监控界面)
```

---

## 13. 部署与实施

### 13.1 Docker Compose部署

```yaml
version: '3.8'
services:
  agent-memory-java:
    build: .
    ports:
      - "8080:8080"
    environment:
      - MILVUS_HOST=milvus
      - NEO4J_URI=bolt://neo4j:7687
      - REDIS_HOST=redis
      - MYSQL_HOST=mysql
      - ES_HOST=elasticsearch
    depends_on:
      - milvus
      - neo4j
      - redis
      - mysql
      - elasticsearch

  milvus:
    image: milvusdb/milvus:v2.4
    ports:
      - "19530:19530"

  neo4j:
    image: neo4j:5.23-community
    ports:
      - "7474:7474"
      - "7687:7687"
    environment:
      - NEO4J_AUTH=neo4j/...

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=***
      - MYSQL_DATABASE=memory_platform

  elasticsearch:
    image: elasticsearch:8.12.2
    ports:
      - "9200:9200"
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    ports:
      - "9092:9092"
    environment:
      - KAFKA_NODE_ID=1
      - KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT
      - KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092
```

### 13.2 性能指标

| 指标 | 目标 | 说明 |
|------|------|------|
| 写入QPS | 10,000+ | 分片队列 + 批量写入 |
| 读取QPS | 50,000+ | 多级缓存 + 向量库直查 |
| P50延迟 | < 20ms | 本地缓存命中 |
| P95延迟 | < 100ms | 向量库查询 |
| P99延迟 | < 500ms | 图遍历 + 实体链接 |

### 13.3 实施建议

1. **Phase 1 (Week 1-2)**: 核心接口 + MySQL适配器 + 简单记忆CRUD
2. **Phase 2 (Week 3-4)**: Milvus向量检索 + 混合检索融合
3. **Phase 3 (Week 5-6)**: Neo4j图谱集成 + 实体关系推理
4. **Phase 4 (Week 7-8)**: 高并发优化 + 监控Dashboard + 压测

---

*文档版本: v4.0 | 作者: 技术架构团队 | 更新: 2026-05-27*
