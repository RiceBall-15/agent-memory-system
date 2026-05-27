# Agent记忆中台 - 项目状态文档

> 最后更新: 2026-05-28 01:45 | 持续优化中，目标截止: 2026-05-28 02:00

## 项目概览

| 指标 | 数值 |
|------|------|
| Java源文件 | 108个 |
| 总代码行数 | 29,480行 |
| 测试文件 | 20个 |
| 包数量 | 22个 |
| 技术栈 | Spring Boot 3.4.1 + JDK 21 + 虚拟线程 |
| 仓库 | git@github.com:RiceBall-15/agent-memory-system.git |

## 架构设计

```
                    ┌─────────────────────────────────────┐
                    │         Agent Memory Platform        │
                    ├─────────────────────────────────────┤
  HTTP/WebSocket ──▶│  Controller (REST API + WS + V2)    │
                    ├─────────────────────────────────────┤
                    │  Handler (Memory/Analytics/Batch/    │
                    │          ImportExport/Version)       │
                    ├─────────────────────────────────────┤
                    │  Service (ConcurrentWrite/Hybrid-   │
                    │    Retrieval/MemoryExtraction/       │
                    │    MemorySharing)                    │
                    ├─────────────────────────────────────┤
  LLM ─────────────▶│  LLM Client (Ollama/qwen2.5:7b)    │
                    ├─────────────────────────────────────┤
                    │  Cache (Caffeine L1 + Redis L2)      │
                    ├─────────────────────────────────────┤
                    │  Circuit Breaker (Resilience4j)      │
                    ├─────────────────────────────────────┤
                    │  Storage Adapters (SPI Plugin化)     │
                    └──────┬──────────┬──────────┬────────┘
                           │          │          │
                    ┌──────▼──┐ ┌─────▼────┐ ┌──▼───────┐
                    │ Milvus  │ │  Neo4j   │ │ MySQL/PG │
                    │(Vector) │ │ (Graph)  │ │(Metadata)│
                    └─────────┘ └──────────┘ └──────────┘
```

## 包结构 (22个包)

| 包名 | 职责 | 核心文件 |
|------|------|----------|
| `connection` | 连接池管理 | AbstractConnectionPool, Neo4j/Milvus连接池 |
| `cache` | 缓存层 | CaffeineCache (Caffeine) |
| `health` | 健康检查 | StorageHealthIndicator |
| `webhook` | Webhook通知 | WebhookService |
| `llm` | LLM集成 | LlmClient (Ollama) |
| `config` | 配置类 | ThreadConfig, ApplicationConfig, Resilience4jConfig |
| `websocket` | WebSocket | WebSocketClient |
| `util` | 工具类 | 各种工具 |
| `scorer` | 评分器 | 相关性评分 |
| `server` | HTTP服务器 | Router, VersionedRouter, GracefulShutdown |
| `controller` | REST控制器 | MemoryController |
| `service` | 业务逻辑 | ConcurrentWriteService, HybridRetrievalService等 |
| `dto` | 数据传输对象 | 各种DTO |
| `extractor` | 记忆提取 | 记忆提取器 |
| `model` | 数据模型 | 各种实体 |
| `handler` | 请求处理器 | MemoryHandler, AnalyticsHandler等 |
| `circuit` | 熔断器 | CircuitBreaker(已废弃), ResilienceCircuitBreaker |
| `storage` | 存储抽象 | StoragePlugin接口, StoragePluginRegistry |
| `storage.adapters` | 存储适配器 | MilvusVectorStore, Neo4jGraphStore, JdbcMetadataStore |
| `security` | 安全模块 | CORS, Rate limiting |
| `metrics` | 指标收集 | MetricsManager, MetricsCollector |
| `actuator` | 监控端点 | AppInfoContributor |

## 已完成优化 (Phase 1-3)

### Phase 1: 性能+稳定性+拓展性
| 优化项 | 状态 | 说明 |
|--------|------|------|
| Caffeine缓存 | ✅ | 替代手写LRU LinkedHashMap，W-TinyLFU淘汰策略 |
| Resilience4j熔断 | ✅ | 替代手写CircuitBreaker，YAML配置驱动 |
| 存储层SPI Plugin化 | ✅ | StoragePlugin接口 + @Service注册 + Registry动态发现 |

### Phase 2: 虚拟线程+监控
| 优化项 | 状态 | 说明 |
|--------|------|------|
| 虚拟线程池统一 | ✅ | ThreadConfig统一管理，所有业务线程→虚拟线程 |
| Actuator健康检查 | ✅ | StorageHealthIndicator + AppInfoContributor |
| Graceful Shutdown | ✅ | 30秒超时优雅停机 |

### Phase 3: 深度优化
| 优化项 | 状态 | 说明 |
|--------|------|------|
| opt6: ConcurrentWriteService→Resilience4j | ✅ | 手写熔断器→resilienceCircuitBroker.execute() |
| opt7: CompletableFuture指定Executor | ✅ | 3处supplyAsync绑定boundedExecutor |
| **opt8: 日志体系整改** | ⏳ | **403处System.out→SLF4J (进行中)** |
| **opt9: MemoryController CRUD补全** | ⏳ | **GET/PUT/DELETE桩代码补全 (待做)** |

## 配置概况 (application.yml)

- **存储**: Milvus(向量) + Neo4j(图) + MySQL(元数据) + PostgreSQL(备份)
- **缓存**: Caffeine L1 + Redis L2
- **LLM**: Ollama/qwen2.5:7b @ localhost:11434
- **熔断**: Resilience4j vectorStore/graphStore/metadataStore + Retry
- **监控**: OpenTelemetry tracing + Prometheus metrics + Actuator
- **线程**: JDK 21 虚拟线程 (`server.tomcat.threads.virtual.enabled: true`)
- **安全**: Rate limiting, CORS

## 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.4.1 | 核心框架 |
| JDK | 21 | 虚拟线程支持 |
| Lombok | - | 代码简化 |
| Caffeine | - | L1缓存 |
| Resilience4j | 2.2.0 | 熔断/重试/限流 |
| Milvus SDK | 2.4.1 | 向量存储 |
| Neo4j Driver | 5.25.0 | 图数据库 |
| HikariCP | - | 连接池 |
| MySQL/PG Drivers | - | 关系数据库 |
| OpenTelemetry | 1.42.0 | 可观测性 |
| Micrometer/Prometheus | - | 指标暴露 |

## 待优化项 (本次任务)

### opt8: 日志体系整改 (403处)
- **范围**: 403处 `System.out.println` / `System.err.println`
- **目标**: 全部替换为 SLF4J 日志调用
- **策略**: 按包分批处理，区分 INFO/WARN/ERROR 级别
- **优先级**: 高 (影响生产环境日志质量)

### opt9: MemoryController CRUD补全
- **范围**: GET/PUT/DELETE 端点为TODO桩代码
- **目标**: 完整实现CRUD操作
- **策略**: 参考MemoryHandler已有实现，复用Service层

### opt10+ : 基于框架调研的新优化项
- **参考框架**: Mem0, Zep, LangChain Memory, MemGPT/Letta, Cognee
- **关注点**: 记忆分层、检索策略、存储抽象、性能优化模式

## Git提交历史

```
fa3e608 opt6+opt7: ConcurrentWriteService迁移到Resilience4j + CompletableFuture指定Executor
51f97f2 剩余线程统一改造为虚拟线程
b3652ec 统一线程池管理 + Actuator健康检查
025b6df 性能+稳定性+拓展性优化 Phase 1
9d0ce9b Spring Boot迁移 Part 4: README更新 + 前端确认
eb9084f Spring Boot迁移 Part 3: WebSocket + 测试
5cde6e5 Spring Boot迁移 Part 2: Service层 + 入口类 + OpenTelemetry
085bf6a Spring Boot迁移 Part 1: Model层Lombok + Controller层 + 配置类
95341dc 功能扩展: 版本管理 + 审计日志 + Webhook + API版本控制
...
```

## 质量指标

| 指标 | 当前值 | 目标值 |
|------|--------|--------|
| System.out调用 | **403** | 0 |
| 测试文件数 | 20 | 30+ |
| 测试覆盖率 | ~30% | 80% |
| 手写线程创建 | 1 (ShutdownHook) | 0 |
| 手写熔断器 | 0 (已废弃) | 0 |
