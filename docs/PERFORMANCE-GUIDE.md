# 性能测试指南

## 概述

本项目提供了多种性能测试工具，用于评估系统的性能表现。

## 测试工具

### 1. Shell脚本测试

#### 完整性能测试 (`performance-test.sh`)

测试所有API操作的性能，包括创建、读取、搜索和列表查询。

```bash
# 基本用法
chmod +x scripts/performance-test.sh
./scripts/performance-test.sh

# 自定义参数
./scripts/performance-test.sh http://localhost:8080 100 20
# 参数说明: [BASE_URL] [ITERATIONS] [CONCURRENCY]
```

#### 快速性能测试 (`quick-perf-test.sh`)

简化版性能测试，适合日常开发测试。

```bash
chmod +x scripts/quick-perf-test.sh
./scripts/quick-perf-test.sh [BASE_URL] [ITERATIONS] [CONCURRENCY]
```

#### 负载测试 (`load-test.sh`)

专注于并发创建操作的负载测试。

```bash
chmod +x scripts/load-test.sh
./scripts/load-test.sh [BASE_URL] [CONCURRENT] [REQUESTS_PER_WORKER]
```

### 2. Java负载测试 (`LoadTest.java`)

使用Java虚拟线程进行高并发负载测试。

```java
import com.memoryplatform.performance.LoadTest;

public class PerformanceTest {
    public static void main(String[] args) {
        LoadTest test = new LoadTest("http://localhost:8080");
        
        // 运行完整负载测试
        LoadTest.LoadTestResult result = test.runFullLoadTest(100, 20);
        result.printReport();
        
        // 运行指定操作测试
        LoadTest.LoadTestResult createResult = test.runCreateLoadTest(50, 10);
        createResult.printReport();
        
        test.shutdown();
    }
}
```

也可以直接运行：

```bash
# 编译并运行
cd /root/agent-memory-system
javac -cp src/main/java src/test/java/com/memoryplatform/performance/LoadTest.java
java -cp . com.memoryplatform.performance.LoadTest http://localhost:8080 100 20
```

## 测试指标

### 关键指标

- **QPS (Queries Per Second)**: 每秒处理的请求数
- **平均延迟**: 所有请求的平均响应时间
- **P50延迟**: 50%请求的响应时间
- **P95延迟**: 95%请求的响应时间
- **P99延迟**: 99%请求的响应时间
- **成功率**: 成功请求数 / 总请求数

### 性能基准

| 操作 | 最低QPS | 平均延迟 | P99延迟 |
|------|---------|----------|---------|
| 创建 | > 50 | < 200ms | < 1000ms |
| 读取 | > 100 | < 100ms | < 500ms |
| 搜索 | > 30 | < 300ms | < 2000ms |
| 列表 | > 50 | < 150ms | < 800ms |

## 测试场景

### 1. 基准测试

测试单个操作在无并发情况下的性能。

```bash
./scripts/performance-test.sh http://localhost:8080 10 1
```

### 2. 并发测试

测试系统在高并发下的表现。

```bash
# 20并发，100次迭代
./scripts/performance-test.sh http://localhost:8080 100 20
```

### 3. 压力测试

测试系统在极限负载下的稳定性。

```bash
# 50并发，500次迭代
./scripts/performance-test.sh http://localhost:8080 500 50
```

## 配置优化

### 系统配置

在 `application.json` 中可以调整以下配置：

```json
{
  "server": {
    "port": 8080,
    "threadCount": 20,      // 增加线程数
    "timeoutSeconds": 30    // 请求超时时间
  },
  "vectorStore": {
    "type": "milvus",
    "host": "localhost",
    "port": 19530
  },
  "metadataStore": {
    "type": "mysql",
    "url": "jdbc:mysql://...",
    "maximum-pool-size": 20  // 增加连接池大小
  }
}
```

### 运行时配置

使用 `ConfigManager` 支持配置热重载：

```java
import com.memoryplatform.config.ConfigManager;

ConfigManager configManager = ConfigManager.getInstance();

// 动态修改配置
configManager.set("server.threadCount", 30);

// 添加配置变更回调
configManager.addCallback((key, value) -> {
    System.out.println("配置变更: " + key + " = " + value);
});

// 监听配置文件变更
ConfigHotReload.getInstance().startWatching("application.json");
```

## 测试报告示例

```
╔══════════════════════════════════════════════════════════════╗
║            Agent Memory System - 性能测试                     ║
╚══════════════════════════════════════════════════════════════╝

  目标地址:           http://localhost:8080
  迭代次数:           100
  并发数:             20

  总请求数: 400
  总耗时: 2500 ms
  总QPS: 160.0 req/s

┌─────────────────────────────────────────────────────┐
│                  各操作对比                          │
├──────────────┬──────────┬──────────┬───────────────┤
│ 操作         │ QPS      │ 平均延迟 │ P99延迟       │
├──────────────┼──────────┼──────────┼───────────────┤
│ 创建         │ 45.2     │ 180.5ms  │ 450.2ms       │
│ 读取         │ 125.8    │ 45.2ms   │ 120.5ms       │
│ 搜索         │ 35.6     │ 280.3ms  │ 850.1ms       │
│ 列表         │ 52.3     │ 120.1ms  │ 350.8ms       │
└──────────────┴──────────┴──────────┴───────────────┘

  ✓ 性能测试通过
```

## 故障排查

### 1. 连接超时

```
错误: Connection refused
解决: 检查服务是否启动，确认端口配置
```

### 2. 响应时间过长

```
错误: P99延迟超过阈值
解决: 检查数据库连接池、向量存储性能、网络延迟
```

### 3. 成功率过低

```
错误: 成功率低于95%
解决: 检查服务日志，确认系统资源充足
```

## 最佳实践

1. **测试环境**: 在与生产环境相似的环境中进行测试
2. **数据准备**: 测试前确保有足够的测试数据
3. **多次测试**: 运行多次取平均值，避免偶发因素
4. **监控资源**: 测试期间监控CPU、内存、网络等资源使用
5. **逐步增加负载**: 从低并发开始，逐步增加到目标并发数
