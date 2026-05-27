# Agent Memory System - REST API 文档

> **Base URL**: `http://localhost:8080`
>
> **Content-Type**: `application/json`
>
> **字符编码**: UTF-8

---

## 目录

- [总览](#总览)
- [认证](#认证)
- [错误处理](#错误处理)
- [接口列表](#接口列表)
  - [健康检查](#1-健康检查)
  - [创建记忆](#2-创建记忆)
  - [获取记忆](#3-获取单条记忆)
  - [更新记忆](#4-更新记忆)
  - [删除记忆](#5-删除记忆)
  - [列表查询](#6-列表查询)
  - [混合检索](#7-混合检索)
  - [向量检索](#8-向量检索)
  - [图遍历检索](#9-图遍历检索)
- [数据模型](#数据模型)

---

## 总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 系统健康检查 |
| `POST` | `/api/memories` | 从对话中提取并创建记忆 |
| `GET` | `/api/memories/{id}` | 获取单条记忆 |
| `PUT` | `/api/memories/{id}` | 更新记忆 |
| `DELETE` | `/api/memories/{id}` | 删除记忆 |
| `GET` | `/api/memories` | 记忆列表查询 |
| `POST` | `/api/search` | 混合检索（向量+BM25+实体） |
| `POST` | `/api/search/vector` | 纯向量语义检索 |
| `POST` | `/api/search/graph` | 图遍历检索 |

---

## 认证

当前版本暂无认证机制。生产环境建议通过网关或中间件添加 API Key 鉴权。

---

## 错误处理

所有接口在出错时返回统一格式的错误响应：

```json
{
  "success": false,
  "error": {
    "code": 400,
    "message": "缺少必需字段: messages, userId"
  }
}
```

| HTTP 状态码 | 说明 |
|-------------|------|
| `400` | 请求参数错误 |
| `404` | 资源不存在 |
| `405` | 不支持的 HTTP 方法 |
| `500` | 服务器内部错误 |
| `503` | 存储服务未配置 |

---

## 接口列表

### 1. 健康检查

检查系统及各存储层的健康状态。

- **方法**: `GET`
- **路径**: `/health`
- **参数**: 无

**响应示例** (200):

```json
{
  "status": "UP",
  "stores": {
    "vector": "UP",
    "graph": "UP",
    "metadata": "UP"
  },
  "timestamp": "2026-05-27T10:30:45Z",
  "uptimeMs": 123456,
  "uptimeFormatted": "1h 2m 3s",
  "memory": {
    "usedMB": 256,
    "maxMB": 1024
  },
  "runtime": {
    "pid": "12345@hostname",
    "javaVersion": "17.0.2",
    "availableProcessors": 8
  }
}
```

**curl 示例**:

```bash
curl http://localhost:8080/health
```

---

### 2. 创建记忆

从对话文本中提取记忆并异步写入存储。系统会自动从对话中提取实体、生成向量、构建知识图谱关系。

- **方法**: `POST`
- **路径**: `/api/memories`
- **请求体**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `messages` | `Message[]` | ✅ | 对话消息列表 |
| `userId` | `String` | ✅ | 用户ID |
| `agentId` | `String` | ❌ | Agent ID（可选） |

**Message 对象**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `role` | `String` | ❌ | 角色: `user` / `assistant`（默认 `user`） |
| `content` | `String` | ✅ | 消息内容 |

**请求示例**:

```bash
curl -X POST http://localhost:8080/api/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "我叫张三，在腾讯工作，负责微信后端开发"},
      {"role": "assistant", "content": "好的，我记住了你的信息。"}
    ],
    "userId": "user1",
    "agentId": "agent-001"
  }'
```

**响应示例** (201):

```json
{
  "memories": [
    {
      "id": "mem_a1b2c3d4",
      "text": "用户张三在腾讯工作，负责微信后端开发",
      "userId": "user1",
      "agentId": "agent-001",
      "entities": [
        {"name": "张三", "type": "PERSON"},
        {"name": "腾讯", "type": "ORG"},
        {"name": "微信", "type": "PRODUCT"}
      ],
      "importance": 0.8,
      "createdAt": "2026-05-27T10:30:45Z"
    }
  ],
  "count": 1,
  "userId": "user1",
  "agentId": "agent-001"
}
```

---

### 3. 获取单条记忆

根据ID获取单条记忆的详细信息。

- **方法**: `GET`
- **路径**: `/api/memories/{id}`
- **路径参数**:

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | `String` | ✅ | 记忆ID |

**请求示例**:

```bash
curl http://localhost:8080/api/memories/mem_a1b2c3d4
```

**响应示例** (200):

```json
{
  "id": "mem_a1b2c3d4",
  "text": "用户张三在腾讯工作，负责微信后端开发",
  "userId": "user1",
  "agentId": "agent-001",
  "importance": 0.8,
  "createdAt": "2026-05-27T10:30:45Z",
  "updatedAt": "2026-05-27T10:30:45Z",
  "metadata": {
    "entities": ["张三", "腾讯", "微信"],
    "source": "conversation"
  }
}
```

**错误响应** (404):

```json
{
  "success": false,
  "error": {
    "code": 404,
    "message": "记忆不存在: mem_not_exist"
  }
}
```

---

### 4. 更新记忆

更新指定记忆的字段。

- **方法**: `PUT`
- **路径**: `/api/memories/{id}`
- **路径参数**:

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | `String` | ✅ | 记忆ID |

- **请求体**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `text` | `String` | ❌ | 更新后的文本内容 |
| `importance` | `Double` | ❌ | 重要性评分（0.0~1.0） |
| `userId` | `String` | ❌ | 用户ID |
| `agentId` | `String` | ❌ | Agent ID |

**请求示例**:

```bash
curl -X PUT http://localhost:8080/api/memories/mem_a1b2c3d4 \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "张三已从腾讯跳槽到字节跳动",
    "importance": 0.9
  }'
```

**响应示例** (200):

```json
{
  "id": "mem_a1b2c3d4",
  "updated": true,
  "fields": ["content", "importance", "updatedAt"]
}
```

---

### 5. 删除记忆

删除指定记忆及其在向量库和图库中的关联数据。

- **方法**: `DELETE`
- **路径**: `/api/memories/{id}`
- **路径参数**:

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | `String` | ✅ | 记忆ID |

**请求示例**:

```bash
curl -X DELETE http://localhost:8080/api/memories/mem_a1b2c3d4
```

**响应示例** (200):

```json
{
  "id": "mem_a1b2c3d4",
  "deleted": true
}
```

---

### 6. 列表查询

分页查询记忆列表，支持按用户ID和Agent ID过滤。

- **方法**: `GET`
- **路径**: `/api/memories`
- **查询参数**:

| 参数 | 类型 | 必需 | 默认值 | 说明 |
|------|------|------|--------|------|
| `userId` | `String` | ❌ | - | 按用户ID过滤 |
| `agentId` | `String` | ❌ | - | 按Agent ID过滤 |
| `limit` | `Integer` | ❌ | `20` | 返回数量（最大100） |
| `offset` | `Integer` | ❌ | `0` | 偏移量（分页用） |

**请求示例**:

```bash
curl "http://localhost:8080/api/memories?userId=user1&limit=10&offset=0"
```

**响应示例** (200):

```json
{
  "memories": [
    {
      "id": "mem_a1b2c3d4",
      "text": "用户张三在腾讯工作",
      "userId": "user1",
      "agentId": "agent-001",
      "importance": 0.8,
      "createdAt": "2026-05-27T10:30:45Z",
      "updatedAt": "2026-05-27T10:30:45Z"
    }
  ],
  "total": 42,
  "limit": 10,
  "offset": 0
}
```

---

### 7. 混合检索

融合向量语义、BM25文本匹配和实体关联三路信号的混合检索。这是主搜索端点。

- **方法**: `POST`
- **路径**: `/api/search`
- **请求体**:

| 字段 | 类型 | 必需 | 默认值 | 说明 |
|------|------|------|--------|------|
| `text` | `String` | ✅ | - | 搜索查询文本 |
| `userId` | `String` | ✅ | - | 用户ID |
| `agentId` | `String` | ❌ | - | Agent ID过滤 |
| `topK` | `Integer` | ❌ | `10` | 返回结果数（最大100） |
| `threshold` | `Double` | ❌ | `0.5` | 相似度阈值（0.0~1.0） |

**请求示例**:

```bash
curl -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "张三的工作",
    "userId": "user1",
    "topK": 5,
    "threshold": 0.3
  }'
```

**响应示例** (200):

```json
{
  "results": [
    {
      "id": "mem_a1b2c3d4",
      "text": "用户张三在腾讯工作，负责微信后端开发",
      "score": 0.92,
      "semanticScore": 0.88,
      "bm25Score": 0.75,
      "entityBoost": 0.3,
      "metadata": {
        "userId": "user1",
        "importance": 0.8
      }
    }
  ],
  "total": 1,
  "latencyMs": 45,
  "query": "张三的工作",
  "topK": 5
}
```

---

### 8. 向量检索

纯语义向量检索，仅基于embedding向量的余弦相似度排序。

- **方法**: `POST`
- **路径**: `/api/search/vector`
- **请求体**: 同[混合检索](#7-混合检索)

**请求示例**:

```bash
curl -X POST http://localhost:8080/api/search/vector \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "微信后端",
    "userId": "user1",
    "topK": 5
  }'
```

**响应示例** (200):

```json
{
  "results": [
    {
      "id": "mem_a1b2c3d4",
      "text": "用户张三在腾讯工作，负责微信后端开发",
      "score": 0.88,
      "semanticScore": 0.88,
      "bm25Score": 0.0,
      "entityBoost": 0.0,
      "metadata": {}
    }
  ],
  "total": 1,
  "latencyMs": 32,
  "searchType": "vector",
  "query": "微信后端"
}
```

---

### 9. 图遍历检索

基于知识图谱实体关联的检索。从查询文本中提取实体关键词，通过图数据库遍历实体关联关系。

- **方法**: `POST`
- **路径**: `/api/search/graph`
- **请求体**:

| 字段 | 类型 | 必需 | 默认值 | 说明 |
|------|------|------|--------|------|
| `text` | `String` | ✅ | - | 查询文本（用于提取实体） |
| `userId` | `String` | ✅ | - | 用户ID |
| `agentId` | `String` | ❌ | - | Agent ID过滤 |
| `topK` | `Integer` | ❌ | `10` | 返回结果数（最大100） |
| `threshold` | `Double` | ❌ | `0.5` | 相似度阈值（0.0~1.0） |

**请求示例**:

```bash
curl -X POST http://localhost:8080/api/search/graph \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "张三",
    "userId": "user1",
    "topK": 10
  }'
```

**响应示例** (200):

```json
{
  "results": [
    {
      "id": "mem_a1b2c3d4",
      "text": "用户张三在腾讯工作，负责微信后端开发",
      "score": 0.95,
      "semanticScore": 0.88,
      "bm25Score": 0.0,
      "entityBoost": 1.0,
      "metadata": {
        "matchedEntities": ["张三"]
      }
    }
  ],
  "total": 1,
  "latencyMs": 58,
  "searchType": "graph",
  "query": "张三"
}
```

---

## 数据模型

### Memory

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 记忆唯一标识 |
| `text` | `String` | 记忆文本内容 |
| `userId` | `String` | 所属用户ID |
| `agentId` | `String` | 所属Agent ID |
| `entities` | `Entity[]` | 提取的实体列表 |
| `linkedMemoryIds` | `String[]` | 关联的记忆ID列表 |
| `importance` | `Double` | 重要性评分（0.0~1.0） |
| `createdAt` | `String` | 创建时间（ISO 8601） |
| `updatedAt` | `String` | 更新时间（ISO 8601） |

### Entity

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 实体名称 |
| `type` | `String` | 实体类型: `PERSON`, `ORG`, `PRODUCT`, `LOCATION`, `EVENT`, `TIME`, `CONCEPT` |

### Message

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `String` | 角色: `user`, `assistant`, `system` |
| `content` | `String` | 消息内容 |

### SearchResult

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 记忆ID |
| `text` | `String` | 记忆文本 |
| `score` | `Double` | 综合评分 |
| `semanticScore` | `Double` | 向量语义评分 |
| `bm25Score` | `Double` | BM25文本匹配评分 |
| `entityBoost` | `Double` | 实体关联加成 |
| `metadata` | `Map` | 附加元数据 |

---

## 快速开始

```bash
# 1. 启动服务
java -jar target/agent-memory-system-1.0.0.jar

# 2. 检查健康状态
curl http://localhost:8080/health

# 3. 创建记忆
curl -X POST http://localhost:8080/api/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "我叫李四，在阿里巴巴工作"}
    ],
    "userId": "user1"
  }'

# 4. 搜索记忆
curl -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "李四的工作",
    "userId": "user1"
  }'
```
