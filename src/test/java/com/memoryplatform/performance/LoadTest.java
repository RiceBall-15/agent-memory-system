package com.memoryplatform.performance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统负载测试类
 * <p>
 * 用于测试系统在高并发场景下的性能表现，包括：
 * <ul>
 *   <li>并发创建记忆</li>
 *   <li>并发读取记忆</li>
 *   <li>并发搜索记忆</li>
 *   <li>混合负载测试</li>
 * </ul>
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * LoadTest test = new LoadTest("http://localhost:8080");
 * LoadTestResult result = test.runFullLoadTest(100, 20);
 * result.printReport();
 * }</pre>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
public class LoadTest {

    /** 目标服务器地址 */
    private final String baseUrl;

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 线程池 */
    private final ExecutorService executor;

    /**
     * 创建负载测试实例
     *
     * @param baseUrl 服务器地址，如 http://localhost:8080
     */
    public LoadTest(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 运行完整负载测试
     *
     * @param totalRequests 总请求数
     * @param concurrency   并发数
     * @return 测试结果
     */
    public LoadTestResult runFullLoadTest(int totalRequests, int concurrency) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          Agent Memory System - Java负载测试                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        LoadTestResult result = new LoadTestResult(totalRequests, concurrency);
        long startTime = System.currentTimeMillis();

        // 创建信号量控制并发
        Semaphore semaphore = new Semaphore(concurrency);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        List<Future<RequestResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            CompletableFuture<RequestResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return executeMixedRequest(index);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RequestResult.failure(index, e.getMessage());
                } catch (Exception e) {
                    return RequestResult.failure(index, e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, executor);

            futures.add(future);
        }

        // 等待所有请求完成
        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long endTime = System.currentTimeMillis();
        result.setDurationMs(endTime - startTime);

        // 收集结果
        for (Future<RequestResult> future : futures) {
            try {
                result.addRequestResult(future.get());
            } catch (Exception e) {
                // 忽略
            }
        }

        return result;
    }

    /**
     * 运行创建操作负载测试
     *
     * @param totalRequests 总请求数
     * @param concurrency   并发数
     * @return 测试结果
     */
    public LoadTestResult runCreateLoadTest(int totalRequests, int concurrency) {
        return runOperationLoadTest(totalRequests, concurrency, "create");
    }

    /**
     * 运行读取操作负载测试
     *
     * @param totalRequests 总请求数
     * @param concurrency   并发数
     * @return 测试结果
     */
    public LoadTestResult runReadLoadTest(int totalRequests, int concurrency) {
        return runOperationLoadTest(totalRequests, concurrency, "read");
    }

    /**
     * 运行搜索操作负载测试
     *
     * @param totalRequests 总请求数
     * @param concurrency   并发数
     * @return 测试结果
     */
    public LoadTestResult runSearchLoadTest(int totalRequests, int concurrency) {
        return runOperationLoadTest(totalRequests, concurrency, "search");
    }

    /**
     * 运行指定操作的负载测试
     */
    private LoadTestResult runOperationLoadTest(int totalRequests, int concurrency, String operation) {
        LoadTestResult result = new LoadTestResult(totalRequests, concurrency);
        long startTime = System.currentTimeMillis();

        Semaphore semaphore = new Semaphore(concurrency);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        List<Future<RequestResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            CompletableFuture<RequestResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return executeOperation(operation, index);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RequestResult.failure(index, e.getMessage());
                } catch (Exception e) {
                    return RequestResult.failure(index, e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, executor);

            futures.add(future);
        }

        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long endTime = System.currentTimeMillis();
        result.setDurationMs(endTime - startTime);

        for (Future<RequestResult> future : futures) {
            try {
                result.addRequestResult(future.get());
            } catch (Exception e) {
                // 忽略
            }
        }

        return result;
    }

    /**
     * 执行混合请求（根据索引选择操作类型）
     */
    private RequestResult executeMixedRequest(int index) {
        String[] operations = {"create", "read", "search"};
        String operation = operations[index % operations.length];
        return executeOperation(operation, index);
    }

    /**
     * 执行指定操作
     */
    private RequestResult executeOperation(String operation, int index) {
        try {
            long requestStart = System.currentTimeMillis();
            HttpRequest request;
            HttpResponse<String> response;

            switch (operation) {
                case "create":
                    request = buildCreateRequest(index);
                    break;
                case "read":
                    request = buildReadRequest(index);
                    break;
                case "search":
                    request = buildSearchRequest(index);
                    break;
                default:
                    return RequestResult.failure(index, "Unknown operation: " + operation);
            }

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long requestEnd = System.currentTimeMillis();
            long latencyMs = requestEnd - requestStart;

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return new RequestResult(index, operation, response.statusCode(), latencyMs, success);

        } catch (Exception e) {
            return RequestResult.failure(index, operation, e.getMessage());
        }
    }

    /**
     * 构建创建记忆请求
     */
    private HttpRequest buildCreateRequest(int index) {
        String[] topics = {"机器学习", "Web开发", "分布式系统", "数据库优化", "容器技术",
                          "微服务", "云原生", "网络安全", "人工智能", "系统设计"};
        String topic = topics[index % topics.length];

        String json = String.format("""
                {
                    "messages": [
                        {"role": "user", "content": "我在%s方面有%d个问题"},
                        {"role": "assistant", "content": "好的，请问关于%s的具体问题"},
                        {"role": "user", "content": "我想了解%s的最佳实践"}
                    ],
                    "userId": "loadtest-user-%d",
                    "agentId": "loadtest-agent"
                }
                """, topic, index, topic, topic, index % 10);

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/memories"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 构建读取记忆请求
     */
    private HttpRequest buildReadRequest(int index) {
        // 实际场景中需要先获取有效的ID
        String testId = "test-memory-" + index;
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/memories/" + testId))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 构建搜索记忆请求
     */
    private HttpRequest buildSearchRequest(int index) {
        String[] queries = {"机器学习", "Web开发", "分布式系统", "数据库优化", "容器技术"};
        String query = queries[index % queries.length];

        String json = String.format("""
                {
                    "text": "%s",
                    "userId": "loadtest-user-%d",
                    "topK": 10
                }
                """, query, index % 10);

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/memories/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 关闭负载测试，释放资源
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    /**
     * 请求结果
     */
    public static class RequestResult {
        private final int index;
        private final String operation;
        private final int statusCode;
        private final long latencyMs;
        private final boolean success;
        private final String error;

        public RequestResult(int index, String operation, int statusCode, long latencyMs, boolean success) {
            this.index = index;
            this.operation = operation;
            this.statusCode = statusCode;
            this.latencyMs = latencyMs;
            this.success = success;
            this.error = null;
        }

        public static RequestResult failure(int index, String error) {
            return new RequestResult(index, "unknown", 0, 0, false, error);
        }

        public static RequestResult failure(int index, String operation, String error) {
            return new RequestResult(index, operation, 0, 0, false, error);
        }

        private RequestResult(int index, String operation, int statusCode, long latencyMs, boolean success, String error) {
            this.index = index;
            this.operation = operation;
            this.statusCode = statusCode;
            this.latencyMs = latencyMs;
            this.success = success;
            this.error = error;
        }

        // Getters
        public int getIndex() { return index; }
        public String getOperation() { return operation; }
        public int getStatusCode() { return statusCode; }
        public long getLatencyMs() { return latencyMs; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }

    /**
     * 负载测试结果
     */
    public static class LoadTestResult {
        private final int totalRequests;
        private final int concurrency;
        private long durationMs;
        private int successCount;
        private int failureCount;
        private long totalLatencyMs;
        private long minLatencyMs = Long.MAX_VALUE;
        private long maxLatencyMs = 0;
        private List<Long> latencies = new ArrayList<>();

        public LoadTestResult(int totalRequests, int concurrency) {
            this.totalRequests = totalRequests;
            this.concurrency = concurrency;
        }

        public void addRequestResult(RequestResult result) {
            if (result.isSuccess()) {
                successCount++;
                long latency = result.getLatencyMs();
                totalLatencyMs += latency;
                minLatencyMs = Math.min(minLatencyMs, latency);
                maxLatencyMs = Math.max(maxLatencyMs, latency);
                latencies.add(latency);
            } else {
                failureCount++;
            }
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        /**
         * 计算平均延迟
         */
        public double getAverageLatencyMs() {
            return successCount > 0 ? (double) totalLatencyMs / successCount : 0;
        }

        /**
         * 计算P50延迟
         */
        public long getP50LatencyMs() {
            return getPercentile(50);
        }

        /**
         * 计算P95延迟
         */
        public long getP95LatencyMs() {
            return getPercentile(95);
        }

        /**
         * 计算P99延迟
         */
        public long getP99LatencyMs() {
            return getPercentile(99);
        }

        /**
         * 计算QPS
         */
        public double getQps() {
            return durationMs > 0 ? (double) successCount * 1000 / durationMs : 0;
        }

        /**
         * 计算成功率
         */
        public double getSuccessRate() {
            return totalRequests > 0 ? (double) successCount / totalRequests * 100 : 0;
        }

        private long getPercentile(int percentile) {
            if (latencies.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(latencies);
            sorted.sort(Long::compareTo);
            int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
            return sorted.get(Math.max(0, index));
        }

        /**
         * 打印测试报告
         */
        public void printReport() {
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║                    负载测试结果报告                         ║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.printf("  %-20s %d%n", "总请求数:", totalRequests);
            System.out.printf("  %-20s %d%n", "并发数:", concurrency);
            System.out.printf("  %-20s %d ms%n", "总耗时:", durationMs);
            System.out.printf("  %-20s %.1f req/s%n", "QPS:", getQps());
            System.out.println();
            System.out.printf("  %-20s %d%n", "成功数:", successCount);
            System.out.printf("  %-20s %d%n", "失败数:", failureCount);
            System.out.printf("  %-20s %.1f%%%n", "成功率:", getSuccessRate());
            System.out.println();
            System.out.println("  响应时间 (ms):");
            System.out.printf("    %-18s %.2f%n", "平均:", getAverageLatencyMs());
            System.out.printf("    %-18s %d%n", "最小:", minLatencyMs == Long.MAX_VALUE ? 0 : minLatencyMs);
            System.out.printf("    %-18s %d%n", "最大:", maxLatencyMs);
            System.out.printf("    %-18s %d%n", "P50:", getP50LatencyMs());
            System.out.printf("    %-18s %d%n", "P95:", getP95LatencyMs());
            System.out.printf("    %-18s %d%n", "P99:", getP99LatencyMs());
            System.out.println();

            // 性能评估
            System.out.println("  性能评估:");
            boolean pass = true;

            if (getQps() < 10) {
                System.out.println("    ⚠ QPS较低，建议优化系统配置");
            }

            if (getP99LatencyMs() > 5000) {
                System.out.println("    ✗ P99延迟过高，超过5000ms阈值");
                pass = false;
            }

            if (getSuccessRate() < 95) {
                System.out.println("    ✗ 成功率过低，低于95%阈值");
                pass = false;
            }

            if (pass) {
                System.out.println();
                System.out.println("  ✓ 负载测试通过");
            } else {
                System.out.println();
                System.out.println("  ✗ 负载测试未通过");
            }
            System.out.println();
        }
    }

    /**
     * 主方法 - 用于独立运行负载测试
     */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        int totalRequests = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 20;

        LoadTest test = new LoadTest(baseUrl);

        try {
            // 运行完整负载测试
            LoadTestResult result = test.runFullLoadTest(totalRequests, concurrency);
            result.printReport();
        } finally {
            test.shutdown();
        }
    }
}
