# API 版本管理文档

## 概述

Agent Memory Platform 支持 API 版本管理，允许不同版本的客户端使用不同的 API 接口。当前支持 V1（基础）和 V2（增强）两个版本。

## 版本路线图

| 版本 | 语义化版本 | 状态 | 说明 |
|------|-----------|------|------|
| V1 | 1.0.0 | 稳定 | 基础 API - CRUD + 搜索 |
| V2 | 2.0.0 | 稳定 | 增强 API - V1 全部功能 + 企业级特性 |

## 版本检测策略

系统支持三种方式检测 API 版本，按优先级排序：

### 1. URL 路径前缀（最高优先级）

```
GET /api/v1/memories/{id}
GET /api/v2/memories/{id}
```

### 2. Accept 头

```
Accept: application/vnd.memoryplatform.v1+json
Accept: application/vnd.memoryplatform.v2+json
```

### 3. 查询参数

```
GET /api/memories?api_version=v2
```

### 4. 默认版本

未指定版本时使用 V2（最新稳定版）。

## V1 API（基础）

V1 提供核心的 CRUD 和搜索功能。

### 记忆管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/memories` | 创建记忆（对话提取） |
| GET | `/api/v1/memories/{id}` | 获取单条记忆 |
| PUT | `/api/v1/memories/{id}` | 更新记忆 |
| DELETE | `/api/v1/memories/{id}` | 删除记忆 |
| GET | `/api/v1/memories` | 列表查询 |

### 搜索

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/search` | 混合检索 |
| POST | `/api/v1/search/vector` | 向量搜索 |
| POST | `/api/v1/search/graph` | 图搜索 |

### 共享

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/memories/{id}/share` | 共享记忆 |
| DELETE | `/api/v1/memories/{id}/share` | 取消共享 |
| GET | `/api/v1/memories/shared` | 获取共享给我的记忆 |
| GET | `/api/v1/memories/shared-by-me` | 获取我共享的记忆 |

### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/memories/context` | 获取记忆上下文 |
| GET | `/health` | 健康检查 |

### V1 响应格式

```json
{
  "success": true,
  "data": { ... }
}
```

## V2 API（增强）

V2 在 V1 基础上增加以下企业级特性：

### V2 新增功能

| 功能 | 说明 |
|------|------|
| 批量操作 | 批量创建/删除/搜索记忆 |
| 导入导出 | 数据导入导出 |
| 归档记忆 | 记忆归档管理 |
| 压缩 | 记忆压缩优化 |
| 索引重建 | 索引重建和优化 |
| 分析接口 | 使用统计和分析 |
| 管理接口 | 系统管理和监控 |

### V2 新增路由

#### 批量操作

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/memories/batch` | 批量创建记忆 |
| POST | `/api/v2/memories/batch/search` | 批量搜索 |
| DELETE | `/api/v2/memories/batch` | 批量删除记忆 |

#### 导入导出

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/memories/export` | 导出记忆 |
| POST | `/api/v2/memories/import` | 导入记忆 |
| GET | `/api/v2/memories/export/file` | 下载导出文件 |

#### 归档与维护

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/memories/archived` | 获取归档记忆 |
| POST | `/api/v2/memories/compress` | 触发记忆压缩 |
| POST | `/api/v2/memories/reindex` | 重建索引 |

#### 分析接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/analytics/overview` | 总览统计 |
| GET | `/api/v2/analytics/timeline` | 时间线分析 |
| GET | `/api/v2/analytics/categories` | 分类统计 |
| GET | `/api/v2/analytics/tags` | 标签统计 |
| GET | `/api/v2/analytics/agents` | Agent 统计 |
| GET | `/api/v2/analytics/quality` | 质量分析 |

#### 管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/stats` | 系统统计 |
| POST | `/admin/cache/clear` | 清除缓存 |
| GET | `/admin/storage/health` | 存储健康检查 |
| POST | `/admin/maintenance/compact` | 数据压缩维护 |

### V2 响应格式

V2 响应包含额外的版本元数据：

```json
{
  "success": true,
  "data": { ... },
  "apiVersion": "v2",
  "apiVersionSemantic": "2.0.0",
  "timestamp": "2025-01-01T00:00:00Z",
  "requestId": "req_abc123"
}
```

### V2 审计日志

对于 V2 请求，系统会自动记录审计日志（如果审计服务可用）：

```json
{
  "operation": "memory.create",
  "resourceId": "mem_123",
  "agentId": "agent_001",
  "timestamp": "2025-01-01T00:00:00Z",
  "apiVersion": "v2"
}
```

## 版本兼容性

### V2 自动包含 V1

V2 路由自动包含 V1 的所有路由。例如：

- `GET /api/v1/memories` 在 V2 中也可用
- V2 客户端可以使用 V1 的所有端点

### 版本降级

如果 V2 路由不存在，系统会自动回退到 V1：

```
请求: GET /api/v2/memories
检测: V2
路由: V2 存在 → 使用 V2 处理器
```

```
请求: GET /api/v1/memories
检测: V1
路由: V1 存在 → 使用 V1 处理器
```

### 响应头

所有响应包含版本相关头：

```
X-API-Version: v2
X-API-Version-Info: 2.0.0
X-API-Supported-Versions: v1, v2
```

## 迁移指南

### 从 V1 迁移到 V2

1. **更新请求路径**：将 `/api/v1/` 替换为 `/api/v2/`（可选，V1 路径仍然可用）

2. **更新 Accept 头**（可选）：
   ```
   Accept: application/vnd.memoryplatform.v2+json
   ```

3. **处理新响应字段**：V2 响应包含 `apiVersion`、`timestamp` 等字段

4. **使用新功能**：利用批量操作、导入导出等 V2 新增功能

### 向后兼容

- V1 API 保持完全向后兼容
- 现有 V1 客户端无需任何修改
- V2 是 V1 的超集，所有 V1 功能在 V2 中可用

### 版本选择建议

- **新项目**：使用 V2，享受完整功能集
- **现有项目**：可以逐步迁移到 V2，V1 路径仍然可用
- **需要企业级特性**：使用 V2（去重、TTL、审计日志等）

## 技术实现

### 架构

```
VersionedRouter
├── V1 Router (基础路由)
│   ├── /api/v1/memories/*
│   ├── /api/v1/search/*
│   └── /health
└── V2 Router (增强路由)
    ├── /api/v2/memories/*
    ├── /api/v2/search/*
    ├── /api/v2/analytics/*
    └── /admin/*
```

### 核心类

- `ApiVersion` - 版本枚举，定义版本特性和检测逻辑
- `VersionedRouter` - 版本化路由管理器
- `Router` - 单版本路由管理器（支持版本绑定）

### 中间件

全局中间件应用于所有版本：
- `LoggingMiddleware` - 请求日志
- `CorsMiddleware` - CORS 处理
- `AuthMiddleware` - 认证验证（可选）

## 示例代码

### 创建版本化路由器

```java
VersionedRouter vr = new VersionedRouter();

// 注册 V1 路由
Router v1 = vr.getRouter(ApiVersion.V1);
v1.post("/api/memories", memoryHandler);

// 注册 V2 路由
Router v2 = vr.getRouter(ApiVersion.V2);
v2.post("/api/memories", enhancedMemoryHandler);

// V2 继承 V1 路由
vr.inheritV1ToV2();
```

### 客户端请求示例

```bash
# V1 请求
curl http://localhost:8080/api/v1/memories

# V2 请求
curl http://localhost:8080/api/v2/memories

# 使用 Accept 头
curl -H "Accept: application/vnd.memoryplatform.v2+json" \
     http://localhost:8080/api/memories

# 使用查询参数
curl "http://localhost:8080/api/memories?api_version=v2"
```
