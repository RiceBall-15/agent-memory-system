# Agent Memory System - 构建与测试操作文档

## 目录

- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [本地测试环境搭建](#本地测试环境搭建)
- [API 测试](#api-测试)
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

应用日志输出到标准输出（stdout），可通过以下方式查看：

```bash
# Docker 容器日志
docker logs -f memory-system

# 本地运行时直接查看终端输出
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
