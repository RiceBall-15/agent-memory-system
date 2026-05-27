# Agent记忆系统深度技术调研与设计方案

> **项目背景**：基于DAG Workflow的Agent系统，Java技术栈，需构建多维度、跨用户的长期记忆系统，支持事实抽取、相关性检索与记忆总结。
> 
> **文档版本**：v2.0 | **调研日期**：2026年5月 | **技术栈**：Java + 向量数据库 + 知识图谱

---

## 目录

1. [行业现状与技术趋势](#1-行业现状与技术趋势)
2. [技术路线演进](#2-技术路线演进)
3. [主流框架深度对比](#3-主流框架深度对比)
4. [记忆系统核心架构设计](#4-记忆系统核心架构设计)
5. [DAG Workflow适配设计](#5-dag-workflow适配设计)
6. [Java技术栈选型](#6-java技术栈选型)
7. [数据模型与存储设计](#7-数据模型与存储设计)
8. [检索与相关性算法](#8-检索与相关性算法)
9. [事实抽取与总结管道](#9-事实抽取与总结管道)
10. [部署架构与性能优化](#10-部署架构与性能优化)
11. [安全与隐私设计](#11-安全与隐私设计)
12. [实施路线图](#12-实施路线图)

---

## 1. 行业现状与技术趋势

### 1.1 2026年Agent记忆系统格局

2026年，Agent记忆系统已从"可选组件"演变为"生产必备基础设施"。Mem0发布**Token高效记忆算法**（LongMemEval达94.8%）、Anthropic推出**Dreaming**（异步海马体重放记忆整合）、Google发布**Memory Bank**（身份域持久化原语），标志着记忆系统进入平台级原生支持阶段。

**GitHub热门项目数据（2026年5月）：**

| 项目 | Stars | 语言 | 核心定位 | 最后更新 |
|------|-------|------|----------|----------|
| **Mem0** | 56,853 | Python | Universal memory layer | 2026-05-27 |
| **Graphiti (Zep)** | 26,639 | Python | Temporal Context Graphs | 2026-05-27 |
| **Letta** | 22,982 | Python | Stateful agents with memory | 2026-05-27 |
| **Cognee** | 17,523 | Python | Memory control plane | 2026-05-27 |

### 1.2 核心挑战

根据LoCoMo、LongMemEval、BEAM三大基准测试，当前记忆系统的核心挑战集中在：

| 挑战 | 说明 | 当前最佳方案 |
|------|------|-------------|
| **时序抽象** | 用户偏好随时间演变，需区分"当前有效"与"历史过期"事实 | Graphiti时序知识图谱 |
| **跨会话结构建模** | 记忆需在会话间演化而非简单覆盖 | Mem0增量提取管道 |
| **对抗鲁棒性** | 防止基于未披露事实的幻觉 | Synthius-Mem 99.55%对抗鲁棒率 |
| **Token效率** | 单次检索消耗从26,000 Token降至~6,956 Token | Mem0新算法 6.8K Token |
| **跨用户经验复用** | 相似用户的经验可迁移 | 向量相似度+图遍历 |

### 1.3 三大技术趋势

| 趋势 | 说明 | 影响 |
|------|------|------|
| **四层记忆栈标准化** | Working → Episodic → Semantic → Governance 分层已成为事实标准 | 架构设计有明确参照 |
| **长上下文经济学** | Claude Opus 4.7提供1M Token平价的上下文窗口 | 小体量Agent可暂缓显式记忆，但企业级仍需分层 |
| **Graph+Vector混合** | 纯向量检索无法满足多跳推理，时序知识图谱成为标配 | 技术选型必须考虑图能力 |

---

## 2. 技术路线演进

### 2.1 演进时间线

```
2023 Q3 ─┬─ MemGPT提出"虚拟上下文管理"概念
         │  └─ 核心思想：LLM内存不够用，需要外挂存储
         │
2024 Q1 ─┼─ Mem0发布v1：向量+元数据记忆层
         │  └─ 突破：自动事实抽取，无需手动定义schema
         │
2024 Q3 ─┼─ Zep推出Graphiti：时序知识图谱
         │  └─ 突破：事实有效性窗口，支持"过去vs现在"查询
         │
2025 Q1 ─┼─ MemGPT更名为Letta，发布状态持久化
         │  └─ 突破：Agent可编辑自己的记忆块
         │
2025 Q3 ─┼─ Cognee发布：记忆控制平面
         │  └─ 突破：6行代码集成，本体grounding
         │
2026 Q2 ─┴─ Mem0发布Token高效算法
            └─ 突破：单次提取+实体链接+多信号检索
               LoCoMo 91.6, LongMemEval 94.8
```

### 2.2 架构演进路径

| 阶段 | 架构 | 代表方案 | 局限性 |
|------|------|----------|--------|
| **Phase 1: KV存储** | 简单键值对 + Redis | 早期ChatBot | 无语义理解，无法处理冲突 |
| **Phase 2: 向量检索** | Embedding + Vector DB | RAG系统 | 无时序感知，无法多跳推理 |
| **Phase 3: 图+向量混合** | Vector + Knowledge Graph | Mem0 + Zep | 部署复杂，需要多存储协调 |
| **Phase 4: 时序上下文图** | Temporal Context Graph | Graphiti | Schema偏对话导向 |
| **Phase 5: Token高效记忆** | 单次提取 + 实体链接 | Mem0 v3 | 新算法，社区验证中 |

### 2.3 关键论文与贡献

| 论文 | 年份 | 核心贡献 | 实际影响 |
|------|------|----------|----------|
| **MemGPT** (arXiv:2310.08560) | 2023 | 虚拟上下文管理，分层记忆 | 启发Letta/Mem0 |
| **Mem0 Paper** (arXiv:2504.19413) | 2025 | Extraction + Update管道 | 成为事实抽取标准 |
| **Graphiti** (arXiv:2501.13956) | 2025 | 时序上下文图 | 时序推理最强 |
| **Synthius-Mem** (arXiv:2604.11563) | 2026 | 结构化persona记忆 | 94.37% LoCoMo |
| **MAGMA** (arXiv:2601.03236) | 2026 | 多图Agent记忆架构 | 理论框架 |

---

## 3. 主流框架深度对比

### 3.1 架构对比

| 框架 | 核心架构 | 存储层 | 检索方式 | 时序支持 | 冲突解决 |
|------|----------|--------|----------|----------|----------|
| **Mem0 v3** | Vector + Entity Linking | Qdrant/PG + Redis | 语义+BM25+实体匹配 | ✅ 时间感知检索 | ❌ ADD-only |
| **Graphiti** | 时序知识图谱 | Neo4j | 语义+关键词+图遍历 | ✅ 有效性窗口 | ✅ 事件溯源 |
| **Letta** | 可编辑记忆块 | PostgreSQL | 直接读取 | ❌ | ✅ 手动编辑 |
| **Cognee** | Vector + KG管道 | Neo4j + Vector DB | 灵活管道组合 | ⚠️ 有限 | ❌ |
| **Hindsight** | Retain-Recall-Reflect | 可变 | 结构化反思 | ❌ | ❌ |

### 3.2 Mem0 v3 深度分析

**核心突破（2026年4月）：**

1. **单次ADD-only提取**：一次LLM调用，不更新不删除，记忆只增不减
2. **实体链接**：提取的实体跨记忆链接，提升检索召回率
3. **多信号检索**：语义、BM25关键词、实体匹配并行打分后融合
4. **时间感知检索**：为"当前状态"、"过去事件"、"未来计划"查询排序正确的日期实例

**基准测试结果：**

| 基准 | 旧算法 | 新算法 | Token消耗 | 延迟P50 |
|------|--------|--------|-----------|---------|
| LoCoMo | 71.4 | **91.6** | 7.0K | 0.88s |
| LongMemEval | 67.8 | **94.8** | 6.8K | 1.09s |
| BEAM (1M) | — | **64.1** | 6.7K | 1.00s |
| BEAM (10M) | — | **48.6** | 6.9K | 1.05s |

**技术细节：**
- **提取管道**：单次LLM调用，输出结构化事实列表
- **实体链接**：提取的实体生成Embedding，跨记忆链接
- **检索管道**：语义相似度 + BM25关键词 + 实体匹配，三路并行打分后RRF融合
- **时间推理**：查询时根据时间意图（现在/过去/未来）调整排序

### 3.3 Graphiti 深度分析

**核心特性：**

1. **时序上下文图**：每个事实有有效性窗口（valid_from, valid_to）
2. **事件溯源**：所有变更可追溯到原始episode
3. **增量更新**：支持增量数据更新，无需完全图重建
4. **混合检索**：语义 + 关键词 + 图遍历
5. **MCP服务器**：为Claude、Cursor等提供记忆能力

**图模型设计：**

```
(:User {id, name})-[:HAS_PREFERENCE {weight, valid_from, valid_to}]->(:Preference)
(:User)-[:HAS_EXPERIENCE {timestamp}]->(:Experience)
(:Fact)-[:CONTRADICTS {confidence}]->(:Fact)
(:Fact)-[:SUPERSEDES {timestamp}]->(:Fact)
(:User)-[:SIMILAR_TO {score}]->(:User)
```

**适用场景：**
- 需要查询"用户上季度偏好是什么"的时序场景
- 需要追踪事实演变的审计场景
- 需要跨用户经验复用的推荐场景

### 3.4 Letta 深度分析

**核心特性：**

1. **可编辑记忆块**：Agent可直接读写自己的记忆
2. **状态持久化**：Agent状态跨会话持久保存
3. **模型无关**：支持GPT-5.2、Opus 4.5等
4. **技能系统**：预置记忆和持续学习技能

**记忆块设计：**

```json
{
  "label": "human",
  "value": "Name: Timber. Status: dog. Occupation: building Letta"
}
```

**适用场景：**
- 需要Agent自主管理记忆的场景
- 本地部署、隐私敏感场景
- 快速原型开发

### 3.5 选型建议

针对**Java + DAG Workflow**场景，推荐**混合架构**：

| 组件 | 选型 | 理由 |
|------|------|------|
| **事实抽取层** | 自研（参考Mem0 v3） | 单次提取+实体链接，Java原生 |
| **时序存储层** | Neo4j（参考Graphiti） | 时序知识图谱，Cypher查询 |
| **向量检索层** | Qdrant | 高性能向量检索，Java SDK |
| **缓存层** | Redis | Working Memory，热路径缓存 |
| **事件总线** | Kafka | 异步记忆事件流 |

**选型理由：**
1. Mem0的**单次提取管道**最适合DAG节点产生的离散事件流
2. Graphiti的**时序知识图谱**完美支持"不同维度不同时间有效性"需求
3. 两者均提供REST API，与Java生态无缝集成
4. 避免Letta的Python运行时依赖

---

## 4. 记忆系统核心架构设计

### 4.1 四层记忆模型

针对DAG Workflow Agent，采用**四层记忆模型**，每层具有独立的延迟目标、保留策略和访问模式：

```
┌─────────────────────────────────────────────────────────────┐
│                    Agent Memory Hierarchy                    │
├──────────────┬──────────────┬──────────────┬────────────────┤
│   Layer 1    │   Layer 2    │   Layer 3    │    Layer 4     │
│ Working Mem  │ Episodic Mem │ Semantic Mem │ Governance Mem │
│  (工作记忆)   │  (情景记忆)   │  (语义记忆)   │   (治理记忆)    │
├──────────────┼──────────────┼──────────────┼────────────────┤
│  < 100ms     │  10-600ms    │  50-150ms    │    Async       │
│  Session级   │  30-90天     │   永久保留    │   1-7年       │
│  Redis/内存  │  Vector DB   │  Graph DB    │   OLAP/日志    │
└──────────────┴──────────────┴──────────────┴────────────────┘
```

#### Layer 1: Working Memory（工作记忆）

**职责**：承载当前DAG Workflow的活跃状态、执行计划、中间结果、用户最近3-5轮交互。

**关键设计：**
- **DAG State Store**：基于Redis的Workflow状态机，存储当前节点、已完成路径、待执行边
- **Context Window Buffer**：动态管理注入LLM的上下文，当达到Token阈值（如60%容量）时触发压缩
- **Active Constraints**：当前生效的业务规则、用户临时偏好

#### Layer 2: Episodic Memory（情景记忆）

**职责**：记录具体事件、交互片段、工具执行历史，保留时间上下文和因果关系。

**核心特征：**
- 每条记忆包含：`episode_id`、`timestamp`、`user_id`、`workflow_id`、`node_id`、`entities[]`
- 支持"展示决策依据"的审计追踪
- 30-90天保留期（合规需求可调）

#### Layer 3: Semantic Memory（语义记忆）

**职责**：存储持久化的事实、用户画像维度、业务规则、跨Workflow的通用知识。

**核心特征：**
- 以**知识图谱**形式存储实体关系（用户-偏好-产品-时间轴）
- 支持时序有效性：`valid_at` / `invalid_at`时间戳
- 事实版本化管理，支持"用户上季度偏好是什么"的时序查询

**认知域划分（参考Synthius-Mem六域模型）：**

| 域 | 说明 | 示例 |
|----|------|------|
| BIOGRAPHY | 用户基本信息 | 姓名、年龄、职业 |
| EXPERIENCES | 历史交互经验 | 之前的对话、操作记录 |
| PREFERENCES | 明确表达的偏好 | 喜欢的颜色、常用功能 |
| SOCIAL_CIRCLE | 关联用户/组织 | 同事、家人、所属公司 |
| WORK_CONTEXT | 工作/业务上下文 | 项目、任务、截止日期 |
| PSYCHOMETRICS | 行为模式推断 | 决策风格、沟通偏好 |

#### Layer 4: Governance Memory（治理记忆）

**职责**：决策溯源、策略执行日志、记忆操作审计、合规留存。

**核心特征：**
- 异步写入，不影响主链路延迟
- 记录`retrieval_set_id`用于溯源
- 支持GDPR/个保法的"被遗忘权"实现

### 4.2 记忆生命周期管道

```
┌──────────┐    ┌──────────────┐    ┌─────────────┐    ┌──────────────┐
│  Ingest  │───▶│   Extract    │───▶│   Store     │───▶│   Retrieve   │
│  (摄入)   │    │  (抽取解析)   │    │  (存储索引)  │    │  (检索召回)   │
└──────────┘    └──────────────┘    └─────────────┘    └──────────────┘
      │                │                  │                  │
      ▼                ▼                  ▼                  ▼
  多源输入         实体/关系/事实       向量+图+关键词      混合策略
  消息/日志/文档    冲突检测/去重        时序标注           重排序/合成
```

---

## 5. DAG Workflow适配设计

### 5.1 Workflow记忆注入点

在DAG执行生命周期中，记忆系统在以下节点介入：

```
┌─────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────┐
│  Start  │────▶│ Node A      │────▶│ Node B      │────▶│  End    │
│ (触发)   │     │ (记忆检索)   │     │ (记忆写入)   │     │ (总结)  │
└─────────┘     └─────────────┘     └─────────────┘     └─────────┘
      │               │                   │                  │
      ▼               ▼                   ▼                  ▼
  用户身份解析    检索相关记忆         抽取节点事实        会话总结
  加载用户画像    注入Prompt          写入Episodic        归档Semantic
```

**记忆钩子（Memory Hooks）设计：**

| 钩子 | 时机 | 操作 |
|------|------|------|
| `beforeNodeExecute` | 节点执行前 | 检索相关记忆注入上下文 |
| `afterNodeExecute` | 节点执行后 | 提取事实并存储 |
| `onWorkflowComplete` | Workflow完成时 | 生成会话摘要，升级至Semantic层 |
| `retrieveCrossUser` | 跨Workflow | 检索其他用户的相似经验 |

### 5.2 节点级记忆隔离与共享

| 隔离级别 | 说明 | 示例 |
|---------|------|------|
| **Node-scoped** | 仅当前节点可见 | 节点内部工具调用的临时参数 |
| **Workflow-scoped** | 同Workflow跨节点共享 | 用户在本Workflow中提供的资料 |
| **User-scoped** | 同用户跨Workflow共享 | 用户长期偏好、历史资料 |
| **Org-scoped** | 组织级共享 | 企业知识库、最佳实践 |
| **Global-scoped** | 全局共享 | 通用业务规则 |

---

## 6. Java技术栈选型

### 6.1 核心组件选型

| 组件 | 选型 | 理由 |
|------|------|------|
| **向量数据库** | Qdrant | Java SDK成熟、分布式、支持混合搜索 |
| **图数据库** | Neo4j | 时序知识图谱原生支持、Cypher查询 |
| **缓存/状态** | Redis | Workflow状态、Working Memory、热路径缓存 |
| **Embedding** | 自研HTTP客户端调用 | 兼容OpenAI/Cohere/本地模型(BGE/E5) |
| **消息队列** | Kafka | 记忆事件流、异步提取管道 |
| **主存储** | PostgreSQL + pgvector | 元数据管理、轻量级向量备用 |
| **LLM调用** | Spring AI | Java生态LLM抽象层 |

### 6.2 与Python方案对比

| 维度 | Java方案 | Python方案 (Mem0/Graphiti) |
|------|----------|---------------------------|
| **性能** | 高并发、低延迟 | 相对较低 |
| **部署** | JVM单一进程 | 需要Python运行时 |
| **生态** | Spring Boot、企业级 | LangChain、快速原型 |
| **集成** | 与现有Java系统无缝 | 需要REST API桥接 |
| **维护** | 类型安全、重构友好 | 动态类型、灵活 |

---

## 7. 数据模型与存储设计

### 7.1 统一记忆Schema

**PostgreSQL主存储：记忆元数据与关系**

```sql
CREATE TABLE memory_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(64),
    node_id VARCHAR(64),
    
    -- 记忆分层
    memory_layer VARCHAR(16) NOT NULL CHECK (memory_layer IN ('WORKING', 'EPISODIC', 'SEMANTIC')),
    
    -- 认知域
    domain VARCHAR(32) NOT NULL,
    
    -- 内容
    content TEXT NOT NULL,
    content_vector vector(1536),
    
    -- 时序有效性（Semantic层核心）
    valid_from TIMESTAMP NOT NULL DEFAULT NOW(),
    valid_to TIMESTAMP,
    
    -- 来源追踪
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(128),
    
    -- 实体标签（用于图构建）
    entities JSONB DEFAULT '[]',
    
    -- 置信度与质量
    confidence FLOAT NOT NULL DEFAULT 0.8,
    verification_status VARCHAR(16) DEFAULT 'UNVERIFIED',
    
    -- 访问统计（用于衰减与清理）
    access_count INT DEFAULT 0,
    last_accessed TIMESTAMP,
    
    -- 审计
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(64) NOT NULL
);

-- 复合索引
CREATE INDEX idx_memory_user_layer ON memory_entries(user_id, memory_layer);
CREATE INDEX idx_memory_domain ON memory_entries(domain);
CREATE INDEX idx_memory_valid_time ON memory_entries(valid_from, valid_to);
CREATE INDEX idx_memory_vector ON memory_entries USING ivfflat (content_vector vector_cosine_ops);
```

### 7.2 Qdrant Collection设计

| Collection | 用途 | 向量维度 | 距离度量 |
|------------|------|----------|----------|
| `episodic_memories` | 情景记忆 | 1536 | COSINE |
| `semantic_memories` | 语义记忆 | 1536 | COSINE |
| `user_profiles` | 用户画像 | 1536 | COSINE |

**Payload索引：**
- `user_id` (Keyword)
- `domain` (Keyword)
- `timestamp` (Integer)
- `workflow_id` (Keyword)

### 7.3 Neo4j图模型

```cypher
// 用户-事实-实体 核心模型
(:User {id, name})-[:HAS_PREFERENCE {weight, valid_from, valid_to}]->(:Preference)
(:User)-[:HAS_EXPERIENCE {timestamp}]->(:Experience)
(:User)-[:KNOWS {strength}]->(:Skill)
(:User)-[:BELONGS_TO]->(:Organization)

// 事实间关系
(:Fact)-[:CONTRADICTS {confidence}]->(:Fact)
(:Fact)-[:SUPERSEDES {timestamp}]->(:Fact)
(:Fact)-[:SUPPORTS]->(:Fact)

// 跨用户相似性（用于检索其他用户经验）
(:User)-[:SIMILAR_TO {score}]->(:User)
```

---

## 8. 检索与相关性算法

### 8.1 混合检索策略

针对"不同维度不同用户相关性高的记忆"需求，实现**四路召回+融合重排**：

```
┌──────────────────────────────────────────────────────────────┐
│                    Hybrid Retrieval Engine                    │
├──────────────────────────────────────────────────────────────┤
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │  Vector  │ │  Graph   │ │  BM25    │ │ Temporal │        │
│  │  Search  │ │ Traverse │ │ Keyword  │ │  Filter  │        │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘        │
│       └─────────────┴─────────────┴─────────────┘            │
│                         │                                    │
│                  ┌──────▼───────┐                            │
│                  │ RRF Fusion  │                            │
│                  └──────┬───────┘                            │
│                         │                                    │
│                  ┌──────▼───────┐                            │
│                  │ MMR Diversify│                            │
│                  └──────┬───────┘                            │
│                         │                                    │
│                  ┌──────▼───────┐                            │
│                  │ Temporal Decay│                           │
│                  └──────┬───────┘                            │
│                         │                                    │
│                  ┌──────▼───────┐                            │
│                  │  LLM Synth  │                            │
│                  └─────────────┘                            │
└──────────────────────────────────────────────────────────────┘
```

### 8.2 算法详解

#### RRF (Reciprocal Rank Fusion)

将多路召回结果融合，消除不同检索方式的分数尺度差异：

```
RRF_score(d) = Σ 1/(k + rank_i(d))
```

其中 `k=60` 为平滑参数，`rank_i(d)` 为文档d在第i路召回中的排名。

#### MMR (Maximal Marginal Relevance)

在相关性基础上增加多样性，避免返回过多相似记忆：

```
MMR = λ * relevance - (1-λ) * diversity
```

其中 `λ=0.5` 为平衡参数，`diversity` 为与已选记忆的最大相似度。

#### 时序衰减

近期记忆提升权重，实现"当前有效"优先：

```
decay = exp(-λ * age)
```

半衰期默认30天，可根据业务调整。

---

## 9. 事实抽取与总结管道

### 9.1 单次提取管道（参考Mem0 v3）

**核心设计：** 单次LLM调用，ADD-only，不更新不删除。

**提取流程：**

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  用户消息    │────▶│  LLM提取     │────▶│  实体链接    │
│  + 上下文    │     │  结构化事实  │     │  跨记忆链接  │
└──────────────┘     └──────────────┘     └──────────────┘
                            │
                            ▼
                     ┌──────────────┐
                     │  写入存储    │
                     │  Vector+Graph│
                     └──────────────┘
```

**提取Prompt设计：**

```
You are a memory extraction system. Extract salient facts from the new
exchange while maintaining awareness of the broader conversation context.

CONVERSATION SUMMARY:
{summary}

RECENT MESSAGES:
{recent}

NEW EXCHANGE:
User: {msg}
Assistant: {resp}

Extract facts in JSON format:
{
  "facts": [
    {
      "subject": "entity name",
      "predicate": "relationship/action",
      "object": "target/value",
      "domain": "PREFERENCES|BIOGRAPHY|EXPERIENCES|WORK_CONTEXT",
      "confidence": 0.95,
      "temporal_marker": "current|past|future|recurring",
      "entities": ["entity1", "entity2"]
    }
  ]
}

Rules:
- Only extract explicit facts, not assumptions
- Mark confidence < 0.7 facts as "needs_verification"
- Include temporal context when available
```

### 9.2 实体链接

**核心设计：** 提取的实体生成Embedding，跨记忆链接。

**流程：**
1. 从提取的事实中识别实体
2. 为每个实体生成Embedding
3. 在向量库中检索相似实体
4. 如果相似度 > 阈值，建立链接关系
5. 在检索时，通过实体链接提升召回率

### 9.3 增量总结机制

参考Anthropic Dreaming的异步整合机制：

**触发条件：**
- 用户活跃度下降（如1小时无交互）
- 记忆数量达到阈值
- 定时任务（如每天凌晨）

**整合流程：**
1. 检索用户所有历史记忆
2. 模式识别：发现重复主题
3. 合并重复事实
4. 检测过时事实并标记
5. 生成用户画像更新

---

## 10. 部署架构与性能优化

### 10.1 推荐部署架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         K8s Cluster                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │  Agent App   │  │  Memory Svc  │  │  Extractor   │              │
│  │   (Java)     │  │   (Java)     │  │   Workers    │              │
│  │              │  │              │  │   (Java)     │              │
│  │  DAG Engine  │  │  REST API    │  │  LLM Pipeline│              │
│  │  Memory Hook │  │  Hybrid Retr │  │  Fact Extract│              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                       │
│  ┌──────▼─────────────────▼─────────────────▼───────┐             │
│  │              Redis Cluster (6 nodes)                 │             │
│  │  ├─ Working Memory (Session State)                 │             │
│  │  ├─ Hot Cache (Frequent Memories)                   │             │
│  │  └─ Rate Limiting / Locking                        │             │
│  └────────────────────────────────────────────────────┘             │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Qdrant     │  │    Neo4j     │  │  PostgreSQL  │              │
│  │  Cluster     │  │   Cluster    │  │   + pgvector │              │
│  │  (3 nodes)   │  │  (3 nodes)   │  │  (Primary+2)│              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
│                                                                     │
│  ┌─────────────────────────────────────────────────────┐             │
│  │           Kafka / Pulsar (Event Bus)               │             │
│  │  ├─ memory-events (Topic)                          │             │
│  │  ├─ fact-extracted (Topic)                        │             │
│  │  └─ memory-updates (Topic)                         │             │
│  └─────────────────────────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
```

### 10.2 性能优化策略

| 优化点 | 策略 | 预期效果 |
|--------|------|---------|
| **检索延迟** | Redis缓存Top-K常用记忆 + Qdrant HNSW索引 | < 100ms P99 |
| **写入吞吐** | Kafka缓冲 + 批量写入Qdrant | 10K+ events/s |
| **Token效率** | 记忆合成压缩（6,956 vs 26,000 tokens） | 节省73% |
| **图查询** | Neo4j预计算相似用户 + 物化视图 | 多跳查询< 200ms |
| **冷启动** | 用户画像预加载到Redis | 首查< 50ms |

### 10.3 成本估算

| 组件 | 规格 | 月成本（AWS） | 说明 |
|------|------|--------------|------|
| Qdrant | 3节点，16GB RAM | ~$500 | 向量检索 |
| Neo4j | 3节点，8GB RAM | ~$400 | 图查询 |
| Redis | 6节点集群 | ~$300 | 缓存/状态 |
| PostgreSQL | Primary+2 Replica | ~$400 | 元数据 |
| Kafka | 3节点 | ~$300 | 事件总线 |
| **总计** | | **~$1,900/月** | 10K用户规模 |

---

## 11. 安全与隐私设计

### 11.1 数据隔离

| 隔离级别 | 实现方式 | 说明 |
|----------|----------|------|
| **用户隔离** | Row-level security | 用户只能访问自己的记忆 |
| **组织隔离** | RBAC + Scope | 组织成员可访问组织级记忆 |
| **跨用户** | 匿名化 + 权限控制 | 需显式授权 |
| **PII保护** | 自动检测 + 脱敏 | 手机号、身份证等自动脱敏 |

### 11.2 被遗忘权实现

**GDPR Art.17 合规流程：**

1. 删除向量存储（Qdrant）
2. 删除图数据（Neo4j）
3. 删除主存储（软删除保留审计）
4. 清除缓存（Redis）
5. 记录合规日志

### 11.3 记忆防篡改

**审计链设计：**

- 每次记忆操作记录审计日志
- 包含操作者、操作类型、时间戳、原因
- 支持操作溯源和合规审查

---

## 12. 实施路线图

### Phase 1: 基础设施（Week 1-2）

| 任务 | 产出 | 优先级 |
|------|------|--------|
| 部署Qdrant + Neo4j + Redis集群 | 存储层就绪 | P0 |
| 搭建Kafka事件总线 | 异步管道就绪 | P0 |
| 定义MemoryEvent Schema | 统一事件格式 | P0 |
| 实现Embedding服务客户端 | 向量生成能力 | P0 |

### Phase 2: 核心管道（Week 3-4）

| 任务 | 产出 | 优先级 |
|------|------|--------|
| 实现Ingestion + Extraction管道 | 事实抽取能力 | P0 |
| 实现Vector存储与检索 | 语义检索能力 | P0 |
| 集成DAG Memory Hook | Workflow记忆注入 | P0 |
| 实现基础RRF重排 | 混合检索v1 | P1 |

### Phase 3: 高级特性（Week 5-6）

| 任务 | 产出 | 优先级 |
|------|------|--------|
| Neo4j知识图谱接入 | 时序事实管理 | P1 |
| 实现MMR + 时序衰减 | 高质量检索 | P1 |
| 跨用户相似性检索 | 经验复用能力 | P1 |
| 记忆总结与整合（Dreaming） | 自动归档能力 | P2 |

### Phase 4: 生产就绪（Week 7-8）

| 任务 | 产出 | 优先级 |
|------|------|--------|
| RBAC + PII脱敏 | 安全合规 | P0 |
| 性能压测与调优 | SLA达标 | P0 |
| 监控大盘（检索命中率/延迟） | 可观测性 | P1 |
| 文档与SDK发布 | 开发者体验 | P1 |

---

## 附录A: 核心接口定义

```java
// 统一记忆服务接口
public interface AgentMemoryService {
    
    // 写入
    void store(String userId, MemoryEvent event);
    void storeFact(String userId, MemoryFact fact);
    void storeEpisode(String userId, EpisodicMemory episode);
    
    // 检索
    RetrievalResult retrieve(MemoryQuery query);
    List<ScoredMemory> retrieveSimilar(String userId, String query, int topK);
    List<ScoredMemory> retrieveCrossUser(String currentUserId, String query, int topK);
    
    // 管理
    void consolidate(String userId);
    void invalidate(String memoryId, String reason);
    void deleteUser(String userId);  // 被遗忘权
    
    // 统计
    MemoryStats getStats(String userId);
}

// 记忆查询对象
@Data
@Builder
public class MemoryQuery {
    private String queryText;
    private String userId;
    private List<CognitiveDomain> domains;
    private MemoryScope scope;
    private TimeRange timeRange;
    private boolean includeCrossUser;
    private boolean synthesize;
    private int topK;
}
```

## 附录B: 参考基准与论文

| 基准/论文 | 核心指标 | 参考价值 |
|-----------|---------|---------|
| **LoCoMo** | 1,540 questions, single/multi-hop, temporal | 时序检索能力评估 |
| **LongMemEval** | 500 questions, knowledge updates, multi-session | 长期记忆准确性 |
| **BEAM** | 1M/10M token scale evaluations | 大规模记忆压力测试 |
| **Mem0 Paper** (arXiv:2504.19413) | Extraction + Update pipeline | 增量处理架构参考 |
| **Graphiti** (arXiv:2501.13956) | Temporal context graphs | 时序图谱参考 |
| **Synthius-Mem** (arXiv:2604.11563) | 94.37% LoCoMo, 99.55% adversarial | 结构化persona记忆 |
| **MAGMA** (arXiv:2601.03236) | Multi-graph agentic memory | 多图记忆架构 |

## 附录C: 部署检查清单

- [ ] Qdrant集群部署完成
- [ ] Neo4j集群部署完成
- [ ] Redis集群部署完成
- [ ] Kafka集群部署完成
- [ ] PostgreSQL部署完成
- [ ] Embedding服务配置完成
- [ ] LLM服务配置完成
- [ ] 监控系统配置完成
- [ ] 告警规则配置完成
- [ ] 备份策略配置完成

---

*文档版本：v2.0 | 最后更新：2026年5月*
