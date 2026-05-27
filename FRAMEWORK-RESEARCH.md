# Agent记忆框架调研报告

> 调研时间: 2026-05-28 | 调研目的: 为agent-memory-system优化提供参考

## 一、框架概览

| 框架 | GitHub Stars | 语言 | 核心定位 |
|------|-------------|------|----------|
| **Mem0** | 56,885 | Python | Universal memory layer for AI Agents |
| **Letta (MemGPT)** | 22,993 | Python | Stateful agents with advanced memory |
| **Cognee** | 17,536 | Python | Memory control plane + Graph RAG |
| **Zep** | 4,614 | Python | Long-term memory for AI assistants |
| **LangChain** | 137,787 | Python | Agent engineering platform (含memory模块) |

## 二、核心架构分析

### 2.1 Mem0 — 最成熟的Agent记忆框架

**记忆类型体系：**
```python
class MemoryType(Enum):
    SEMANTIC = "semantic_memory"      # 语义记忆：事实、知识
    EPISODIC = "episodic_memory"      # 情景记忆：对话经历
    PROCEDURAL = "procedural_memory"  # 程序记忆：操作步骤
```

**存储抽象层：**
- 基于Factory模式：`VectorStoreFactory`, `EmbedderFactory`, `LlmFactory`, `RerankerFactory`
- 支持26种向量存储后端：Milvus, Qdrant, Chroma, Pinecone, Weaviate, PgVector等
- 清晰的ABC接口：`MemoryBase` 定义 get/get_all/update/delete/history

**检索策略：**
- 混合检索：向量相似度 + BM25文本匹配 + 实体增强
- Sigmoid归一化：查询长度自适应的BM25分数归一化
- 实体增强权重：`ENTITY_BOOST_WEIGHT = 0.5`

**变更历史：**
- SQLite存储记忆变更历史
- 支持 `history(memory_id)` 查询记忆演变

**关键设计决策：**
- 敏感字段与运行时字段分离（`_SENSITIVE_FIELDS_EXACT` vs `_RUNTIME_FIELDS`）
- 内置遥测（telemetry）追踪记忆操作

### 2.2 Letta/MemGPT — 分层记忆架构

**分层记忆模型：**
```
┌─────────────────────────────────────────┐
│  Core Memory (工作记忆)                   │
│  - 始终在上下文窗口中                      │
│  - Token感知，有字符限制                    │
│  - 模块化Block设计                        │
├─────────────────────────────────────────┤
│  Archival Memory (归档记忆)               │
│  - 长期存储，按需检索                      │
│  - 向量索引                              │
│  - 类似情景记忆                           │
├─────────────────────────────────────────┤
│  Recall Memory (回忆记忆)                 │
│  - 对话历史                              │
│  - 时间线检索                            │
│  - 全文搜索                              │
└─────────────────────────────────────────┘
```

**Token感知上下文管理：**
- `ContextWindowOverview`: 追踪每个记忆区域的token使用量
- 自动摘要：当上下文超限时自动压缩
- 动态加载：按需从Archival/Recall加载到Core

**Agent类型多样性：**
- 标准Agent、批处理Agent、语音Agent、临时Agent
- 每种Agent有不同的记忆管理策略

**关键设计决策：**
- 内存文件系统（Memory Filesystem）：Git-enabled agent的文件级记忆
- 插件系统：`plugins/` 目录支持自定义扩展

### 2.3 Cognee — 流水线架构

**流水线设计：**
```
Ingestion → Cognify(提取) → Storage → Retrieval
    │           │              │          │
    ▼           ▼              ▼          ▼
  数据加载    实体抽取       多存储      混合检索
  分块处理    关系构建       图+向量     语义搜索
```

**模块化架构：**
- `modules/`: agent_memory, chunking, cognify, graph, ingestion, recall, retrieval, search, storage
- `infrastructure/`: databases, engine, entities, files, llm, loaders, locks, session

**Graph RAG：**
- 实体抽取 + 关系构建 → 知识图谱
- 图遍历 + 向量检索 = 增强检索

**关键设计决策：**
- Pipeline模式：每个处理步骤可独立配置和替换
- 本体论（Ontology）支持：领域知识建模
- 可观测性：内置metrics和可视化

### 2.4 Zep — 时间知识图谱

**核心特性：**
- 时间感知的记忆检索（Recency scoring）
- 事实抽取：从对话中自动提取结构化事实
- 时间知识图谱：记忆之间的时序关系
- 会话摘要：自动压缩对话历史

### 2.5 LangChain Memory — 模块化记忆模式

**记忆类型：**
- `ConversationBufferMemory`: 完整对话缓冲
- `ConversationSummaryMemory`: 对话摘要
- `ConversationEntityMemory`: 实体记忆
- `ConversationKnowledgeGraphMemory`: 知识图谱记忆
- `VectorStoreRetrieverMemory`: 向量检索记忆

**设计模式：**
- 接口统一：所有记忆实现 `BaseMemory` 接口
- 组合模式：可以叠加多种记忆类型
- LLM驱动：记忆提取和摘要由LLM完成

## 三、共性设计模式

### 3.1 记忆类型分类
| 类型 | 说明 | 对应我们的实现 |
|------|------|---------------|
| 语义记忆 (Semantic) | 事实、知识、概念 | VectorStore中的embedding |
| 情景记忆 (Episodic) | 对话经历、事件 | MetadataStore中的记录 |
| 程序记忆 (Procedural) | 操作步骤、技能 | 尚未实现 |
| 工作记忆 (Working) | 当前上下文 | 尚未实现 |

### 3.2 检索策略
| 策略 | 说明 | 我们的现状 |
|------|------|-----------|
| 向量相似度 | Cosine/内积相似度 | ✅ 已实现 |
| BM25文本匹配 | 关键词匹配 | ❌ 未实现 |
| 实体增强 | 实体匹配加权 | ❌ 未实现 |
| 时间衰减 | 新记忆权重更高 | ❌ 部分（有衰减模型但未集成到检索） |
| 重要度加权 | 高重要度记忆优先 | ❌ 未实现 |
| 混合排序 | 多策略融合 | ✅ 已有FusionScorer |

### 3.3 存储抽象
| 模式 | 说明 | 我们的现状 |
|------|------|-----------|
| Factory模式 | 动态创建存储实例 | ✅ StoragePluginRegistry |
| ABC接口 | 统一存储接口 | ✅ StoragePlugin接口 |
| 多后端支持 | 可插拔存储 | ✅ Milvus/Neo4j/MySQL |
| 变更历史 | 记忆版本追踪 | ❌ 未实现 |

### 3.4 性能优化
| 优化 | 说明 | 我们的现状 |
|------|------|-----------|
| 多级缓存 | L1+L2缓存 | ✅ Caffeine+Redis |
| 异步写入 | 非阻塞写入 | ✅ CompletableFuture |
| 批量操作 | 批量读写 | ✅ BatchHandler |
| 连接池 | 复用连接 | ✅ HikariCP+自定义池 |
| 虚拟线程 | 高并发 | ✅ JDK 21 |

## 四、可借鉴的优化建议

### 4.1 记忆类型体系 (高优先级)
**参考**: Mem0的MemoryType, Letta的分层模型
**建议**: 在Memory模型中添加 `memoryType` 字段，支持 SEMANTIC/EPISODIC/PROCEDURAL
**实现**: 修改Memory实体 + VectorStore索引 + 检索过滤

### 4.2 BM25混合检索 (高优先级)
**参考**: Mem0的scoring.py, Cognee的retrieval模块
**建议**: 在HybridRetrievalService中集成BM25文本匹配
**实现**: 添加BM25索引（可用Elasticsearch或内建实现）+ 混合排序

### 4.3 记忆变更历史 (中优先级)
**参考**: Mem0的SQLite history, Letta的Archival Memory
**建议**: 添加MemoryHistory表，记录每次记忆的变更
**实现**: 在ConcurrentWriteService中添加历史记录写入

### 4.4 Token感知上下文管理 (中优先级)
**参考**: Letta的ContextWindowOverview
**建议**: 追踪每个记忆的token使用量，支持上下文窗口管理
**实现**: 在Memory模型中添加tokenCount字段

### 4.5 实体增强检索 (低优先级)
**参考**: Mem0的ENTITY_BOOST_WEIGHT
**建议**: 从记忆中抽取实体，构建实体索引，检索时实体匹配加权
**实现**: 利用现有的EntityExtractor + 新增实体索引

### 4.6 流水线架构 (低优先级)
**参考**: Cognee的Pipeline模式
**建议**: 将记忆处理拆分为可配置的流水线步骤
**实现**: 定义Pipeline接口，支持步骤编排

## 五、我们的优势

对比这些框架，我们的agent-memory-system已有以下优势：
1. **三层存储架构**：向量+图+关系数据库，比大多数框架更完整
2. **Resilience4j熔断**：生产级稳定性保障
3. **虚拟线程**：JDK 21原生支持，性能优异
4. **OpenTelemetry监控**：完整的可观测性
5. **SPI插件化**：存储后端可扩展
6. **Spring Boot生态**：企业级框架支持

## 六、下一步行动

基于调研结果，建议按以下优先级实施优化：

| 优先级 | 优化项 | 参考框架 | 预计工作量 |
|--------|--------|----------|-----------|
| P0 | 记忆类型体系 | Mem0, Letta | 中 |
| P0 | BM25混合检索 | Mem0 | 大 |
| P1 | 记忆变更历史 | Mem0 | 中 |
| P1 | Token感知管理 | Letta | 小 |
| P2 | 实体增强检索 | Mem0 | 大 |
| P2 | 流水线架构 | Cognee | 大 |
