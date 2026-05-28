# Agent Memory System API Documentation

## Overview

Agent记忆系统提供REST API用于管理AI代理的记忆数据，支持语义搜索、上下文管理和版本控制。

**Base URL:** `http://localhost:8080`

---

## 认证

所有API请求需要在Header中携带JWT Token:

```
Authorization: Bearer <your_jwt_token>
```

---

## API Endpoints

### 1. 创建记忆

```http
POST /api/memories
Content-Type: application/json

{
  "content": "记忆内容",
  "metadata": {
    "category": "学习",
    "importance": "high"
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "mem_xxxxxxxx",
    "content": "记忆内容",
    "createdAt": "2026-05-28T10:00:00Z"
  }
}
```

---

### 2. 获取记忆

```http
GET /api/memories/{memoryId}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "mem_xxxxxxxx",
    "content": "记忆内容",
    "metadata": {},
    "createdAt": "2026-05-28T10:00:00Z",
    "updatedAt": "2026-05-28T10:00:00Z"
  }
}
```

---

### 3. 更新记忆

```http
PUT /api/memories/{memoryId}
Content-Type: application/json

{
  "content": "更新后的内容"
}
```

---

### 4. 删除记忆

```http
DELETE /api/memories/{memoryId}
```

---

### 5. 批量删除

```http
POST /api/memories/batch-delete
Content-Type: application/json

{
  "memoryIds": ["id1", "id2", "id3"]
}
```

---

### 6. 语义搜索

```http
POST /api/memories/search
Content-Type: application/json

{
  "query": "搜索关键词",
  "limit": 10,
  "threshold": 0.7
}
```

**Response:**
```json
{
  "success": true,
  "data": [
    {
      "memory": {...},
      "score": 0.85
    }
  ]
}
```

---

### 7. 列表查询

```http
GET /api/memories?page=1&size=20&category=学习
```

---

### 8. 版本历史

```http
GET /api/memories/{memoryId}/history
```

---

### 9. 版本回滚

```http
POST /api/memories/{memoryId}/rollback/{version}
```

---

### 10. 审计日志

```http
GET /api/memories/audit-logs?page=1&size=50
```

---

## 管理API (需要ADMIN角色)

### 健康检查
```http
GET /api/admin/health
```

### 系统统计
```http
GET /api/admin/stats
```

### 缓存管理
```http
POST /api/admin/cache/clear
```

---

## 批量操作API

### 批量导入
```http
POST /api/batch/import
Content-Type: application/json

{
  "memories": [...]
}
```

### 批量导出
```http
POST /api/batch/export
Content-Type: application/json

{
  "memoryIds": [...]
}
```

---

## 错误响应

所有错误响应格式:
```json
{
  "success": false,
  "error": "错误信息",
  "code": "ERROR_CODE"
}
```

**常见错误码:**
- `UNAUTHORIZED` - 认证失败
- `FORBIDDEN` - 权限不足
- `NOT_FOUND` - 资源不存在
- `VALIDATION_ERROR` - 参数验证失败
- `INTERNAL_ERROR` - 服务器内部错误

---

## 版本管理

当前API版本: v1

API变更时会通过Header返回:
```
API-Version: v1
Deprecation: true/false
```
