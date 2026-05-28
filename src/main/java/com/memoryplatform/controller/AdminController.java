package com.memoryplatform.controller;

import com.memoryplatform.cache.LRUCache;
import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.storage.StorageFactory;
import com.memoryplatform.storage.VectorStore;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理员REST Controller
 * <p>
 * 提供系统管理操作：
 * <ul>
 *   <li>GET /admin/stats - 系统统计信息</li>
 *   <li>POST /admin/cache/clear - 清空缓存</li>
 *   <li>GET /admin/storage/health - 存储健康检查</li>
 * </ul>
 * </p>
 * <p>
 * 所有端点需要管理员Token认证（通过请求头 X-Admin-Token 传递）。
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "系统管理", description = "管理员操作（需Token认证）")
public class AdminController {

    private final StorageFactory storageFactory;
    private final LRUCache<String, Object> metadataCache;

    /**
     * 获取系统统计信息
     *
     * @return 存储统计（向量数、图节点数、元数据记录数、缓存命中率）
     */
    @GetMapping("/stats")
    @Operation(summary = "系统统计", description = "获取各存储层的统计信息")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        log.info("[AdminController] 获取系统统计信息");

        Map<String, Object> stats = new HashMap<>();

        // 获取向量存储状态
        VectorStore vectorStore = storageFactory.getDefaultVectorStore();
        if (vectorStore != null) {
            Map<String, Object> vsStats = new HashMap<>();
            vsStats.put("type", vectorStore.getStoreName());
            vsStats.put("status", vectorStore.healthCheck() ? "UP" : "DOWN");
            try {
                Map<String, Object> storeStats = vectorStore.getStats("memories");
                vsStats.put("recordCount", storeStats.getOrDefault("recordCount", 0));
            } catch (Exception e) {
                vsStats.put("recordCount", 0);
                log.warn("[AdminController] 获取向量存储统计失败: {}", e.getMessage());
            }
            stats.put("vectorStore", vsStats);
        } else {
            stats.put("vectorStore", Map.of("type", "unknown", "status", "NOT_CONFIGURED"));
        }

        // 获取图存储状态
        GraphStore graphStore = storageFactory.getDefaultGraphStore();
        if (graphStore != null) {
            Map<String, Object> gsStats = new HashMap<>();
            gsStats.put("type", graphStore.getStoreName());
            gsStats.put("status", graphStore.healthCheck() ? "UP" : "DOWN");
            stats.put("graphStore", gsStats);
        } else {
            stats.put("graphStore", Map.of("type", "unknown", "status", "NOT_CONFIGURED"));
        }

        // 获取元数据存储状态
        MetadataStore metadataStore = storageFactory.getDefaultMetadataStore();
        if (metadataStore != null) {
            Map<String, Object> msStats = new HashMap<>();
            msStats.put("type", metadataStore.getStoreName());
            msStats.put("status", metadataStore.healthCheck() ? "UP" : "DOWN");
            try {
                long recordCount = metadataStore.count("memories", null);
                msStats.put("recordCount", recordCount);
            } catch (Exception e) {
                msStats.put("recordCount", 0);
                log.warn("[AdminController] 获取元数据存储统计失败: {}", e.getMessage());
            }
            stats.put("metadataStore", msStats);
        } else {
            stats.put("metadataStore", Map.of("type", "unknown", "status", "NOT_CONFIGURED"));
        }

        // 获取缓存统计
        LRUCache.CacheStats cacheStats = metadataCache.getStats();
        stats.put("cache", Map.of(
                "hitRate", cacheStats.hitRate,
                "size", cacheStats.size,
                "maxSize", cacheStats.maxSize,
                "hitCount", cacheStats.hitCount,
                "missCount", cacheStats.missCount
        ));
        stats.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    /**
     * 清空LRU缓存
     *
     * @return 清空结果
     */
    @PostMapping("/cache/clear")
    @Operation(summary = "清空缓存", description = "清空LRU元数据缓存")
    public ResponseEntity<ApiResponse<Void>> clearCache() {
        log.info("[AdminController] 清空LRU缓存");

        try {
            metadataCache.clear();
            log.info("[AdminController] 缓存已清空");
            return ResponseEntity.ok(ApiResponse.ok("缓存已清空", null));
        } catch (Exception e) {
            log.error("[AdminController] 清空缓存失败", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.internalError("清空缓存失败: " + e.getMessage()));
        }
    }

    /**
     * 存储健康检查
     * <p>
     * 检查Milvus、Neo4j、MySQL的连接状态。
     * </p>
     *
     * @return 各存储层健康状态
     */
    @GetMapping("/storage/health")
    @Operation(summary = "存储健康检查", description = "检查所有存储层的连接健康状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> storageHealth() {
        log.info("[AdminController] 执行存储健康检查");

        Map<String, Object> health = new HashMap<>();

        // Milvus健康检查
        Map<String, Object> milvusHealth = new HashMap<>();
        VectorStore vectorStore = storageFactory.getDefaultVectorStore();
        if (vectorStore != null) {
            long start = System.currentTimeMillis();
            boolean healthy = vectorStore.healthCheck();
            long latency = System.currentTimeMillis() - start;
            milvusHealth.put("status", healthy ? "UP" : "DOWN");
            milvusHealth.put("latencyMs", latency);
            milvusHealth.put("storeName", vectorStore.getStoreName());
        } else {
            milvusHealth.put("status", "NOT_CONFIGURED");
            milvusHealth.put("latencyMs", 0L);
        }
        health.put("milvus", milvusHealth);

        // Neo4j健康检查
        Map<String, Object> neo4jHealth = new HashMap<>();
        GraphStore graphStore = storageFactory.getDefaultGraphStore();
        if (graphStore != null) {
            long start = System.currentTimeMillis();
            boolean healthy = graphStore.healthCheck();
            long latency = System.currentTimeMillis() - start;
            neo4jHealth.put("status", healthy ? "UP" : "DOWN");
            neo4jHealth.put("latencyMs", latency);
            neo4jHealth.put("storeName", graphStore.getStoreName());
        } else {
            neo4jHealth.put("status", "NOT_CONFIGURED");
            neo4jHealth.put("latencyMs", 0L);
        }
        health.put("neo4j", neo4jHealth);

        // MySQL健康检查
        Map<String, Object> mysqlHealth = new HashMap<>();
        MetadataStore metadataStore = storageFactory.getDefaultMetadataStore();
        if (metadataStore != null) {
            long start = System.currentTimeMillis();
            boolean healthy = metadataStore.healthCheck();
            long latency = System.currentTimeMillis() - start;
            mysqlHealth.put("status", healthy ? "UP" : "DOWN");
            mysqlHealth.put("latencyMs", latency);
            mysqlHealth.put("storeName", metadataStore.getStoreName());
        } else {
            mysqlHealth.put("status", "NOT_CONFIGURED");
            mysqlHealth.put("latencyMs", 0L);
        }
        health.put("mysql", mysqlHealth);

        log.info("[AdminController] 存储健康检查完成");
        return ResponseEntity.ok(ApiResponse.ok(health));
    }
}
