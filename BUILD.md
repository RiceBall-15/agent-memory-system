# Agent Memory System - 构建与测试操作文档

## 目录

- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [本地测试环境搭建](#本地测试环境搭建)
- [单元测试](#单元测试)
- [集成测试](#集成测试)
- [API 测试](#api-测试)
- [API 使用示例](#api-使用示例)
- [日志系统](#日志系统)
- [配置说明](#配置说明)
- [常见问题](#常见问题)

---

## 环境要求

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 项目使用 Java 17 特性（虚拟线程等） |
| Maven | 3.8+ | 构建工具 |
| Docker | 20.10+ | 可选，用于本地测试存储 |
| Docker Compose | 2.0+ | 可选，一键启动所有依赖服务 |

> **提示**：LLM 功能需要额外安装 Ollama 或配置 OpenAI 兼容 API。

---

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd agent-memory-system
```

### 2. 编译

```bash
mvn clean compile
```

### 3. 打包（跳过测试）

```bash
mvn clean package -DskipTests
```

### 4. 运行测试

```bash
mvn test
```

### 5. 运行应用

```bash
java -jar target/agent-memory-system-1.0.0.jar
```

应用默认监听端口：
- HTTP API: `8080`
- Prometheus 指标: `9090`

### 6. 使用 Docker Compose 启动全部依赖

```bash
# 仅启动存储服务（Milvus + Neo4j + MySQL）
docker compose up -d

# 启动全部服务（含监控）
docker compose --profile monitoring up -d
```

### 7. 构建 Docker 镜像

```bash
# 先打包
mvn clean package -DskipTests

# 构建镜像
docker build -t agent-memory-system:1.0.0 .

# 运行容器
docker run -d \
  --name memory-system \
  -p 8080:8080 \
  -p 9090:9090 \
  -v $(pwd)/config:/app/config \
  agent-memory-system:1.0.0
```

---

## 本地测试环境搭建

### 方式一：Docker Compose（推荐）

一键启动所有依赖服务：

```bash
docker compose up -d
```

启动的服务：
- Milvus: `19530` (gRPC) / `9091` (管理)
- Neo4j: `7474` (Web UI) / `7687` (Bolt)
- MySQL: `3306`

查看状态：
```bash
docker compose ps
docker compose logs -f
```

停止服务：
```bash
docker compose down
```

停止并清除数据：
```bash
docker compose down -v
```

### 方式二：手动启动

#### Milvus

```bash
docker run -d --name milvus \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:latest
```

- gRPC 端口: `19530`
- 管理端口: `9091`
- 访问: http://localhost:9091/healthz

#### Neo4j

```bash
docker run -d --name neo4j \
  -p 7474:7474 \
  -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/password123 \
  -e NEO4J_PLUGINS='["apoc"]' \
  neo4j:5.23
```

- Web UI: http://localhost:7474 (用户名 `neo4j`，密码 `password123`)
- Bolt 协议: `bolt://localhost:7687`

#### MySQL

```bash
docker run -d --name mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root123 \
  -e MYSQL_DATABASE=memory_platform \
  -e MYSQL_CHARSET=utf8mb4 \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci
```

- 连接: `mysql -h 127.0.0.1 -P 3306 -u root -proot123`
- 数据库: `memory_platform`

#### Ollama（LLM 功能）

```bash
# 安装 Ollama
curl -fsSL https://ollama.com/install.sh | sh

# 拉取模型
ollama pull qwen2.5:7b
ollama pull nomic-embed-text

# 验证
ollama list
```

---

## 单元测试

### 运行所有测试

```bash
mvn test
```

### 运行指定测试类

```bash
# 运行Router测试
mvn test -Dtest=RouterTest

# 运行所有Handler测试
mvn test -Dtest=*HandlerTest
```

### 运行指定测试方法

```bash
mvn test -Dtest=RouterTest#testRouteMatching
```

### 测试覆盖率（需要JaCoCo）

```bash
mvn test jacoco:report
# 报告生成在 target/site/jacoco/index.html
```

### 测试注意事项

1. **数据库依赖测试**: 部分测试需要连接 MySQL/Milvus/Neo4j，在没有外部服务时会跳过
2. **LLM 测试**: 涉及 LLM 调用的测试需要 Ollama 或 OpenAI API
3. **超时测试**: 网络相关测试可能因环境不同需要调整超时时间

---

## 集成测试

### 完整流程测试

以下脚本模拟完整的记忆创建→搜索→更新→删除流程：

```bash
# 1. 启动服务（需要先启动依赖服务）
java -jar target/agent-memory-system-1.0.0.jar &

# 2. 等待服务启动
sleep 5

# 3. 检查健康状态
HEALTH=$(curl -s http://localhost:8080/health)
echo "健康状态: $HEALTH"

# 4. 创建记忆
MEMORY=$(curl -s -X POST http://localhost:8080/api/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "我叫王五，在字节跳动做推荐算法"},
      {"role": "assistant", "content": "已记录您的信息。"}
    ],
    "userId": "integration-test-user"
  }')
echo "创建结果: $MEMORY"

# 提取记忆ID
MEMORY_ID=$(echo $MEMORY | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "记忆ID: $MEMORY_ID"

# 5. 获取单条记忆
curl -s http://localhost:8080/api/memories/$MEMORY_ID | python3 -m json.tool

# 6. 列表查询
curl -s "http://localhost:8080/api/memories?userId=integration-test-user&limit=5" | python3 -m json.tool

# 7. 混合检索
curl -s -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "字节跳动推荐算法",
    "userId": "integration-test-user",
    "topK": 5
  }' | python3 -m json.tool

# 8. 更新记忆
curl -s -X PUT http://localhost:8080/api/memories/$MEMORY_ID \
  -H 'Content-Type: application/json' \
  -d '{"importance": 0.95}' | python3 -m json.tool

# 9. 删除记忆
curl -s -X DELETE http://localhost:8080/api/memories/$MEMORY_ID | python3 -m json.tool

# 10. 验证删除
curl -s http://localhost:8080/api/memories/$MEMORY_ID | python3 -m json.tool

# 停止服务
kill %1
```

---

## API 测试

启动应用后，可通过以下命令验证功能。

### 健康检查

```bash
curl http://localhost:8080/health
```

预期返回：
```json
{
  "status": "UP",
  "services": {
    "vectorStore": "OK",
    "graphStore": "OK",
    "metadataStore": "OK"
  }
}
```

### 创建记忆（对话提取）

```bash
curl -X POST http://localhost:8080/api/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "我叫张三，在腾讯工作，负责微信后端开发"},
      {"role": "assistant", "content": "好的，我记住了你的信息。"}
    ],
    "userId": "user1"
  }'
```

### 搜索记忆（混合检索）

```bash
curl -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "张三的工作",
    "userId": "user1",
    "topK": 5
  }'
```

### 向量搜索

```bash
curl -X POST http://localhost:8080/api/search/vector \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "微信后端",
    "userId": "user1",
    "topK": 5
  }'
```

### 图搜索

```bash
curl -X POST http://localhost:8080/api/search/graph \
  -H 'Content-Type: application/json' \
  -d '{
    "entityName": "张三",
    "depth": 2
  }'
```

### 获取单条记忆

```bash
curl http://localhost:8080/api/memories/{memory_id}
```

### 列表查询

```bash
curl "http://localhost:8080/api/memories?userId=user1&limit=10"
```

### Prometheus 指标

```bash
curl http://localhost:9090/metrics
```

---

## API 使用示例

### 场景1: 对话记忆提取

**场景**: 用户对话中包含个人偏好信息，系统自动提取结构化记忆。

```bash
# 用户描述了工作信息
curl -X POST http://localhost:8080/api/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "我是一名前端工程师，最近在用React和TypeScript重构公司内部管理系统"},
      {"role": "assistant", "content": "了解了，你在做前端重构工作。"},
      {"role": "user", "content": "对了，我们团队用的是Ant Design组件库"},
      {"role": "assistant", "content": "好的，已记录技术栈信息。"}
    ],
    "userId": "dev-001"
  }'
```

### 场景2: 精准搜索

**场景**: 查找特定用户的记忆。

```bash
# 搜索"前端"相关的记忆
curl -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "前端技术栈",
    "userId": "dev-001",
    "topK": 3
  }'

# 搜索特定实体
curl -X POST http://localhost:8080/api/search/graph \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "React",
    "userId": "dev-001",
    "topK": 5
  }'
```

### 场景3: 批量管理记忆

```bash
# 查看用户所有记忆
curl "http://localhost:8080/api/memories?userId=dev-001&limit=50"

# 更新记忆重要性
curl -X PUT http://localhost:8080/api/memories/{memory_id} \
  -H 'Content-Type: application/json' \
  -d '{"importance": 0.9}'

# 删除过期记忆
curl -X DELETE http://localhost:8080/api/memories/{memory_id}
```

### 场景4: 使用Python客户端调用

```python
import requests

BASE_URL = "http://localhost:8080"

# 创建记忆
response = requests.post(f"{BASE_URL}/api/memories", json={
    "messages": [
        {"role": "user", "content": "我住在北京市海淀区"}
    ],
    "userId": "user-001"
})
memory = response.json()
print(f"创建了 {memory['count']} 条记忆")

# 搜索记忆
response = requests.post(f"{BASE_URL}/api/search", json={
    "text": "用户住址",
    "userId": "user-001",
    "topK": 5
})
results = response.json()
for result in results["results"]:
    print(f"  [{result['score']:.2f}] {result['text']}")
```

### 场景5: 使用JavaScript/Node.js客户端

```javascript
const BASE_URL = 'http://localhost:8080';

// 创建记忆
async function createMemory(messages, userId) {
  const resp = await fetch(`${BASE_URL}/api/memories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages, userId })
  });
  return resp.json();
}

// 搜索记忆
async function searchMemory(text, userId, topK = 10) {
  const resp = await fetch(`${BASE_URL}/api/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, userId, topK })
  });
  return resp.json();
}

// 使用示例
(async () => {
  const result = await createMemory(
    [{ role: 'user', content: '我喜欢用Python开发AI应用' }],
    'user-002'
  );
  console.log(`创建了 ${result.count} 条记忆`);

  const search = await searchMemory('AI开发', 'user-002');
  search.results.forEach(r => {
    console.log(`[${r.score.toFixed(2)}] ${r.text}`);
  });
})();
```

---

## 日志系统

项目使用 `com.memoryplatform.util.Logger` 工具类进行结构化日志输出，替代 `System.out.println`。

### 日志格式

```
[2026-05-27 10:30:45] [INFO] [MemoryHandler] 请求: POST /api/memories
[2026-05-27 10:30:45] [INFO] [MemoryHandler] 提取到 3 条记忆
[2026-05-27 10:30:45] [WARN] [EmbeddingService] 模型响应较慢: 2500ms
[2026-05-27 10:30:45] [ERROR] [MilvusVectorStore] 连接失败: timeout
```

### 日志级别

| 级别 | 输出流 | 说明 |
|------|--------|------|
| `INFO` | `System.out` | 常规运行信息 |
| `WARN` | `System.out` | 警告信息（不影响服务但需关注） |
| `ERROR` | `System.err` | 错误信息（需要排查修复） |

### 使用方法

```java
import com.memoryplatform.util.Logger;

// 基本用法
Logger.info("服务启动成功");
Logger.warn("连接池使用率超过80%");
Logger.error("数据库连接失败");

// 带类名前缀（推荐）
Logger.info(MemoryHandler.class, "请求处理完成");
Logger.warn(EmbeddingService.class, "模型响应较慢: {}ms", latency);
Logger.error(MilvusVectorStore.class, "连接失败: {}", e.getMessage());

// 带异常堆栈
try {
    // ...
} catch (Exception e) {
    Logger.error(MyService.class, "操作失败", e);
}
```

### 日志输出位置

- **INFO/WARN**: 输出到 `System.out`（终端/标准输出）
- **ERROR**: 输出到 `System.err`（标准错误输出）

Docker环境下可通过以下命令查看：
```bash
docker logs -f memory-system          # 查看所有日志
docker logs -f memory-system 2>&1     # 包含ERROR日志
```

---

## 配置说明

配置文件位于 `config/application.json`，完整示例见 `config/application-example.json`。

### 服务配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `server.port` | int | `8080` | HTTP 服务端口 |
| `server.threadCount` | int | `10` | 工作线程数 |
| `server.timeoutSeconds` | int | `30` | 请求超时时间（秒） |
| `metrics.port` | int | `9090` | Prometheus 指标端口 |

### 向量存储配置（Milvus）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `vectorStore.type` | string | `milvus` | 存储类型 |
| `vectorStore.host` | string | `localhost` | Milvus 服务地址 |
| `vectorStore.port` | int | `19530` | Milvus gRPC 端口 |
| `vectorStore.collection` | string | `agent_memories` | 集合名称 |
| `vectorStore.dimension` | int | `384` | 向量维度 |

### 图存储配置（Neo4j）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `graphStore.type` | string | `neo4j` | 存储类型 |
| `graphStore.uri` | string | `bolt://localhost:7687` | Neo4j Bolt URI |
| `graphStore.username` | string | `neo4j` | 用户名 |
| `graphStore.password` | string | `password123` | 密码 |

### 元数据存储配置（MySQL）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `metadataStore.type` | string | `mysql` | 存储类型（`mysql` / `postgresql`） |
| `metadataStore.url` | string | `jdbc:mysql://localhost:3306/memory_platform` | JDBC URL |
| `metadataStore.username` | string | `root` | 数据库用户名 |
| `metadataStore.password` | string | `root123` | 数据库密码 |
| `metadataStore.maxPoolSize` | int | `10` | 连接池最大连接数 |
| `metadataStore.minIdle` | int | `2` | 连接池最小空闲数 |

### LLM 配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `llm.provider` | string | `ollama` | 提供商（`ollama` / `openai`） |
| `llm.baseUrl` | string | `http://localhost:11434` | API 基础地址 |
| `llm.model` | string | `qwen2.5:7b` | 模型名称 |
| `llm.apiKey` | string | `""` | API 密钥（OpenAI 需要） |
| `llm.temperature` | double | `0.3` | 生成温度 |
| `llm.maxTokens` | int | `2048` | 最大生成 token 数 |

### Embedding 配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `embedding.provider` | string | `ollama` | 提供商 |
| `embedding.model` | string | `nomic-embed-text` | 模型名称 |
| `embedding.dimension` | int | `384` | 向量维度 |

### Redis 配置（可选，用于缓存）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `redis.host` | string | `localhost` | Redis 服务地址 |
| `redis.port` | int | `6379` | Redis 端口 |
| `redis.password` | string | `""` | Redis 密码 |

---

## 常见问题

### 1. 端口冲突

**症状**：启动时报 `Address already in use`

**解决**：
```bash
# 查找占用端口的进程
lsof -i :8080
lsof -i :19530
lsof -i :7687
lsof -i :3306

# 杀掉占用进程
kill -9 <PID>

# 或修改配置使用其他端口
```

### 2. 存储连接失败

**症状**：`Connection refused` 或 `timeout`

**检查清单**：
1. 确认 Docker 容器正在运行：`docker ps`
2. 确认端口映射正确：`docker port <容器名>`
3. 检查网络连通性：`telnet localhost 19530`
4. 检查防火墙规则

**Milvus 特殊情况**：
- Milvus Standalone 启动较慢，可能需要等待 10-30 秒
- 检查 Milvus 日志：`docker logs milvus`

### 3. 内存不足

**症状**：`OutOfMemoryError` 或容器被 OOM Kill

**解决**：
```bash
# 增加 Docker 容器内存限制
docker run -m 4g ...

# 或增加 JVM 堆内存
java -Xmx2g -jar target/agent-memory-system-1.0.0.jar
```

**推荐内存配置**：
| 组件 | 最小内存 | 推荐内存 |
|------|---------|---------|
| 应用本身 | 512MB | 1GB |
| Milvus | 1GB | 2GB |
| Neo4j | 512MB | 1GB |
| MySQL | 256MB | 512MB |

### 4. LLM 调用超时

**症状**：`LlmException: 调用失败` 或响应时间过长

**排查步骤**：
```bash
# 1. 检查 Ollama 是否运行
curl http://localhost:11434/api/tags

# 2. 检查模型是否已下载
ollama list

# 3. 手动测试模型
ollama run qwen2.5:7b "你好"

# 4. 检查系统资源（LLM 需要 GPU 或大量 CPU）
nvidia-smi  # GPU 检查
free -h     # 内存检查
```

**调优建议**：
- 使用更小的模型：`qwen2.5:3b` 替代 `qwen2.5:7b`
- 减少 `maxTokens`：从 2048 降到 1024
- 增加超时时间：修改 `LlmClient` 中的 `READ_TIMEOUT`

### 5. Docker Compose 服务启动顺序问题

**症状**：应用启动时存储服务尚未就绪

**解决**：
- 使用 `docker compose` 的 `depends_on` + `healthcheck`
- 在应用中增加启动重试逻辑（已有内建重试机制）

### 6. 中文编码问题

**症状**：MySQL 存储中文乱码

**解决**：
```bash
# 确保 MySQL 使用 utf8mb4
docker run -d --name mysql \
  -e MYSQL_CHARSET=utf8mb4 \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci
```

---

## 开发调试

### 查看应用日志

应用使用 `com.memoryplatform.util.Logger` 工具类进行结构化日志输出：
- INFO/WARN 日志输出到 `System.out`
- ERROR 日志输出到 `System.err`

```bash
# Docker 容器日志（查看所有输出）
docker logs -f memory-system

# 仅查看错误日志
docker logs -f memory-system 2>&1 | grep "\[ERROR\]"

# 本地运行时直接查看终端输出
# 日志格式: [时间] [级别] [类名] 消息
# 示例:     [2026-05-27 10:30:45] [INFO] [MemoryHandler] 请求: POST /api/memories
```

### 使用 curl 调试

```bash
# 添加 verbose 输出
curl -v http://localhost:8080/health

# 查看响应头
curl -I http://localhost:8080/health
```

### IDE 运行配置

在 IntelliJ IDEA 或 VS Code 中：

1. 主类: `com.memoryplatform.Application`
2. VM 参数: `-Dconfig.path=config/application.json`
3. 工作目录: 项目根目录

> **注意**：`Application` 类可能尚未创建，需先实现主入口类。
