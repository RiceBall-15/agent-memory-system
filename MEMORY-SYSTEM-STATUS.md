# Agent记忆中台 - 项目状态文档

> 最后更新: 2026-05-28 01:55 | 持续优化中，目标截止: 2026-05-28 02:00

## 项目概览

| 指标 | 数值 |
|------|------|
| Java源文件 | 110+个 |
| 总代码行数 | 30,000+行 |
| 测试文件 | 20个 |
| 技术栈 | Spring Boot 3.x + JDK 21 (虚拟线程) |
| 存储层 | Milvus(向量) + Neo4j(图) + MySQL(关系) |
| 缓存 | Caffeine(L1) + Redis(L2) |
| 稳定性 | Resilience4j (熔断+重试) |
| 监控 | OpenTelemetry + Actuator + Micrometer |
| 插件化 | SPI StoragePlugin机制 |

## 已完成优化项 (11/12)

### Phase 1 - 基础架构优化
| 优化项 | 状态 | 说明 |
|--------|------|------|
| opt1. Caffeine缓存 | ✅ | 替代手写LRU，W-TinyLFU策略 |
| opt2. Resilience4j | ✅ | 工业级熔断+重试 |
| opt3. SPI插件化 | ✅ | StoragePlugin接口+Registry |
| opt4. 虚拟线程池统一 | ✅ | ThreadConfig统一管理 |
| opt5. Actuator监控 | ✅ | 健康检查+指标暴露 |

### Phase 2 - 代码质量优化
| 优化项 | 状态 | 说明 |
|--------|------|------|
| opt6. Resilience4j迁移 | ✅ | ConcurrentWriteService完全迁移(-41行) |
| opt7. CompletableFuture | ✅ | 3处指定boundedExecutor |
| opt8. 日志体系整改 | ✅ | 403处System.out→SLF4J(34文件) |
| opt9. MemoryController | ✅ | 7个API端点完整CRUD |

### Phase 3 - 功能增强
| 优化项 | 状态 | 说明 |
|--------|------|------|
| opt10. 记忆类型体系 | ✅ | MemoryType枚举+BM25混合检索 |
| opt11. 记忆变更历史 | ✅ | 版本历史+回滚+审计日志API |

### 待实施
| 优化项 | 状态 | 说明 |
|--------|------|------|
| opt12. 单元测试 | ⏳ | 覆盖率提升至80% |

## API端点清单

### 记忆管理
| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/memories` | 从对话提取并保存记忆 |
| GET | `/api/memories/{id}` | 获取单条记忆 |
| GET | `/api/memories` | 搜索/列表查询(支持memoryType过滤) |
| PUT | `/api/memories/{id}` | 更新记忆 |
| DELETE | `/api/memories/{id}` | 删除记忆 |
| POST | `/api/memories/batch-delete` | 批量删除 |
| POST | `/api/memories/search` | 语义搜索 |

### 版本历史
| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/memories/{id}/history` | 获取记忆版本历史 |
| POST | `/api/memories/{id}/rollback/{version}` | 回滚到指定版本 |

### 审计日志
| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/audit-logs` | 查询审计日志(支持memoryId/userId过滤) |

### 系统运维
| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/metrics` | 指标查询 |
| GET | `/api/vector/stats` | 向量存储统计 |
| GET | `/api/graph/stats` | 图存储统计 |

## 记忆类型体系

| 类型 | 说明 | 用途 |
|------|------|------|
| SEMANTIC | 语义记忆 | 事实、知识、概念（默认） |
| EPISODIC | 情景记忆 | 对话经历、事件 |
| PROCEDURAL | 程序记忆 | 操作步骤、技能 |
| WORKING | 工作记忆 | 当前上下文 |

## 检索策略 (五阶段)

```
Phase 1: 向量检索 (Milvus) → top2K候选
Phase 2: BM25重排 (关键词匹配)
Phase 3: 实体增强 (Neo4j图遍历)
Phase 4: 融合排序 (FusionScorer)
Phase 5: 阈值过滤 → 最终topK
```

## 框架调研参考

基于Mem0、Letta(MemGPT)、Cognee、Zep、LangChain五大框架调研。
详见 [FRAMEWORK-RESEARCH.md](FRAMEWORK-RESEARCH.md)

关键借鉴：
- **Mem0**: 记忆类型分类、BM25混合检索、变更历史
- **Letta**: 分层记忆模型(Core/Archival/Recall)、Token感知
- **Cognee**: 流水线架构、Graph RAG
- **Zep**: 时间知识图谱、事实抽取

## Git提交记录 (今日)

| 提交 | 说明 |
|------|------|
| `fa3e608` | opt6+opt7: Resilience4j迁移+CompletableFuture优化 |
| `1785708` | opt9: MemoryController CRUD完整实现 |
| `62835ca` | opt10: 记忆类型体系+BM25混合检索+框架调研 |
| `a9ae799` | opt11: 记忆变更历史API |

## 项目架构图

```
┌─────────────────────────────────────────────────────────┐
│                    REST API Layer                        │
│  MemoryController (CRUD + History + Audit)               │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                  Service Layer                           │
│  ConcurrentWriteService │ HybridRetrievalService         │
│  MemoryExtractionService│ MemoryVersionService           │
│  AuditLogService        │ FusionScorer │ Bm25Scorer      │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│               Storage Plugin Layer (SPI)                 │
│  VectorStore(Milvus) │ GraphStore(Neo4j) │ MetadataStore │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ StoragePluginRegistry (动态发现+注册)                 │ │
│  └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│                Infrastructure Layer                      │
│  Caffeine(L1) │ Redis(L2) │ Resilience4j │ 虚拟线程       │
│  OpenTelemetry│ Actuator  │ Graceful Shutdown            │
└─────────────────────────────────────────────────────────┘
```
