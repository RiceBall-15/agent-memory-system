     1|# Agent 记忆系统深度技术调研与设计方案
     2|
     3|> 基于腾讯云向量数据库的企业级 Agent 记忆中台技术设计  
     4|> 版本: v3.0 | 更新时间: 2026-05-27
     5|
     6|---
     7|
     8|## 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 行业对标：Mem0/Graphiti/Letta 深度分析](#2-行业对标mem0graphitletta-深度分析)
- [3. 插件化存储架构设计](#3-插件化存储架构设计)
- [4. 核心架构设计](#4-核心架构设计)
- [5. 记忆提取引擎](#5-记忆提取引擎)
- [6. 混合检索引擎](#6-混合检索引擎)
- [7. 知识图谱集成](#7-知识图谱集成)
- [8. 高并发与极端场景处理](#8-高并发与极端场景处理)
- [9. API 接口设计](#9-api-接口设计)
- [10. 前端监控界面设计](#10-前端监控界面设计)
- [11. 腾讯云集成方案](#11-腾讯云集成方案)
- [12. 部署架构](#12-部署架构)
- [13. 实施路线图](#13-实施路线图)

## 1. 背景与目标
    26|
    27|### 1.1 问题定义
    28|
    29|当前 Agent 系统面临的核心挑战：
    30|
    31|| 挑战 | 描述 | 影响 |
    32||------|------|------|
    33|| 上下文窗口限制 | LLM 单次对话的 token 上限 | 长对话丢失早期关键信息 |
    34|| 无状态设计缺陷 | 每次对话独立，无法积累经验 | 重复询问相同偏好 |
    35|| 语义检索低效 | 简单关键词匹配无法理解意图 | 检索结果不精准 |
    36|| 多 Agent 协作断裂 | 独立 Agent 之间无法共享记忆 | 任务交接丢失上下文 |
    37|
    38|### 1.2 设计目标
    39|
    40|作为企业级**记忆中台**，需要支持：
    41|
    42|```
    43|┌─────────────────────────────────────────────────────────┐
    44|│                    Agent 记忆中台目标                       │
    45|├─────────────────────────────────────────────────────────┤
    46|│  ✓ 长期记忆持久化    支持跨会话、跨 Agent 的记忆存储       │
    47|│  ✓ 语义级检索       基于向量相似度的智能检索               │
    48|│  ✓ 多维度查询       时间/类型/重要性/关联性多维过滤        │
    49|│  ✓ 对外 API 服务    标准化 RESTful 接口供外部系统调用      │
    50|│  ✓ 腾讯云集成       利用企业级向量数据库保障性能与可靠性   │
    51|│  ✓ 高并发支持       万级 QPS 的读写能力                   │
    52|│  ✓ 监控运维         完善的指标监控和告警体系               │
    53|│  ✓ 多租户隔离       不同业务线的数据隔离                  │
    54|└─────────────────────────────────────────────────────────┘
    55|```
    56|
    57|---
    58|
    59|## 2. 行业对标：Mem0/Graphiti/Letta 深度分析
    60|
    61|### 2.1 Mem0 v3 架构分析（GitHub 25K+ Stars）
    62|
    63|Mem0 是目前最成熟的 Agent 记忆层项目，2026年4月发布了革命性的 v3 算法。
    64|
    65|**核心创新点：**
    66|
    67|| 技术特性 | 实现方式 | 我们的借鉴 |
    68||----------|----------|------------|
    69|| **ADD-only 单次提取** | 一次 LLM 调用提取所有记忆，不做 UPDATE/DELETE | 采用，避免记忆覆盖 |
    70|| **实体链接** | 实体提取 + 嵌入 + 跨记忆关联 | 集成到知识图谱层 |
    71|| **多信号融合检索** | 语义 + BM25 + 实体 boost 并行打分 | 核心检索策略 |
    72|| **时间感知检索** | 解析相对时间引用，锚定到绝对日期 | 必须实现 |
    73|
    74|**Mem0 检索评分算法（源码分析）：**
    75|
    76|```python
    77|# Mem0 核心评分公式（简化）
    78|combined_score = (semantic_score + bm25_score + entity_boost) / max_possible
    79|
    80|# 其中：
    81|# - semantic_score: HNSW 向量检索得分 [0, 1]
    82|# - bm25_score: 关键词匹配得分，经 sigmoid 归一化 [0, 1]
    83|# - entity_boost: 实体关联加权，最大 0.5
    84|# - max_possible: 根据可用信号动态计算 (1.0 ~ 2.5)
    85|```
    86|
    87|**BM25 归一化参数（查询长度自适应）：**
    88|
    89|| 查询词数 | Sigmoid 中点 | 陡峭度 |
    90||----------|-------------|--------|
    91|| ≤3 | 5.0 | 0.7 |
    92|| 4-6 | 7.0 | 0.6 |
    93|| 7-9 | 9.0 | 0.5 |
    94|| 10-15 | 10.0 | 0.5 |
    95|| >15 | 12.0 | 0.5 |
    96|
    97|### 2.2 Graphiti 知识图谱分析（Zep 团队）
    98|
    99|Graphiti 是 Zep 的开源时间感知上下文图谱引擎，论文发表于 arXiv。
   100|
   101|**核心架构：**
   102|
   103|| 组件 | 存储内容 | 作用 |
   104||------|----------|------|
   105|| **Entities（节点）** | 人物、产品、策略、概念 | 实体的摘要随时间演进 |
   106|| **Facts/Relationships（边）** | 三元组 + 时间有效性窗口 | 追踪事实何时成立、何时被取代 |
   107|| **Episodes（溯源）** | 原始输入数据 | 所有派生事实的源头 |
   108|| **Custom Types（本体）** | 开发者定义的实体/边类型 | Pydantic 模型定义 |
   109|
   110|**Graphiti vs 传统 RAG：**
   111|
   112|| 维度 | 传统 RAG | Graphiti |
   113||------|----------|----------|
   114|| 数据结构 | 扁平文档块 | 时序图结构 |
   115|| 更新方式 | 全量重建 | 增量更新 |
   116|| 时间感知 | 无 | 有效期窗口 |
   117|| 关系追踪 | 无 | 实体关系图 |
   118|| 检索方式 | 纯语义 | 语义+图遍历 |
   119|
   120|### 2.3 Letta（原 MemGPT）分析
   121|
   122|Letta 实现了"无限上下文窗口"的虚拟上下文管理。
   123|
   124|**核心机制：**
   125|
   126|- **分层记忆架构**：核心记忆 → 归档记忆 → 冻结记忆
   127|- **主动管理**：Agent 自主决定何时存储、检索、遗忘
   128|- **压缩策略**：LLM 摘要 + 重要性评分
   129|
   130|---
   131|
   132|## 4. 插件化存储架构设计

### 3.1 设计理念

系统采用**适配器模式 + 策略模式**，将存储层完全抽象化：

```
┌─────────────────────────────────────────────────────────────┐
│                      应用层 (业务代码)                       │
├─────────────────────────────────────────────────────────────┤
│                      接口层 (统一抽象)                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
│  │ VectorStore  │ │ GraphStore   │ │ MetadataStore│       │
│  │  Interface   │ │  Interface   │ │  Interface   │       │
│  └──────────────┘ └──────────────┘ └──────────────┘       │
├─────────────────────────────────────────────────────────────┤
│                      适配器层 (插件实现)                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ VectorStore Adapters:                                │  │
│  │   ├─ TencentVectorDBAdapter (腾讯云)                  │  │
│  │   ├─ MilvusAdapter (开源)                             │  │
│  │   ├─ PineconeAdapter (SaaS)                          │  │
│  │   ├─ WeaviateAdapter (开源)                           │  │
│  │   ├─ QdrantAdapter (开源)                             │  │
│  │   └─ ChromaDBAdapter (本地开发)                       │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ GraphStore Adapters:                                 │  │
│  │   ├─ Neo4jAdapter (企业级)                            │  │
│  │   ├─ FalkorDBAdapter (轻量级)                         │  │
│  │   ├─ TigerGraphAdapter (大规模)                       │  │
│  │   ├─ JanusGraphAdapter (分布式)                       │  │
│  │   └─ NetworkXAdapter (本地开发)                       │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ MetadataStore Adapters:                              │  │
│  │   ├─ MySQLAdapter (关系型)                            │  │
│  │   ├─ PostgreSQLAdapter (关系型)                       │  │
│  │   ├─ MongoDBAdapter (文档型)                          │  │
│  │   ├─ TiDBAdapter (分布式)                             │  │
│  │   └─ SQLiteAdapter (本地开发)                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 统一接口定义

#### 3.2.1 向量存储接口

```python
from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional
from dataclasses import dataclass

@dataclass
class VectorRecord:
    """统一的向量记录格式"""
    id: str
    vector: List[float]
    payload: Dict[str, Any]
    metadata: Optional[Dict[str, Any]] = None

@dataclass
class SearchResult:
    """统一的搜索结果格式"""
    id: str
    score: float
    payload: Dict[str, Any]
    vector: Optional[List[float]] = None

class VectorStoreInterface(ABC):
    """向量存储统一接口"""
    
    @abstractmethod
    def __init__(self, config: Dict[str, Any]):
        """初始化连接"""
        pass
    
    @abstractmethod
    async def create_collection(
        self, 
        collection_name: str,
        dimension: int,
        distance_metric: str = "cosine",
        **kwargs
    ) -> bool:
        """创建集合/索引"""
        pass
    
    @abstractmethod
    async def upsert(
        self,
        collection_name: str,
        records: List[VectorRecord]
    ) -> bool:
        """插入或更新向量"""
        pass
    
    @abstractmethod
    async def search(
        self,
        collection_name: str,
        query_vector: List[float],
        top_k: int = 10,
        filters: Optional[Dict[str, Any]] = None,
        **kwargs
    ) -> List[SearchResult]:
        """向量搜索"""
        pass
    
    @abstractmethod
    async def delete(
        self,
        collection_name: str,
        ids: List[str]
    ) -> bool:
        """删除向量"""
        pass
    
    @abstractmethod
    async def get(
        self,
        collection_name: str,
        ids: List[str]
    ) -> List[VectorRecord]:
        """获取向量"""
        pass
    
    @abstractmethod
    async def health_check(self) -> bool:
        """健康检查"""
        pass
    
    @abstractmethod
    async def get_stats(self, collection_name: str) -> Dict[str, Any]:
        """获取统计信息"""
        pass
```

#### 3.2.2 图存储接口

```python
@dataclass
class GraphNode:
    """统一的图节点格式"""
    id: str
    label: str  # 节点类型
    properties: Dict[str, Any]

@dataclass
class GraphEdge:
    """统一的图边格式"""
    id: str
    source: str
    target: str
    relationship: str
    properties: Dict[str, Any]

class GraphStoreInterface(ABC):
    """图存储统一接口"""
    
    @abstractmethod
    def __init__(self, config: Dict[str, Any]):
        """初始化连接"""
        pass
    
    @abstractmethod
    async def create_node(
        self,
        node: GraphNode
    ) -> str:
        """创建节点"""
        pass
    
    @abstractmethod
    async def create_edge(
        self,
        edge: GraphEdge
    ) -> str:
        """创建边"""
        pass
    
    @abstractmethod
    async def get_node(
        self,
        node_id: str
    ) -> Optional[GraphNode]:
        """获取节点"""
        pass
    
    @abstractmethod
    async def traverse(
        self,
        start_node: str,
        relationship_types: Optional[List[str]] = None,
        direction: str = "both",
        max_depth: int = 3,
        filters: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        """图遍历"""
        pass
    
    @abstractmethod
    async def search_nodes(
        self,
        label: str,
        properties: Dict[str, Any],
        limit: int = 100
    ) -> List[GraphNode]:
        """搜索节点"""
        pass
    
    @abstractmethod
    async def delete(
        self,
        node_ids: Optional[List[str]] = None,
        edge_ids: Optional[List[str]] = None
    ) -> bool:
        """删除节点或边"""
        pass
    
    @abstractmethod
    async def health_check(self) -> bool:
        """健康检查"""
        pass
```

#### 3.2.3 元数据存储接口

```python
@dataclass
class MetadataRecord:
    """统一的元数据记录格式"""
    id: str
    table_name: str
    data: Dict[str, Any]
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

class MetadataStoreInterface(ABC):
    """元数据存储统一接口"""
    
    @abstractmethod
    def __init__(self, config: Dict[str, Any]):
        """初始化连接"""
        pass
    
    @abstractmethod
    async def insert(
        self,
        table_name: str,
        record: MetadataRecord
    ) -> str:
        """插入记录"""
        pass
    
    @abstractmethod
    async def batch_insert(
        self,
        table_name: str,
        records: List[MetadataRecord]
    ) -> List[str]:
        """批量插入"""
        pass
    
    @abstractmethod
    async def find(
        self,
        table_name: str,
        filters: Dict[str, Any],
        limit: int = 100,
        offset: int = 0
    ) -> List[MetadataRecord]:
        """查询记录"""
        pass
    
    @abstractmethod
    async def update(
        self,
        table_name: str,
        record_id: str,
        updates: Dict[str, Any]
    ) -> bool:
        """更新记录"""
        pass
    
    @abstractmethod
    async def delete(
        self,
        table_name: str,
        record_ids: List[str]
    ) -> bool:
        """删除记录"""
        pass
    
    @abstractmethod
    async def count(
        self,
        table_name: str,
        filters: Optional[Dict[str, Any]] = None
    ) -> int:
        """计数"""
        pass
    
    @abstractmethod
    async def health_check(self) -> bool:
        """健康检查"""
        pass
```

### 3.3 适配器实现示例

#### 3.3.1 腾讯云 VectorDB 适配器

```python
from tencentcloud.vdb.v20240613 import vdb_client, models

class TencentVectorDBAdapter(VectorStoreInterface):
    """腾讯云向量数据库适配器"""
    
    def __init__(self, config: Dict[str, Any]):
        self.secret_id = config['secret_id']
        self.secret_key = config['secret_key']
        self.instance_id = config['instance_id']
        self.region = config.get('region', 'ap-guangzhou')
        
        # 初始化客户端
        credential = Credential(self.secret_id, self.secret_key)
        self.client = VdbClient(credential, self.region)
    
    async def create_collection(
        self,
        collection_name: str,
        dimension: int,
        distance_metric: str = "cosine",
        **kwargs
    ) -> bool:
        """创建集合"""
        request = CreateCollectionRequest()
        request.InstanceId = self.instance_id
        request.CollectionName = collection_name
        request.Dimension = dimension
        request.MetricType = self._map_metric(distance_metric)
        
        # HNSW 参数
        request.IndexType = kwargs.get('index_type', 'HNSW')
        request.HnswParameters = {
            'M': kwargs.get('m', 16),
            'EfConstruction': kwargs.get('ef_construction', 256)
        }
        
        try:
            await self.client.create_collection(request)
            return True
        except Exception as e:
            logger.error(f"Create collection failed: {e}")
            return False
    
    async def search(
        self,
        collection_name: str,
        query_vector: List[float],
        top_k: int = 10,
        filters: Optional[Dict[str, Any]] = None,
        **kwargs
    ) -> List[SearchResult]:
        """向量搜索"""
        request = SearchRequest()
        request.InstanceId = self.instance_id
        request.CollectionName = collection_name
        request.QueryVector = query_vector
        request.TopK = top_k
        
        # 过滤条件
        if filters:
            request.Filter = self._build_filter(filters)
        
        response = await self.client.search(request)
        
        return [
            SearchResult(
                id=item.Id,
                score=item.Score,
                payload=item.Metadata
            )
            for item in response.Results
        ]
    
    def _map_metric(self, metric: str) -> str:
        """映射指标名称"""
        mapping = {
            'cosine': 'COSINE',
            'euclidean': 'L2',
            'dot': 'IP'
        }
        return mapping.get(metric, 'COSINE')
    
    def _build_filter(self, filters: Dict[str, Any]) -> str:
        """构建过滤表达式"""
        # 转换为腾讯云VectorDB的过滤语法
        parts = []
        for key, value in filters.items():
            if isinstance(value, list):
                parts.append(f"{key} IN ({','.join(map(str, value))})")
            elif isinstance(value, dict):
                for op, val in value.items():
                    parts.append(f"{key} {op} {val}")
            else:
                parts.append(f"{key} = '{value}'")
        return " AND ".join(parts)
    
    async def health_check(self) -> bool:
        try:
            await self.client.describe_instance_status(
                InstanceIds=[self.instance_id]
            )
            return True
        except:
            return False
```

#### 3.3.2 Milvus 适配器

```python
from pymilvus import connections, Collection, FieldSchema, CollectionSchema, DataType

class MilvusAdapter(VectorStoreInterface):
    """Milvus 向量数据库适配器"""
    
    def __init__(self, config: Dict[str, Any]):
        self.host = config['host']
        self.port = config.get('port', 19530)
        self.collection_prefix = config.get('collection_prefix', '')
        
        connections.connect(
            alias="default",
            host=self.host,
            port=self.port
        )
    
    async def create_collection(
        self,
        collection_name: str,
        dimension: int,
        distance_metric: str = "cosine",
        **kwargs
    ) -> bool:
        """创建集合"""
        full_name = f"{self.collection_prefix}{collection_name}"
        
        fields = [
            FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
            FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=dimension),
            FieldSchema(name="payload", dtype=DataType.JSON),
        ]
        
        schema = CollectionSchema(fields, enable_dynamic_field=True)
        
        # 索引参数
        index_params = {
            "metric_type": self._map_metric(distance_metric),
            "index_type": kwargs.get('index_type', 'HNSW'),
            "params": {
                "M": kwargs.get('m', 16),
                "efConstruction": kwargs.get('ef_construction', 256)
            }
        }
        
        collection = Collection(full_name, schema)
        collection.create_index("vector", index_params)
        
        return True
    
    async def search(
        self,
        collection_name: str,
        query_vector: List[float],
        top_k: int = 10,
        filters: Optional[Dict[str, Any]] = None,
        **kwargs
    ) -> List[SearchResult]:
        """向量搜索"""
        full_name = f"{self.collection_prefix}{collection_name}"
        collection = Collection(full_name)
        collection.load()
        
        search_params = {
            "metric_type": self._map_metric(kwargs.get('metric', 'cosine')),
            "params": {"ef": kwargs.get('ef', 128)}
        }
        
        expr = self._build_expr(filters) if filters else None
        
        results = collection.search(
            data=[query_vector],
            anns_field="vector",
            param=search_params,
            limit=top_k,
            expr=expr,
            output_fields=["payload"]
        )
        
        return [
            SearchResult(
                id=hit.id,
                score=hit.score,
                payload=hit.entity.get('payload', {})
            )
            for hit in results[0]
        ]
    
    def _map_metric(self, metric: str) -> str:
        mapping = {
            'cosine': 'COSINE',
            'euclidean': 'L2',
            'dot': 'IP'
        }
        return mapping.get(metric, 'COSINE')
    
    def _build_expr(self, filters: Dict[str, Any]) -> str:
        """构建Milvus表达式"""
        # 简化的过滤表达式构建
        parts = []
        for key, value in filters.items():
            if isinstance(value, list):
                parts.append(f'payload["{key}"] in {value}')
            elif isinstance(value, dict):
                for op, val in value.items():
                    parts.append(f'payload["{key}"] {op} {val}')
            else:
                parts.append(f'payload["{key}"] == "{value}"')
        return " && ".join(parts)
```

#### 3.3.3 Neo4j 图适配器

```python
from neo4j import AsyncGraphDatabase

class Neo4jAdapter(GraphStoreInterface):
    """Neo4j 图数据库适配器"""
    
    def __init__(self, config: Dict[str, Any]):
        self.uri = config['uri']
        self.user = config['user']
        self.password = config['password']
        self.database = config.get('database', 'neo4j')
        
        self.driver = AsyncGraphDatabase.driver(
            self.uri, auth=(self.user, self.password)
        )
    
    async def create_node(self, node: GraphNode) -> str:
        """创建节点"""
        query = """
        CREATE (n:$$label {id: $id, properties: $properties})
        RETURN n.id
        """
        query = query.replace('$$label', node.label)
        
        async with self.driver.session(database=self.database) as session:
            result = await session.run(
                query,
                id=node.id,
                properties=node.properties
            )
            return (await result.single())[0]
    
    async def create_edge(self, edge: GraphEdge) -> str:
        """创建边"""
        query = """
        MATCH (source {id: $source_id})
        MATCH (target {id: $target_id})
        CREATE (source)-[r:$$relationship {id: $id, properties: $properties}]->(target)
        RETURN r.id
        """
        query = query.replace('$$relationship', edge.relationship)
        
        async with self.driver.session(database=self.database) as session:
            result = await session.run(
                query,
                id=edge.id,
                source_id=edge.source,
                target_id=edge.target,
                properties=edge.properties
            )
            return (await result.single())[0]
    
    async def traverse(
        self,
        start_node: str,
        relationship_types: Optional[List[str]] = None,
        direction: str = "both",
        max_depth: int = 3,
        filters: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        """图遍历"""
        # 构建遍历查询
        rel_clause = ""
        if relationship_types:
            rel_clause = ":" + "|".join(relationship_types)
        
        if direction == "outgoing":
            rel_pattern = f"-[r{rel_clause}]->"
        elif direction == "incoming":
            rel_pattern = f"<-[r{rel_clause}]-"
        else:
            rel_pattern = f"-[r{rel_clause}]-"
        
        query = f"""
        MATCH path = (start {{id: $start_id}}){rel_pattern}(end)
        WHERE length(path) <= $max_depth
        RETURN path, nodes(path) as nodes, relationships(path) as rels
        LIMIT 1000
        """
        
        async with self.driver.session(database=self.database) as session:
            result = await session.run(
                query,
                start_id=start_node,
                max_depth=max_depth
            )
            
            paths = []
            async for record in result:
                paths.append({
                    'path': record['path'],
                    'nodes': record['nodes'],
                    'relationships': record['rels']
                })
            return paths
```

### 3.4 适配器工厂

```python
from typing import Type
import importlib

class StorageFactory:
    """存储适配器工厂"""
    
    # 注册的适配器
    _vector_adapters: Dict[str, Type[VectorStoreInterface]] = {}
    _graph_adapters: Dict[str, Type[GraphStoreInterface]] = {}
    _metadata_adapters: Dict[str, Type[MetadataStoreInterface]] = {}
    
    @classmethod
    def register_vector_adapter(cls, name: str, adapter_class: Type[VectorStoreInterface]):
        """注册向量存储适配器"""
        cls._vector_adapters[name] = adapter_class
    
    @classmethod
    def register_graph_adapter(cls, name: str, adapter_class: Type[GraphStoreInterface]):
        """注册图存储适配器"""
        cls._graph_adapters[name] = adapter_class
    
    @classmethod
    def register_metadata_adapter(cls, name: str, adapter_class: Type[MetadataStoreInterface]):
        """注册元数据存储适配器"""
        cls._metadata_adapters[name] = adapter_class
    
    @classmethod
    def create_vector_store(cls, store_type: str, config: Dict[str, Any]) -> VectorStoreInterface:
        """创建向量存储实例"""
        if store_type not in cls._vector_adapters:
            raise ValueError(f"Unknown vector store type: {store_type}. "
                           f"Available: {list(cls._vector_adapters.keys())}")
        return cls._vector_adapters[store_type](config)
    
    @classmethod
    def create_graph_store(cls, store_type: str, config: Dict[str, Any]) -> GraphStoreInterface:
        """创建图存储实例"""
        if store_type not in cls._graph_adapters:
            raise ValueError(f"Unknown graph store type: {store_type}. "
                           f"Available: {list(cls._graph_adapters.keys())}")
        return cls._graph_adapters[store_type](config)
    
    @classmethod
    def create_metadata_store(cls, store_type: str, config: Dict[str, Any]) -> MetadataStoreInterface:
        """创建元数据存储实例"""
        if store_type not in cls._metadata_adapters:
            raise ValueError(f"Unknown metadata store type: {store_type}. "
                           f"Available: {list(cls._metadata_adapters.keys())}")
        return cls._metadata_adapters[store_type](config)
    
    @classmethod
    def auto_discover(cls, plugin_dirs: List[str]):
        """自动发现并注册插件"""
        for plugin_dir in plugin_dirs:
            import glob
            for plugin_path in glob.glob(f"{plugin_dir}/*.py"):
                module_name = Path(plugin_path).stem
                spec = importlib.util.spec_from_file_location(
                    module_name, plugin_path
                )
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                
                # 查找并注册适配器
                for attr_name in dir(module):
                    attr = getattr(module, attr_name)
                    if isinstance(attr, type):
                        if issubclass(attr, VectorStoreInterface) and attr != VectorStoreInterface:
                            cls.register_vector_adapter(attr_name.lower(), attr)
                        elif issubclass(attr, GraphStoreInterface) and attr != GraphStoreInterface:
                            cls.register_graph_adapter(attr_name.lower(), attr)
                        elif issubclass(attr, MetadataStoreInterface) and attr != MetadataStoreInterface:
                            cls.register_metadata_adapter(attr_name.lower(), attr)


# 预注册内置适配器
def register_builtin_adapters():
    """注册内置适配器"""
    StorageFactory.register_vector_adapter('tencent', TencentVectorDBAdapter)
    StorageFactory.register_vector_adapter('milvus', MilvusAdapter)
    StorageFactory.register_vector_adapter('pinecone', PineconeAdapter)
    StorageFactory.register_vector_adapter('weaviate', WeaviateAdapter)
    StorageFactory.register_vector_adapter('qdrant', QdrantAdapter)
    StorageFactory.register_vector_adapter('chroma', ChromaDBAdapter)
    
    StorageFactory.register_graph_adapter('neo4j', Neo4jAdapter)
    StorageFactory.register_graph_adapter('falkordb', FalkorDBAdapter)
    StorageFactory.register_graph_adapter('tigergraph', TigerGraphAdapter)
    StorageFactory.register_graph_adapter('networkx', NetworkXAdapter)
    
    StorageFactory.register_metadata_adapter('mysql', MySQLAdapter)
    StorageFactory.register_metadata_adapter('postgresql', PostgreSQLAdapter)
    StorageFactory.register_metadata_adapter('mongodb', MongoDBAdapter)
    StorageFactory.register_metadata_adapter('sqlite', SQLiteAdapter)
```

### 3.5 配置化切换

```yaml
# config.yaml - 存储配置
storage:
  # 向量存储配置
  vector:
    type: tencent  # 可切换: tencent, milvus, pinecone, weaviate, qdrant, chroma
    config:
      # 腾讯云配置
      secret_id: ${TENCENT_SECRET_ID}
      secret_key: ${TENCENT_SECRET_KEY}
      instance_id: vdb-xxx
      region: ap-guangzhou
      
      # 替换为 Milvus 配置
      # host: localhost
      # port: 19530
      
      # 替换为 Pinecone 配置
      # api_key: ${PINECONE_API_KEY}
      # environment: us-west1-gcp
      # index_name: memories
      
    collection_prefix: "memory_"
    default_dimension: 1536
  
  # 图存储配置
  graph:
    type: neo4j  # 可切换: neo4j, falkordb, tigergraph, networkx
    config:
      # Neo4j 配置
      uri: bolt://localhost:7687
      user: neo4j
      password: ${NEO4J_PASSWORD}
      database: memory_graph
      
      # 替换为 FalkorDB 配置
      # host: localhost
      # port: 6379
      
      # 替换为 NetworkX 配置（本地开发）
      # graph_file: ./data/graph.json
  
  # 元数据存储配置
  metadata:
    type: mysql  # 可切换: mysql, postgresql, mongodb, sqlite
    config:
      # MySQL 配置
      host: localhost
      port: 3306
      user: memory_user
      password: ${MYSQL_PASSWORD}
      database: agent_memory
      
      # 替换为 PostgreSQL 配置
      # host: localhost
      # port: 5432
      
      # 替换为 MongoDB 配置
      # uri: mongodb://localhost:27017
      
      # 替换为 SQLite 配置（本地开发）
      # db_path: ./data/metadata.db
```

### 3.6 动态切换示例

```python
import yaml
from pathlib import Path

class MemorySystem:
    """记忆系统主类 - 支持动态切换存储"""
    
    def __init__(self, config_path: str = "config.yaml"):
        with open(config_path) as f:
            self.config = yaml.safe_load(f)
        
        # 初始化存储适配器
        self.vector_store = StorageFactory.create_vector_store(
            self.config['storage']['vector']['type'],
            self.config['storage']['vector']['config']
        )
        
        self.graph_store = StorageFactory.create_graph_store(
            self.config['storage']['graph']['type'],
            self.config['storage']['graph']['config']
        )
        
        self.metadata_store = StorageFactory.create_metadata_store(
            self.config['storage']['metadata']['type'],
            self.config['storage']['metadata']['config']
        )
    
    async def switch_vector_store(self, new_type: str, new_config: Dict[str, Any]):
        """动态切换向量存储"""
        # 创建新实例
        new_store = StorageFactory.create_vector_store(new_type, new_config)
        
        # 健康检查
        if not await new_store.health_check():
            raise ConnectionError(f"Cannot connect to {new_type}")
        
        # 可选：数据迁移
        # await self._migrate_vector_data(self.vector_store, new_store)
        
        self.vector_store = new_store
        self.config['storage']['vector']['type'] = new_type
        logger.info(f"Switched vector store to {new_type}")
    
    async def _migrate_vector_data(
        self,
        source: VectorStoreInterface,
        target: VectorStoreInterface
    ):
        """数据迁移（可选）"""
        # 实现数据从源到目标的迁移
        pass
```

### 3.7 适配器对比

| 维度 | 腾讯云VectorDB | Milvus | Pinecone | Weaviate | Qdrant |
|------|----------------|--------|----------|----------|--------|
| 部署方式 | 云托管 | 自托管/云 | 云托管 | 自托管/云 | 自托管/云 |
| 扩展性 | 自动扩容 | 水平扩展 | 自动 | 水平扩展 | 水平扩展 |
| 混合检索 | 原生支持 | 支持 | 支持 | 原生支持 | 支持 |
| 全文检索 | 支持 | 有限 | 不支持 | 原生支持 | 支持 |
| 多租户 | 支持 | 支持 | 支持 | 原生支持 | 支持 |
| 成本 | 按量付费 | 开源免费 | SaaS订阅 | 开源免费 | 开源免费 |
| **推荐场景** | 生产环境首选 | 大规模私有化 | 快速上手 | 多模态 | 高性能 |

| 维度 | Neo4j | FalkorDB | TigerGraph | NetworkX |
|------|-------|----------|------------|----------|
| 部署方式 | 自托管/云 | 自托管 | 自托管/云 | 嵌入式 |
| 性能 | 高 | 高 | 极高 | 低 |
| 查询语言 | Cypher | OpenCypher | GSQL | Python API |
| 图算法 | 丰富 | 基础 | 丰富 | 丰富 |
| 适用规模 | 中大规模 | 小中型 | 大规模 | 小型/原型 |
| **推荐场景** | 生产环境首选 | 轻量级部署 | 大规模图计算 | 本地开发 |



## 4. 核心架构设计
   133|
   134|### 3.1 系统架构图
   135|
   136|```plantuml
   137|@startuml Agent-Memory-Middleware
   138|!theme cerulean
   139|title Agent 记忆中台核心架构
   140|
   141|package "接入层" as接入 {
   142|  [Web 控制台] as WebUI
   143|  [REST API] as API
   144|  [SDK (Python/Go)] as SDK
   145|}
   146|
   147|package "API 网关" as网关 {
   148|  [Kong/Nginx] as GW
   149|  [JWT 认证] as Auth
   150|  [限流熔断] as RateLimit
   151|}
   152|
   153|package "核心服务层" as核心 {
   154|  [记忆提取服务
   155|(Add-only Engine)] as Extractor
   156|  [混合检索服务
   157|(Hybrid Retrieval)] as Retriever
   158|  [实体链接服务
   159|(Entity Linking)] as EntityLink
   160|  [时间推理服务
   161|(Temporal Reasoning)] as Temporal
   162|  [衰减清理服务
   163|(Memory Decay)] as Decay
   164|}
   165|
   166|package "数据处理层" as处理 {
   167|  [Embedding 服务
   168|(腾讯云/本地)] as Embed
   169|  [NER 实体提取
   170|(spaCy/LLM)] as NER
   171|  [BM25 索引
   172|(Elasticsearch)] as BM25
   173|}
   174|
   175|package "存储层" as存储 {
   176|  database "腾讯云 VectorDB
   177|(HNSW 索引)" as VDB
   178|  database "Redis Cluster
   179|(热数据缓存)" as Redis
   180|  database "MySQL/PostgreSQL
   181|(元数据)" as MySQL
   182|  database "Neo4j/FalkorDB
   183|(知识图谱)" as Graph
   184|  database "Elasticsearch
   185|(全文检索)" as ES
   186|}
   187|
   188|package "消息队列" as队列 {
   189|  queue "Kafka/RabbitMQ
   190|(异步写入)" as MQ
   191|}
   192|
   193|WebUI --> GW
   194|API --> GW
   195|SDK --> GW
   196|GW --> Auth
   197|Auth --> RateLimit
   198|
   199|RateLimit --> Extractor
   200|RateLimit --> Retriever
   201|
   202|Extractor --> Embed
   203|Extractor --> NER
   204|Extractor --> MQ
   205|
   206|Retriever --> Embed
   207|Retriever --> VDB
   208|Retriever --> BM25
   209|Retriever --> Graph
   210|Retriever --> Redis
   211|
   212|MQ --> VDB : 异步写入
   213|MQ --> Graph : 异步更新
   214|MQ --> MySQL : 异步记录
   215|
   216|Decay --> VDB
   217|Decay --> Graph
   218|
   219|@enduml
   220|```
   221|
   222|### 3.2 服务职责
   223|
   224|| 服务 | 职责 | 核心算法 |
   225||------|------|----------|
   226|| 记忆提取服务 | 从对话中提取结构化记忆 | ADD-only 单次提取 + 实体识别 |
   227|| 混合检索服务 | 多信号融合检索 | 语义 + BM25 + 实体 boost |
   228|| 实体链接服务 | 跨记忆实体关联 | 实体嵌入 + 图遍历 |
   229|| 时间推理服务 | 处理时间引用 | 相对时间 → 绝对日期锚定 |
   230|| 衰减清理服务 | 记忆生命周期管理 | 指数衰减 + 重要性加权 |
   231|
   232|---
   233|
   234|## 5. 记忆提取引擎
   235|
   236|### 4.1 提取流程（借鉴 Mem0 v3）
   237|
   238|```plantuml
   239|@startuml Memory-Extraction-Flow
   240|title 记忆提取流程（ADD-only）
   241|
   242|start
   243|
   244|:接收对话数据;
   245|
   246|partition "阶段1: 预处理" {
   247|  :解析消息结构;
   248|  :提取最近20条上下文;
   249|  :识别观察日期;
   250|  :获取已有记忆摘要;
   251|}
   252|
   253|partition "阶段2: LLM 提取（单次调用）" {
   254|  :构建提取 Prompt;
   255|  note right
   256|    系统提示 + 新消息 + 历史摘要
   257|    + 已提取记忆（去重参考）
   258|    + 已有记忆（链接参考）
   259|  end note
   260|  :调用 LLM 提取;
   261|  :解析 JSON 输出;
   262|}
   263|
   264|partition "阶段3: 后处理" {
   265|  if (提取成功?) then (是)
   266|    :解析记忆列表;
   267|    :提取实体和关系;
   268|    :计算实体嵌入;
   269|    :建立实体链接;
   270|    :关联已有记忆;
   271|  else (否)
   272|    :记录错误;
   273|    :降级处理;
   274|  endif
   275|}
   276|
   277|partition "阶段4: 写入存储" {
   278|  fork
   279|    :写入 VectorDB;
   280|  fork again
   281|    :更新知识图谱;
   282|  fork again
   283|    :更新 BM25 索引;
   284|  fork again
   285|    :记录元数据;
   286|  end fork
   287|}
   288|
   289|stop
   290|
   291|@enduml
   292|```
   293|
   294|### 4.2 提取 Prompt 设计
   295|
   296|```python
   297|EXTRACTION_PROMPT = """
   298|# 角色
   299|你是记忆提取器 — 一个精确的、基于证据的处理器，负责从对话中提取丰富的、上下文相关的记忆。
   300|
   301|# 输入
   302|- 新消息: 当前对话轮次（用户 + 助手）
   303|- 摘要: 用户历史档案摘要
   304|- 已提取记忆: 本次会话已提取的记忆（去重参考）
   305|- 已有记忆: 系统中已存在的相关记忆（链接参考）
   306|- 观察日期: 对话发生的真实日期
   307|
   308|# 提取规则
   309|1. 从用户消息提取: 个人事实、偏好、计划、关系、观点
   310|2. 从助手消息提取: 给出的建议、创建的计划、提供的解决方案
   311|3. 时间处理: 所有相对时间引用必须锚定到观察日期
   312|4. 去重: 如果与已有记忆语义重复，跳过
   313|5. 链接: 如果与已有记忆相关，在 linked_memory_ids 中引用
   314|
   315|# 输出格式
   316|{
   317|  "memory": [
   318|    {
   319|      "text": "记忆内容",
   320|      "entities": [{"name": "实体名", "type": "实体类型"}],
   321|      "linked_memory_ids": ["已有记忆ID"],
   322|      "importance": 0.8,
   323|      "created_at": "2026-05-27"
   324|    }
   325|  ]
   326|}
   327|"""
   328|```
   329|
   330|### 4.3 实体提取算法
   331|
   332|Mem0 使用 spaCy 进行实体提取，我们的增强版：
   333|
   334|```python
   335|# 实体类型定义
   336|ENTITY_TYPES = {
   337|    "PERSON": "人物",
   338|    "ORG": "组织",
   339|    "PRODUCT": "产品",
   340|    "LOCATION": "地点",
   341|    "DATE": "日期",
   342|    "PREFERENCE": "偏好",
   343|    "SKILL": "技能",
   344|    "PROJECT": "项目",
   345|}
   346|
   347|# 实体提取流程
   348|def extract_entities(text: str, llm_client) -> List[Entity]:
   349|    """
   350|    混合实体提取: spaCy NER + LLM 增强
   351|    """
   352|    # 1. spaCy 快速提取
   353|    doc = spacy_nlp(text)
   354|    spacy_entities = [(ent.text, ent.label_) for ent in doc.ents]
   355|    
   356|    # 2. LLM 增强提取（业务实体）
   357|    llm_prompt = f"""
   358|    从以下文本中提取实体，包括人物、组织、产品、偏好等：
   359|    {text}
   360|    
   361|    返回JSON格式: [{{"name": "...", "type": "..."}}]
   362|    """
   363|    llm_entities = llm_client.extract(llm_prompt)
   364|    
   365|    # 3. 合并去重
   366|    return merge_and_deduplicate(spacy_entities, llm_entities)
   367|```
   368|
   369|### 4.4 时间感知处理
   370|
   371|```python
   372|def resolve_temporal_references(text: str, observation_date: datetime) -> str:
   373|    """
   374|    将相对时间引用锚定到绝对日期
   375|    """
   376|    # 示例:
   377|    # "yesterday" → observation_date - 1 day
   378|    # "last week" → observation_date - 7 days
   379|    # "next month" → observation_date + 1 month
   380|    
   381|    resolved_text = text
   382|    for pattern, delta in TEMPORAL_PATTERNS.items():
   383|        if pattern in resolved_text:
   384|            absolute_date = observation_date + delta
   385|            resolved_text = resolved_text.replace(
   386|                pattern, absolute_date.strftime("%Y-%m-%d")
   387|            )
   388|    return resolved_text
   389|```
   390|
   391|---
   392|
   393|## 6. 混合检索引擎
   394|
   395|### 5.1 检索架构
   396|
   397|```plantuml
   398|@startuml Hybrid-Retrieval-Architecture
   399|title 混合检索架构
   400|
   401|start
   402|
   403|:接收查询请求;
   404|
   405|partition "查询预处理" {
   406|  :嵌入查询文本;
   407|  :提取查询实体;
   408|  :词形还原 (lemmatize);
   409|}
   410|
   411|partition "并行检索（4路）" {
   412|  fork
   413|    :向量语义检索
   414|(VectorDB HNSW);
   415|    note right: top_k * 4
   416|  fork again
   417|    :BM25 关键词检索
   418|(Elasticsearch);
   419|    note right: top_k * 4
   420|  fork again
   421|    :实体图遍历检索
   422|(Neo4j/FalkorDB);
   423|    note right: 关联记忆
   424|  fork again
   425|    :元数据过滤;
   426|    note right: 类型/标签/时间
   427|  end fork
   428|}
   429|
   430|partition "融合评分" {
   431|  :BM25 分数归一化
   432|(sigmoid);
   433|  :计算实体 boost;
   434|  :RRF 混合排序;
   435|  
   436|  note right
   437|    combined = (semantic + bm25 + entity) / max_possible
   438|    max_possible 根据可用信号动态计算
   439|  end note
   440|}
   441|
   442|partition "后处理" {
   443|  :阈值过滤;
   444|  :Reranker 重排序;
   445|  :格式化输出;
   446|}
   447|
   448|stop
   449|
   450|@enduml
   451|```
   452|
   453|### 5.2 评分算法详解
   454|
   455|```python
   456|def score_and_rank(
   457|    semantic_results: List[Dict],
   458|    bm25_scores: Dict[str, float],
   459|    entity_boosts: Dict[str, float],
   460|    threshold: float,
   461|    top_k: int,
   462|) -> List[Dict]:
   463|    """
   464|    三信号融合评分
   465|    """
   466|    # 动态计算最大可能分数
   467|    max_possible = 1.0  # 基础: 语义分数
   468|    if bm25_scores:
   469|        max_possible += 1.0  # + BM25
   470|    if entity_boosts:
   471|        max_possible += 0.5  # + 实体 boost
   472|    
   473|    scored = []
   474|    for result in semantic_results:
   475|        # 语义分数（必须超过阈值）
   476|        semantic_score = result["score"]
   477|        if semantic_score < threshold:
   478|            continue
   479|        
   480|        # BM25 分数（已归一化到 [0, 1]）
   481|        bm25_score = bm25_scores.get(result["id"], 0.0)
   482|        
   483|        # 实体 boost（最大 0.5）
   484|        entity_boost = entity_boosts.get(result["id"], 0.0)
   485|        
   486|        # 加权融合
   487|        combined = (semantic_score + bm25_score + entity_boost) / max_possible
   488|        scored.append({
   489|            "id": result["id"],
   490|            "score": combined,
   491|            "semantic": semantic_score,
   492|            "bm25": bm25_score,
   493|            "entity_boost": entity_boost,
   494|        })
   495|    
   496|    # 按综合分数排序
   497|    scored.sort(key=lambda x: x["score"], reverse=True)
   498|    return scored[:top_k]
   499|
   500|
   501|def normalize_bm25(raw_score: float, query_length: int) -> float:
   502|    """
   503|    BM25 分数归一化（查询长度自适应 sigmoid）
   504|    """
   505|    # 根据查询长度选择 sigmoid 参数
   506|    if query_length <= 3:
   507|        midpoint, steepness = 5.0, 0.7
   508|    elif query_length <= 6:
   509|        midpoint, steepness = 7.0, 0.6
   510|    else:
   511|        midpoint, steepness = 10.0, 0.5
   512|    
   513|    # sigmoid 归一化
   514|    return 1.0 / (1.0 + math.exp(-steepness * (raw_score - midpoint)))
   515|```
   516|
   517|### 5.3 实体 Boost 计算
   518|
   519|```python
   520|def compute_entity_boosts(
   521|    query_entities: List[Tuple[str, str]],  # (type, text)
   522|    entity_store,  # 向量化的实体库
   523|    embedding_model,
   524|    filters: Dict,
   525|) -> Dict[str, float]:
   526|    """
   527|    实体关联加权：将查询实体与记忆中的实体匹配，给予关联记忆加分
   528|    """
   529|    memory_boosts = {}
   530|    ENTITY_BOOST_WEIGHT = 0.5  # 最大 boost 权重
   531|    
   532|    for entity_type, entity_text in query_entities[:8]:  # 最多 8 个实体
   533|        # 嵌入实体文本
   534|        entity_embedding = embedding_model.embed(entity_text, "search")
   535|        
   536|        # 在实体库中搜索
   537|        matches = entity_store.search(
   538|            query=entity_text,
   539|            vectors=entity_embedding,
   540|            top_k=500,
   541|            filters=filters,
   542|        )
   543|        
   544|        for match in matches:
   545|            similarity = match.score
   546|            if similarity < 0.5:  # 相似度阈值
   547|                continue
   548|            
   549|            # 获取关联的记忆 ID
   550|            linked_memory_ids = match.payload.get("linked_memory_ids", [])
   551|            
   552|            # 衰减计算：关联记忆越多，单个 boost 越小
   553|            num_linked = max(len(linked_memory_ids), 1)
   554|            weight = 1.0 / (1.0 + 0.001 * ((num_linked - 1) ** 2))
   555|            
   556|            boost = similarity * ENTITY_BOOST_WEIGHT * weight
   557|            
   558|            for memory_id in linked_memory_ids:
   559|                memory_boosts[memory_id] = max(
   560|                    memory_boosts.get(memory_id, 0.0), boost
   561|                )
   562|    
   563|    return memory_boosts
   564|```
   565|
   566|---
   567|
   568|## 7. 知识图谱集成
   569|
   570|### 6.1 图谱架构（借鉴 Graphiti）
   571|
   572|```plantuml
   573|@startuml Knowledge-Graph-Architecture
   574|title 知识图谱架构
   575|
   576|database "Neo4j / FalkorDB" as Graph {
   577|  rectangle "实体节点" as Entity {
   578|    (人物) as P
   579|    (组织) as O
   580|    (产品) as Pr
   581|    (概念) as C
   582|  }
   583|  
   584|  rectangle "关系边" as Relation {
   585|    (工作于) as R1
   586|    (偏好) as R2
   587|    (创建) as R3
   588|  }
   589|  
   590|  rectangle "时间窗口" as Temporal {
   591|    (valid_from) as VF
   592|    (valid_to) as VT
   593|  }
   594|}
   595|
   596|rectangle "溯源" as Provenance {
   597|  (原始对话) as Raw
   598|  (提取时间) as ExtractTime
   599|}
   600|
   601|Entity --> Relation
   602|Relation --> Temporal
   603|Relation --> Provenance
   604|
   605|@enduml
   606|```
   607|
   608|### 6.2 图谱节点定义
   609|
   610|```cypher
   611|// 实体节点
   612|CREATE (e:Entity {
   613|  id: "uuid",
   614|  name: "张三",
   615|  type: "PERSON",
   616|  summary: "腾讯云高级工程师，专注于分布式系统",
   617|  created_at: datetime(),
   618|  updated_at: datetime(),
   619|  access_count: 0,
   620|  importance: 0.8
   621|})
   622|
   623|// 关系边（带时间窗口）
   624|CREATE (e1)-[:WORKS_AT {
   625|  valid_from: date("2024-01-01"),
   626|  valid_to: null,  // null 表示当前有效
   627|  confidence: 0.95,
   628|  source_episode_id: "episode-uuid"
   629|}]->(e2)
   630|
   631|// Episode 节点（溯源）
   632|CREATE (ep:Episode {
   633|  id: "uuid",
   634|  content: "原始对话内容...",
   635|  source: "chat",
   636|  created_at: datetime()
   637|})
   638|```
   639|
   640|### 6.3 图遍历查询
   641|
   642|```cypher
   643|// 查询某人的所有当前有效关系
   644|MATCH (p:Person {name: "张三"})-[r]->(target)
   645|WHERE r.valid_to IS NULL
   646|RETURN target, type(r) as relationship, r.valid_from as since
   647|
   648|// 查询与某实体相关的所有记忆
   649|MATCH (e:Entity {name: "项目A"})<-[:MENTIONS]-(m:Memory)
   650|WHERE m.created_at > datetime() - duration({days: 30})
   651|RETURN m.text, m.importance
   652|ORDER BY m.importance DESC
   653|```
   654|
   655|---
   656|
   657|## 8. 高并发与极端场景处理
   658|
   659|### 7.1 并发控制架构
   660|
   661|```plantuml
   662|@startuml High-Concurrency-Architecture
   663|title 高并发处理架构
   664|
   665|rectangle "接入层" {
   666|  [负载均衡
   667|(Nginx/HAProxy)] as LB
   668|  [限流器
   669|(Token Bucket)] as Limiter
   670|}
   671|
   672|rectangle "服务层" {
   673|  [记忆服务 x3] as Svc1
   674|  [检索服务 x3] as Svc2
   675|  [异步 Worker x5] as Worker
   676|}
   677|
   678|rectangle "中间件" {
   679|  queue "Kafka
   680|(写入队列)" as Kafka
   681|  queue "Redis
   682|(分布式锁)" as Redis
   683|  queue "Sentinel
   684|(熔断降级)" as Sentinel
   685|}
   686|
   687|rectangle "存储层" {
   688|  database "VectorDB
   689|(分片集群)" as VDB
   690|  database "Redis Cluster
   691|(缓存)" as RedisCluster
   692|  database "MySQL
   693|(主从)" as MySQL
   694|}
   695|
   696|LB --> Limiter
   697|Limiter --> Svc1
   698|Limiter --> Svc2
   699|Svc1 --> Kafka
   700|Svc2 --> RedisCluster
   701|Svc2 --> VDB
   702|Worker --> Kafka
   703|Worker --> VDB
   704|Worker --> MySQL
   705|Svc1 --> Sentinel
   706|Svc2 --> Sentinel
   707|
   708|@enduml
   709|```
   710|
   711|### 7.2 分布式锁（写入并发控制）
   712|
   713|```python
   714|import redis
   715|import uuid
   716|import time
   717|
   718|class MemoryWriteLock:
   719|    """
   720|    基于 Redis 的分布式锁，防止并发写入冲突
   721|    """
   722|    def __init__(self, redis_client: redis.Redis):
   723|        self.redis = redis_client
   724|        self.lock_prefix = "memory:lock:"
   725|    
   726|    def acquire(self, memory_key: str, timeout: int = 10) -> str:
   727|        """
   728|        获取锁
   729|        - memory_key: 细粒度锁粒度（如 user_id + entity_name）
   730|        - timeout: 锁超时时间
   731|        """
   732|        lock_key = f"{self.lock_prefix}{memory_key}"
   733|        lock_value = str(uuid.uuid4())
   734|        
   735|        # 原子性加锁（SET NX PX）
   736|        acquired = self.redis.set(
   737|            lock_key, lock_value,
   738|            nx=True,  # 不存在才设置
   739|            px=timeout * 1000  # 毫秒过期
   740|        )
   741|        
   742|        if acquired:
   743|            return lock_value
   744|        return None
   745|    
   746|    def release(self, memory_key: str, lock_value: str) -> bool:
   747|        """
   748|        释放锁（Lua 脚本保证原子性）
   749|        """
   750|        lua_script = """
   751|        if redis.call("get", KEYS[1]) == ARGV[1] then
   752|            return redis.call("del", KEYS[1])
   753|        else
   754|            return 0
   755|        end
   756|        """
   757|        return self.redis.eval(
   758|            lua_script, 1,
   759|            f"{self.lock_prefix}{memory_key}",
   760|            lock_value
   761|        )
   762|
   763|
   764|# 使用示例
   765|def write_memory_with_lock(redis_client, memory_data):
   766|    lock = MemoryWriteLock(redis_client)
   767|    lock_value = lock.acquire(f"{memory_data['user_id']}:{memory_data['entity']}")
   768|    
   769|    if lock_value:
   770|        try:
   771|            # 执行写入操作
   772|            vector_db.upsert(memory_data)
   773|            graph_db.add_entity(memory_data)
   774|        finally:
   775|            lock.release(f"{memory_data['user_id']}:{memory_data['entity']}", lock_value)
   776|    else:
   777|        # 获取锁失败，放入重试队列
   778|        kafka_producer.send("memory_retry_queue", memory_data)
   779|```
   780|
   781|### 7.3 异步写入（Kafka 消息队列）
   782|
   783|```python
   784|from kafka import KafkaProducer, KafkaConsumer
   785|import json
   786|
   787|class AsyncMemoryWriter:
   788|    """
   789|    异步写入：通过 Kafka 解耦写入操作
   790|    """
   791|    def __init__(self, bootstrap_servers: str):
   792|        self.producer = KafkaProducer(
   793|            bootstrap_servers=bootstrap_servers,
   794|            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
   795|            acks='all',  # 确保消息持久化
   796|            retries=3,
   797|            max_in_flight_requests_per_connection=1  # 保证顺序
   798|        )
   799|    
   800|    def async_write(self, memory_data: dict):
   801|        """
   802|        异步写入记忆
   803|        """
   804|        # 写入主队列
   805|        self.producer.send('memory_write_topic', value=memory_data)
   806|        
   807|        # 如果写入失败，自动重试
   808|        self.producer.flush()
   809|    
   810|    def batch_write(self, memories: list):
   811|        """
   812|        批量写入（减少网络开销）
   813|        """
   814|        for memory in memories:
   815|            self.producer.send('memory_write_topic', value=memory)
   816|        self.producer.flush()
   817|
   818|
   819|# 消费者（Worker）
   820|class MemoryWriteWorker:
   821|    """
   822|    异步写入 Worker
   823|    """
   824|    def __init__(self, bootstrap_servers: str):
   825|        self.consumer = KafkaConsumer(
   826|            'memory_write_topic',
   827|            bootstrap_servers=bootstrap_servers,
   828|            group_id='memory_writer_group',
   829|            auto_offset_reset='earliest',
   830|            enable_auto_commit=False,
   831|            max_poll_records=100  # 批量处理
   832|        )
   833|    
   834|    def run(self):
   835|        """
   836|        消费消息并写入存储
   837|        """
   838|        while True:
   839|            messages = self.consumer.poll(timeout_ms=1000)
   840|            
   841|            for topic_partition, records in messages.items():
   842|                for record in records:
   843|                    try:
   844|                        # 写入 VectorDB
   845|                        vector_db.upsert(record.value)
   846|                        
   847|                        # 写入知识图谱
   848|                        graph_db.add_entity(record.value)
   849|                        
   850|                        # 写入元数据
   851|                        mysql_db.insert_metadata(record.value)
   852|                    except Exception as e:
   853|                        # 失败处理：死信队列
   854|                        self.send_to_dead_letter(record, str(e))
   855|                
   856|                # 批量提交 offset
   857|                self.consumer.commit()
   858|    
   859|    def send_to_dead_letter(self, record, error):
   860|        """
   861|        失败消息进入死信队列
   862|        """
   863|        self.producer.send('memory_dead_letter', value={
   864|            'original': record.value,
   865|            'error': error,
   866|            'timestamp': time.time()
   867|        })
   868|```
   869|
   870|### 7.4 多级缓存策略
   871|
   872|```python
   873|class MultiLevelCache:
   874|    """
   875|    三级缓存：L1 本地内存 → L2 Redis → L3 VectorDB
   876|    """
   877|    def __init__(self, redis_client, vector_db):
   878|        self.l1_cache = {}  # 本地内存（带 TTL）
   879|        self.l2_cache = redis_client  # Redis
   880|        self.l3_storage = vector_db  # VectorDB
   881|        
   882|        # 缓存配置
   883|        self.l1_ttl = 60  # 1分钟
   884|        self.l2_ttl = 300  # 5分钟
   885|    
   886|    async def get(self, key: str, user_id: str) -> dict:
   887|        """
   888|        三级缓存查询
   889|        """
   890|        # L1: 本地内存
   891|        if key in self.l1_cache:
   892|            item = self.l1_cache[key]
   893|            if time.time() - item['time'] < self.l1_ttl:
   894|                return item['data']
   895|            else:
   896|                del self.l1_cache[key]
   897|        
   898|        # L2: Redis
   899|        cached = await self.l2_cache.get(f"memory:{user_id}:{key}")
   900|        if cached:
   901|            data = json.loads(cached)
   902|            # 回填 L1
   903|            self.l1_cache[key] = {'data': data, 'time': time.time()}
   904|            return data
   905|        
   906|        # L3: VectorDB
   907|        result = await self.l3_storage.search(
   908|            query_id=key,
   909|            filters={"user_id": user_id}
   910|        )
   911|        if result:
   912|            # 回填 L1 + L2
   913|            self.l1_cache[key] = {'data': result, 'time': time.time()}
   914|            await self.l2_cache.setex(
   915|                f"memory:{user_id}:{key}",
   916|                self.l2_ttl,
   917|                json.dumps(result)
   918|            )
   919|        
   920|        return result
   921|```
   922|
   923|### 7.5 降级与熔断
   924|
   925|```python
   926|from circuitbreaker import circuit
   927|
   928|class MemoryService:
   929|    """
   930|    带熔断降级的记忆服务
   931|    """
   932|    
   933|    @circuit(
   934|        failure_threshold=5,      # 失败次数阈值
   935|        recovery_timeout=30,      # 恢复超时（秒）
   936|        expected_exception=Exception
   937|    )
   938|    async def search_with_fallback(self, query: str, filters: dict):
   939|        """
   940|        检索带降级
   941|        """
   942|        try:
   943|            # 尝试完整检索（混合检索）
   944|            results = await self.hybrid_search(query, filters)
   945|            return results
   946|        except Exception as e:
   947|            # 降级策略1: 仅向量检索
   948|            try:
   949|                results = await self.vector_search_only(query, filters)
   950|                return results
   951|            except Exception:
   952|                # 降级策略2: 仅元数据检索
   953|                results = await self.metadata_search_only(filters)
   954|                return results
   955|    
   956|    async def write_with_retry(self, memory_data: dict):
   957|        """
   958|        写入带重试
   959|        """
   960|        max_retries = 3
   961|        for attempt in range(max_retries):
   962|            try:
   963|                await self.write(memory_data)
   964|                return True
   965|            except Exception as e:
   966|                if attempt < max_retries - 1:
   967|                    # 指数退避
   968|                    time.sleep(2 ** attempt)
   969|                else:
   970|                    # 最终失败：放入延迟队列
   971|                    await self.send_to_retry_queue(memory_data)
   972|                    return False
   973|```
   974|
   975|### 7.6 数据一致性保证
   976|
   977|| 场景 | 策略 | 实现方式 |
   978||------|------|----------|
   979|| 写入一致性 | 最终一致性 | Kafka + 幂等写入 |
   980|| 读写一致性 | 读时合并 | 写入版本号，读时校验 |
   981|| 图谱一致性 | 事务 + 定期对账 | Neo4j 事务 + 定时任务 |
   982|| 缓存一致性 | 写时失效 | 写入后删除缓存 |
   983|
   984|```python
   985|class ConsistencyManager:
   986|    """
   987|    数据一致性管理
   988|    """
   989|    
   990|    async def write_with_version(self, memory_data: dict):
   991|        """
   992|        带版本号的写入（乐观锁）
   993|        """
   994|        # 获取当前版本
   995|        current_version = await self.get_version(memory_data['id'])
   996|        
   997|        # 写入（条件更新）
   998|        success = await self.vector_db.upsert(
   999|            memory_data,
  1000|            condition={"version": current_version}
  1001|        )
  1002|        
  1003|        if success:
  1004|            # 更新版本号
  1005|            await self.update_version(memory_data['id'], current_version + 1)
  1006|            # 失效缓存
  1007|            await self.invalidate_cache(memory_data['id'])
  1008|        else:
  1009|            # 版本冲突，重试
  1010|            raise VersionConflictError()
  1011|    
  1012|    async def reconcile(self):
  1013|        """
  1014|        定期对账任务（补偿机制）
  1015|        """
  1016|        # 比较 VectorDB 和 GraphDB 的数据
  1017|        vector_memories = await self.vector_db.scan()
  1018|        graph_entities = await self.graph_db.scan()
  1019|        
  1020|        # 找出差异
  1021|        diff = self.compute_diff(vector_memories, graph_entities)
  1022|        
  1023|        # 补偿写入
  1024|        for item in diff['missing_in_graph']:
  1025|            await self.graph_db.add_entity(item)
  1026|        
  1027|        for item in diff['missing_in_vector']:
  1028|            await self.vector_db.upsert(item)
  1029|```
  1030|
  1031|---
  1032|
  1033|## 9. API 接口设计
  1034|
  1035|### 8.1 接口总览
  1036|
  1037|| 模块 | 接口 | 方法 | 说明 |
  1038||------|------|------|------|
  1039|| **记忆管理** | /api/v1/memories | POST | 添加记忆 |
  1040|| | /api/v1/memories/{id} | GET | 获取记忆 |
  1041|| | /api/v1/memories/{id} | PUT | 更新记忆 |
  1042|| | /api/v1/memories/{id} | DELETE | 删除记忆 |
  1043|| | /api/v1/memories/search | POST | 搜索记忆 |
  1044|| **批量操作** | /api/v1/memories/batch | POST | 批量添加 |
  1045|| | /api/v1/memories/export | GET | 导出记忆 |
  1046|| **实体管理** | /api/v1/entities | GET | 实体列表 |
  1047|| | /api/v1/entities/{id}/memories | GET | 实体关联记忆 |
  1048|| **图谱查询** | /api/v1/graph/traverse | POST | 图遍历查询 |
  1049|| | /api/v1/graph/relationships | GET | 关系查询 |
  1050|| **监控指标** | /api/v1/metrics/stats | GET | 统计信息 |
  1051|| | /api/v1/metrics/performance | GET | 性能指标 |
  1052|| **管理后台** | /api/v1/admin/config | GET/PUT | 配置管理 |
  1053|| | /api/v1/admin/jobs | GET | 后台任务 |
  1054|
  1055|### 8.2 核心接口示例
  1056|
  1057|```yaml
  1058|# 添加记忆
  1059|POST /api/v1/memories
  1060|Request:
  1061|{
  1062|  "content": "用户偏好使用Python，不喜欢JavaScript",
  1063|  "user_id": "user_123",
  1064|  "agent_id": "agent_456",
  1065|  "metadata": {
  1066|    "source": "chat",
  1067|    "importance": 0.8,
  1068|    "tags": ["偏好", "编程语言"]
  1069|  },
  1070|  "extract_entities": true,
  1071|  "link_existing": true
  1072|}
  1073|
  1074|Response:
  1075|{
  1076|  "id": "mem_789",
  1077|  "text": "用户偏好使用Python，不喜欢JavaScript",
  1078|  "entities": [
  1079|    {"name": "Python", "type": "PRODUCT", "id": "ent_001"},
  1080|    {"name": "JavaScript", "type": "PRODUCT", "id": "ent_002"}
  1081|  ],
  1082|  "linked_memories": ["mem_123", "mem_456"],
  1083|  "score": 0.85,
  1084|  "created_at": "2026-05-27T10:00:00Z"
  1085|}
  1086|
  1087|# 搜索记忆（混合检索）
  1088|POST /api/v1/memories/search
  1089|Request:
  1090|{
  1091|  "query": "用户喜欢什么编程语言",
  1092|  "user_id": "user_123",
  1093|  "top_k": 10,
  1094|  "threshold": 0.3,
  1095|  "filters": {
  1096|    "tags": {"$in": ["偏好", "技术"]},
  1097|    "created_at": {"$gte": "2026-01-01"}
  1098|  },
  1099|  "enable_hybrid": true,  // 启用混合检索
  1100|  "enable_entity_boost": true,
  1101|  "rerank": true
  1102|}
  1103|
  1104|Response:
  1105|{
  1106|  "results": [
  1107|    {
  1108|      "id": "mem_789",
  1109|      "text": "用户偏好使用Python，不喜欢JavaScript",
  1110|      "score": 0.92,
  1111|      "breakdown": {
  1112|        "semantic": 0.85,
  1113|        "bm25": 0.78,
  1114|        "entity_boost": 0.45
  1115|      },
  1116|      "entities": ["Python", "JavaScript"],
  1117|      "created_at": "2026-05-27T10:00:00Z"
  1118|    }
  1119|  ],
  1120|  "total": 1,
  1121|  "query_time_ms": 125
  1122|}
  1123|```
  1124|
  1125|### 8.3 认证与限流
  1126|
  1127|```yaml
  1128|# API Key 认证
  1129|Authorization: Bearer ***
  1130|
  1131|# 限流配置
  1132|Rate-Limits:
  1133|  - 写入: 100 QPS per user
  1134|  - 读取: 1000 QPS per user
  1135|  - 批量: 10 QPS per user
  1136|
  1137|# 响应头
  1138|X-RateLimit-Limit: 100
  1139|X-RateLimit-Remaining: 95
  1140|X-RateLimit-Reset: 1622505600
  1141|```
  1142|
  1143|---
  1144|
  1145|## 10. 前端监控界面设计
  1146|
  1147|### 9.1 Dashboard 布局
  1148|
  1149|```
  1150|┌─────────────────────────────────────────────────────────────┐
  1151|│  Agent 记忆中台监控面板                          [通知] [设置] │
  1152|├─────────────────────────────────────────────────────────────┤
  1153|│                                                             │
  1154|│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────┐ │
  1155|│  │ 总记忆数     │ │ 今日新增     │ │ 检索 QPS    │ │ 命中率│ │
  1156|│  │   1,234,567 │ │   +12,345   │ │   1,234     │ │ 94.2% │ │
  1157|│  │   ↑12.3%    │ │   ↑8.5%     │ │   ↑15.2%    │ │ ↑2.1% │ │
  1158|│  └─────────────┘ └─────────────┘ └─────────────┘ └───────┘ │
  1159|│                                                             │
  1160|│  ┌──────────────────────────┐ ┌──────────────────────────┐ │
  1161|│  │                          │ │                          │ │
  1162|│  │    写入/读取趋势图         │ │    记忆类型分布           │ │
  1163|│  │    (7天折线图)             │ │    (饼图)                │ │
  1164|│  │                          │ │                          │ │
  1165|│  └──────────────────────────┘ └──────────────────────────┘ │
  1166|│                                                             │
  1167|│  ┌──────────────────────────┐ ┌──────────────────────────┐ │
  1168|│  │                          │ │                          │ │
  1169|│  │    检索延迟分布            │ │    缓存命中率            │ │
  1170|│  │    (P50/P95/P99)          │ │    (实时曲线)            │ │
  1171|│  │                          │ │                          │ │
  1172|│  └──────────────────────────┘ └──────────────────────────┘ │
  1173|│                                                             │
  1174|│  ┌──────────────────────────────────────────────────────┐ │
  1175|│  │                                                      │ │
  1176|│  │  最近告警                                             │ │
  1177|│  │  ├─ [WARN] 2026-05-27 10:30:00 检索延迟 P99 超标     │ │
  1178|│  │  ├─ [INFO] 2026-05-27 09:15:00 自动衰减任务完成      │ │
  1179|│  │  └─ [ERROR] 2026-05-27 08:00:00 VectorDB 连接超时    │ │
  1180|│  └──────────────────────────────────────────────────────┘ │
  1181|└─────────────────────────────────────────────────────────────┘
  1182|```
  1183|
  1184|### 9.2 监控指标定义
  1185|
  1186|```typescript
  1187|// 核心指标
  1188|interface Metrics {
  1189|  // 存储指标
  1190|  total_memories: number;        // 总记忆数
  1191|  memories_today: number;        // 今日新增
  1192|  memories_by_type: Record<string, number>;  // 按类型分布
  1193|  
  1194|  // 性能指标
  1195|  write_qps: number;             // 写入 QPS
  1196|  read_qps: number;              // 读取 QPS
  1197|  avg_latency_ms: number;        // 平均延迟
  1198|  p99_latency_ms: number;        // P99 延迟
  1199|  
  1200|  // 检索质量
  1201|  hit_rate: number;              // 命中率
  1202|  avg_score: number;             // 平均相关性分数
  1203|  
  1204|  // 缓存
  1205|  l1_hit_rate: number;           // L1 命中率
  1206|  l2_hit_rate: number;           // L2 命中率
  1207|  
  1208|  // 存储
  1209|  vector_db_size_gb: number;     // VectorDB 大小
  1210|  redis_memory_mb: number;       // Redis 内存
  1211|  disk_usage_percent: number;    // 磁盘使用率
  1212|}
  1213|```
  1214|
  1215|### 9.3 前端技术栈
  1216|
  1217|| 组件 | 技术选型 | 理由 |
  1218||------|----------|------|
  1219|| 框架 | React + TypeScript | 生态成熟 |
  1220|| UI 库 | Ant Design Pro | 企业级组件 |
  1221|| 图表 | ECharts | 功能丰富 |
  1222|| 实时数据 | WebSocket | 实时推送 |
  1223|| 状态管理 | Zustand | 轻量高效 |
  1224|
  1225|### 9.4 前端代码示例
  1226|
  1227|```tsx
  1228|// Dashboard 主页面
  1229|import { Card, Statistic, Row, Col } from 'antd';
  1230|import { LineChart, PieChart } from 'echarts-for-react';
  1231|import { useMetrics } from '../hooks/useMetrics';
  1232|
  1233|const Dashboard: React.FC = () => {
  1234|  const { metrics, loading } = useMetrics('/api/v1/metrics/stats');
  1235|  
  1236|  return (
  1237|    <div className="dashboard">
  1238|      {/* 顶部统计卡片 */}
  1239|      <Row gutter={16}>
  1240|        <Col span={6}>
  1241|          <Card>
  1242|            <Statistic
  1243|              title="总记忆数"
  1244|              value={metrics?.total_memories}
  1245|              suffix={
  1246|                <span style={{ color: '#52c41a' }}>
  1247|                  ↑{metrics?.growth_rate}%
  1248|                </span>
  1249|              }
  1250|            />
  1251|          </Card>
  1252|        </Col>
  1253|        <Col span={6}>
  1254|          <Card>
  1255|            <Statistic
  1256|              title="今日新增"
  1257|              value={metrics?.memories_today}
  1258|              valueStyle={{ color: '#1890ff' }}
  1259|            />
  1260|          </Card>
  1261|        </Col>
  1262|        <Col span={6}>
  1263|          <Card>
  1264|            <Statistic
  1265|              title="检索 QPS"
  1266|              value={metrics?.read_qps}
  1267|              suffix={`/ ${metrics?.write_qps} 写入`}
  1268|            />
  1269|          </Card>
  1270|        </Col>
  1271|        <Col span={6}>
  1272|          <Card>
  1273|            <Statistic
  1274|              title="命中率"
  1275|              value={metrics?.hit_rate}
  1276|              precision={1}
  1277|              suffix="%"
  1278|              valueStyle={{ 
  1279|                color: metrics?.hit_rate > 90 ? '#52c41a' : '#ff4d4f' 
  1280|              }}
  1281|            />
  1282|          </Card>
  1283|        </Col>
  1284|      </Row>
  1285|      
  1286|      {/* 趋势图 */}
  1287|      <Row gutter={16} style={{ marginTop: 16 }}>
  1288|        <Col span={12}>
  1289|          <Card title="写入/读取趋势">
  1290|            <LineChart option={metrics?.trend_chart} />
  1291|          </Card>
  1292|        </Col>
  1293|        <Col span={12}>
  1294|          <Card title="记忆类型分布">
  1295|            <PieChart option={metrics?.type_distribution} />
  1296|          </Card>
  1297|        </Col>
  1298|      </Row>
  1299|      
  1300|      {/* 延迟分布 */}
  1301|      <Row gutter={16} style={{ marginTop: 16 }}>
  1302|        <Col span={12}>
  1303|          <Card title="检索延迟分布">
  1304|            <LineChart option={metrics?.latency_chart} />
  1305|          </Card>
  1306|        </Col>
  1307|        <Col span={12}>
  1308|          <Card title="缓存命中率">
  1309|            <LineChart option={metrics?.cache_hit_chart} />
  1310|          </Card>
  1311|        </Col>
  1312|      </Row>
  1313|    </div>
  1314|  );
  1315|};
  1316|
  1317|export default Dashboard;
  1318|```
  1319|
  1320|---
  1321|
  1322|## 11. 腾讯云集成方案
  1323|
  1324|### 10.1 腾讯云向量数据库配置
  1325|
  1326|```python
  1327|# 腾讯云 VectorDB 配置
  1328|VECTORDB_CONFIG = {
  1329|    "instance_id": "vdb-xxx",
  1330|    "region": "ap-guangzhou",
  1331|    "engine": "Milvus",
  1332|    "engine_version": "2.2.0",
  1333|    "dimension": 1536,  # OpenAI ada-002
  1334|    "index_type": "HNSW",
  1335|    "metric_type": "COSINE",
  1336|    "replica_num": 2,
  1337|    "shard_num": 3,
  1338|}
  1339|
  1340|# HNSW 参数调优
  1341|HNSW_PARAMS = {
  1342|    "M": 16,              # 连接数
  1343|    "efConstruction": 256, # 构建时搜索范围
  1344|    "efSearch": 128,       # 查询时搜索范围
  1345|}
  1346|
  1347|# 索引创建
  1348|CREATE_INDEX_SQL = """
  1349|CREATE INDEX idx_memory_embedding 
  1350|ON memory_table (embedding)
  1351|USING HNSW
  1352|WITH (m = 16, ef_construction = 256);
  1353|"""
  1354|```
  1355|
  1356|### 10.2 多租户隔离
  1357|
  1358|```python
  1359|class TenantIsolation:
  1360|    """
  1361|    多租户数据隔离
  1362|    """
  1363|    
  1364|    # 方案1: 前缀隔离（简单）
  1365|    def get_collection_name(self, tenant_id: str, collection: str) -> str:
  1366|        return f"tenant_{tenant_id}_{collection}"
  1367|    
  1368|    # 方案2: 过滤字段（推荐）
  1369|    def get_filters(self, tenant_id: str) -> dict:
  1370|        return {"tenant_id": tenant_id}
  1371|    
  1372|    # 方案3: 独立实例（强隔离）
  1373|    def get_instance(self, tenant_id: str):
  1374|        return self.instances[tenant_id]
  1375|```
  1376|
  1377|---
  1378|
  1379|## 12. 部署架构
  1380|
  1381|### 11.1 Kubernetes 部署
  1382|
  1383|```yaml
  1384|# deployment.yaml
  1385|apiVersion: apps/v1
  1386|kind: Deployment
  1387|metadata:
  1388|  name: memory-service
  1389|spec:
  1390|  replicas: 3
  1391|  selector:
  1392|    matchLabels:
  1393|      app: memory-service
  1394|  template:
  1395|    spec:
  1396|      containers:
  1397|      - name: memory-service
  1398|        image: memory-service:v3.0
  1399|        resources:
  1400|          requests:
  1401|            memory: "512Mi"
  1402|            cpu: "500m"
  1403|          limits:
  1404|            memory: "1Gi"
  1405|            cpu: "1000m"
  1406|        env:
  1407|        - name: VECTORDB_HOST
  1408|          valueFrom:
  1409|            secretKeyRef:
  1410|              name: memory-secrets
  1411|              key: vectordb-host
  1412|        - name: REDIS_URL
  1413|          valueFrom:
  1414|            configMapKeyRef:
  1415|              name: memory-config
  1416|              key: redis-url
  1417|        ports:
  1418|        - containerPort: 8080
  1419|        readinessProbe:
  1420|          httpGet:
  1421|            path: /health
  1422|            port: 8080
  1423|          initialDelaySeconds: 10
  1424|          periodSeconds: 5
  1425|        livenessProbe:
  1426|          httpGet:
  1427|            path: /health
  1428|            port: 8080
  1429|          initialDelaySeconds: 30
  1430|          periodSeconds: 10
  1431|---
  1432|apiVersion: v1
  1433|kind: Service
  1434|metadata:
  1435|  name: memory-service
  1436|spec:
  1437|  type: ClusterIP
  1438|  ports:
  1439|  - port: 80
  1440|    targetPort: 8080
  1441|  selector:
  1442|    app: memory-service
  1443|---
  1444|apiVersion: autoscaling/v2
  1445|kind: HorizontalPodAutoscaler
  1446|metadata:
  1447|  name: memory-service-hpa
  1448|spec:
  1449|  scaleTargetRef:
  1450|    apiVersion: apps/v1
  1451|    kind: Deployment
  1452|    name: memory-service
  1453|  minReplicas: 3
  1454|  maxReplicas: 20
  1455|  metrics:
  1456|  - type: Resource
  1457|    resource:
  1458|      name: cpu
  1459|      target:
  1460|        type: Utilization
  1461|        averageUtilization: 70
  1462|```
  1463|
  1464|### 11.2 监控告警
  1465|
  1466|```yaml
  1467|# prometheus-rules.yaml
  1468|groups:
  1469|- name: memory-service
  1470|  rules:
  1471|  - alert: HighLatency
  1472|    expr: histogram_quantile(0.99, rate(memory_search_duration_seconds_bucket[5m])) > 0.2
  1473|    for: 5m
  1474|    labels:
  1475|      severity: warning
  1476|    annotations:
  1477|      summary: "检索延迟 P99 超标"
  1478|      
  1479|  - alert: LowHitRate
  1480|    expr: memory_cache_hit_rate < 0.9
  1481|    for: 10m
  1482|    labels:
  1483|      severity: warning
  1484|    annotations:
  1485|      summary: "缓存命中率过低"
  1486|      
  1487|  - alert: HighErrorRate
  1488|    expr: rate(memory_errors_total[5m]) / rate(memory_requests_total[5m]) > 0.01
  1489|    for: 5m
  1490|    labels:
  1491|      severity: critical
  1492|    annotations:
  1493|      summary: "错误率超过 1%"
  1494|```
  1495|
  1496|---
  1497|
  1498|## 13. 实施路线图
  1499|
  1500|| 阶段 | 周期 | 目标 | 交付物 |
  1501||------|------|------|--------|
  1502|| **P0: 核心引擎** | 3 周 | 记忆提取 + 混合检索 | 核心 API + VectorDB 集成 |
  1503|| **P1: 知识图谱** | 2 周 | 实体链接 + 图遍历 | 图谱查询 API |
  1504|| **P2: 高并发** | 2 周 | 异步写入 + 缓存 + 熔断 | 生产级并发处理 |
  1505|| **P3: 监控运维** | 2 周 | 前端 Dashboard + 告警 | 监控系统 |
  1506|| **P4: 生产优化** | 1 周 | 性能调优 + 压测 | 生产就绪版本 |
  1507|
  1508|---
  1509|
  1510|## 参考资料
  1511|
  1512|1. Mem0 - Memory Layer for AI Agents: https://github.com/mem0ai/mem0
  1513|2. Graphiti - Temporal Context Graphs: https://github.com/getzep/graphiti
  1514|3. Letta (MemGPT) - Stateful LLM Agents: https://github.com/letta-ai/letta
  1515|4. 腾讯云向量数据库文档: https://cloud.tencent.com/document/product/1709
  1516|5. HNSW 论文: https://arxiv.org/abs/1603.09320
  1517|6. RRF 论文: https://arxiv.org/abs/2205.11487
  1518|7. Mem0 v3 评估: https://mem0.ai/research
  1519|
  1520|---
  1521|
  1522|*文档版本: v3.0 | 作者: AI Learning Blog | 最后更新: 2026-05-27*
  1523|