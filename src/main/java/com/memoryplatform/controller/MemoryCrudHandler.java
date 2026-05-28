package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.BatchDeleteRequest;
import com.memoryplatform.dto.CreateMemoryRequest;
import com.memoryplatform.dto.UpdateMemoryRequest;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryType;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 记忆CRUD操作处理器
 * <p>
 * 处理记忆的增删改查操作：
 * <ul>
 *   <li>POST /api/memories - 从对话中提取并保存记忆</li>
 *   <li>GET /api/memories/{id} - 获取单条记忆</li>
 *   <li>PUT /api/memories/{id} - 更新记忆</li>
 *   <li>DELETE /api/memories/{id} - 删除记忆</li>
 *   <li>POST /api/memories/batch-delete - 批量删除记忆</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryCrudHandler {

    private static final String METADATA_TABLE = "memories";
    private static final String VECTOR_COLLECTION = "memories";
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    private final MemoryExtractionService extractionService;
    private final ConcurrentWriteService writeService;
    private final MetadataStore metadataStore;
    private final VectorStore vectorStore;
    private final GraphStore graphStore;

    // ==================== 创建记忆 ====================

    /**
     * 从对话文本中提取记忆并保存
     *
     * @param request 包含对话消息的请求体
     * @return 创建成功的记忆信息
     */
    public ResponseEntity<ApiResponse<List<WriteResult>>> createMemory(CreateMemoryRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("[MemoryCrudHandler] 创建记忆请求: userId={}, agentId={}, requestId={}",
                request.getUserId(), request.getAgentId(), requestId);

        try {
            // 1. 将DTO消息转换为模型消息
            List<com.memoryplatform.model.Message> messages = request.getMessages().stream()
                    .map(m -> com.memoryplatform.model.Message.builder()
                            .role(m.getRole())
                            .content(m.getContent())
                            .build())
                    .toList();

            // 2. 从对话中提取记忆
            List<Memory> memories = extractionService.extractFromConversation(
                    messages, request.getUserId(), request.getAgentId());

            // 3. 应用请求中的memoryType到提取的记忆
            MemoryType requestedType = request.getMemoryTypeOrDefault();
            for (Memory memory : memories) {
                if (memory.getMemoryType() == null || memory.getMemoryType() == MemoryType.DEFAULT) {
                    memory.setMemoryType(requestedType);
                }
            }

            log.info("[MemoryCrudHandler] 提取到 {} 条记忆, requestId={}", memories.size(), requestId);

            if (memories.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.ok("未提取到记忆", Collections.emptyList()));
            }

            // 4. 异步写入存储层（阻塞等待结果）
            List<WriteResult> results = new ArrayList<>();
            for (Memory memory : memories) {
                try {
                    CompletableFuture<WriteResult> future = writeService.write(memory);
                    WriteResult result = future.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    results.add(result);
                } catch (Exception e) {
                    log.error("[MemoryCrudHandler] 写入记忆失败: id={}, error={}",
                            memory.getId(), e.getMessage());
                    results.add(WriteResult.failureBuilder()
                            .error("写入失败: " + e.getMessage())
                            .build());
                }
            }

            long successCount = results.stream().filter(WriteResult::isSuccess).count();
            log.info("[MemoryCrudHandler] 记忆创建完成: total={}, success={}, requestId={}",
                    results.size(), successCount, requestId);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("记忆创建成功", results));
        } catch (Exception e) {
            log.error("[MemoryCrudHandler] 创建记忆异常: requestId={}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("服务器内部错误: " + e.getMessage()));
        }
    }

    // ==================== 获取记忆 ====================

    /**
     * 根据ID获取单条记忆
     *
     * @param id 记忆ID
     * @return 记忆详情
     */
    public ResponseEntity<ApiResponse<Memory>> getMemory(String id) {
        log.info("[MemoryCrudHandler] 获取记忆: id={}", id);

        try {
            if (metadataStore == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.internalError("元数据存储未配置"));
            }

            // 从元数据存储查询
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", id);
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (records == null || records.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.notFound("记忆不存在: " + id));
            }

            MetadataRecord record = records.get(0);
            Memory memory = buildMemoryFromRecord(record);

            log.info("[MemoryCrudHandler] 获取记忆成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok(memory));
        } catch (Exception e) {
            log.error("[MemoryCrudHandler] 获取记忆异常: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("获取记忆失败: " + e.getMessage()));
        }
    }

    // ==================== 更新记忆 ====================

    /**
     * 更新记忆信息
     *
     * @param id      记忆ID
     * @param request 更新内容
     * @return 更新后的记忆
     */
    public ResponseEntity<ApiResponse<Memory>> updateMemory(String id, UpdateMemoryRequest request) {
        log.info("[MemoryCrudHandler] 更新记忆: id={}", id);

        try {
            if (metadataStore == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.internalError("元数据存储未配置"));
            }

            // 1. 验证记忆是否存在
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", id);
            List<MetadataRecord> existingRecords = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (existingRecords == null || existingRecords.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.notFound("记忆不存在: " + id));
            }

            // 2. 构建更新字段（仅支持 content, importance, data_json 等数据库中存在的列）
            Map<String, Object> updates = new LinkedHashMap<>();
            if (request.getText() != null && !request.getText().isBlank()) {
                updates.put("content", request.getText());
            }
            if (request.getImportance() != null) {
                updates.put("importance", request.getImportance());
            }
            // 注意: status字段不在JdbcMetadataStore允许更新的列白名单中
            // 如果需要支持状态更新，请先在数据库中添加status列并更新JdbcMetadataStore

            if (updates.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.badRequest("没有可更新的字段"));
            }

            // 3. 执行更新
            boolean success = metadataStore.update(METADATA_TABLE, id, updates);

            if (!success) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.internalError("更新失败"));
            }

            // 4. 返回更新后的记忆
            MetadataRecord updatedRecord = existingRecords.get(0);
            Memory memory = buildMemoryFromRecord(updatedRecord);

            // 应用更新字段到内存对象
            if (request.getText() != null) {
                memory.setText(request.getText());
            }
            if (request.getImportance() != null) {
                memory.setImportance(request.getImportance());
            }
            // status字段不在数据库中，仅在内存对象中更新
            if (request.getStatus() != null) {
                memory.setStatus(request.getStatus());
            }
            memory.setUpdatedAt(Instant.now());

            log.info("[MemoryCrudHandler] 记忆更新成功: id={}, fields={}", id, updates.keySet());
            return ResponseEntity.ok(ApiResponse.ok("更新成功", memory));
        } catch (Exception e) {
            log.error("[MemoryCrudHandler] 更新记忆异常: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("更新失败: " + e.getMessage()));
        }
    }

    // ==================== 删除记忆 ====================

    /**
     * 删除记忆
     *
     * @param id 记忆ID
     * @return 删除结果
     */
    public ResponseEntity<ApiResponse<Void>> deleteMemory(String id) {
        log.info("[MemoryCrudHandler] 删除记忆: id={}", id);

        try {
            if (metadataStore == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.internalError("元数据存储未配置"));
            }

            // 1. 验证记忆是否存在
            Map<String, Object> filters = new HashMap<>();
            filters.put("id", id);
            List<MetadataRecord> existingRecords = metadataStore.find(METADATA_TABLE, filters, 1, 0);

            if (existingRecords == null || existingRecords.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.notFound("记忆不存在: " + id));
            }

            // 2. 从元数据存储删除
            boolean metadataDeleted = metadataStore.delete(METADATA_TABLE, id);
            if (!metadataDeleted) {
                log.warn("[MemoryCrudHandler] 元数据删除返回false: id={}", id);
            }

            // 3. 从向量存储删除（尽力删除，不影响整体结果）
            try {
                if (vectorStore != null) {
                    vectorStore.delete(VECTOR_COLLECTION, List.of(id));
                }
            } catch (Exception e) {
                log.warn("[MemoryCrudHandler] 向量存储删除失败: id={}, error={}", id, e.getMessage());
            }

            // 4. 从图存储删除（尽力删除，不影响整体结果）
            try {
                if (graphStore != null) {
                    graphStore.delete(id);
                }
            } catch (Exception e) {
                log.warn("[MemoryCrudHandler] 图存储删除失败: id={}, error={}", id, e.getMessage());
            }

            log.info("[MemoryCrudHandler] 记忆删除成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok("删除成功", null));
        } catch (Exception e) {
            log.error("[MemoryCrudHandler] 删除记忆异常: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("删除失败: " + e.getMessage()));
        }
    }

    // ==================== 批量删除记忆 ====================

    /**
     * 批量删除记忆
     *
     * @param request 批量删除请求（包含ID列表）
     * @return 批量删除结果
     */
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDeleteMemories(BatchDeleteRequest request) {
        log.info("[MemoryCrudHandler] 批量删除记忆: count={}", request.getIds().size());

        try {
            if (metadataStore == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.internalError("元数据存储未配置"));
            }

            List<String> deletedIds = new ArrayList<>();
            List<String> failedIds = new ArrayList<>();

            for (String id : request.getIds()) {
                try {
                    // 从元数据存储删除
                    boolean deleted = metadataStore.delete(METADATA_TABLE, id);

                    // 从向量存储删除（尽力）
                    try {
                        if (vectorStore != null) {
                            vectorStore.delete(VECTOR_COLLECTION, List.of(id));
                        }
                    } catch (Exception e) {
                        log.warn("[MemoryCrudHandler] 批量删除-向量存储删除失败: id={}", id);
                    }

                    // 从图存储删除（尽力）
                    try {
                        if (graphStore != null) {
                            graphStore.delete(id);
                        }
                    } catch (Exception e) {
                        log.warn("[MemoryCrudHandler] 批量删除-图存储删除失败: id={}", id);
                    }

                    if (deleted) {
                        deletedIds.add(id);
                    } else {
                        failedIds.add(id);
                    }
                } catch (Exception e) {
                    log.warn("[MemoryCrudHandler] 批量删除-删除失败: id={}, error={}", id, e.getMessage());
                    failedIds.add(id);
                }
            }

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("deletedCount", deletedIds.size());
            responseData.put("failedCount", failedIds.size());
            responseData.put("deletedIds", deletedIds);
            if (!failedIds.isEmpty()) {
                responseData.put("failedIds", failedIds);
            }

            log.info("[MemoryCrudHandler] 批量删除完成: deleted={}, failed={}",
                    deletedIds.size(), failedIds.size());
            return ResponseEntity.ok(ApiResponse.ok("批量删除完成", responseData));
        } catch (Exception e) {
            log.error("[MemoryCrudHandler] 批量删除异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("批量删除失败: " + e.getMessage()));
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 从MetadataRecord构建Memory对象
     *
     * @param record 元数据记录
     * @return Memory对象
     */
    static Memory buildMemoryFromRecord(MetadataRecord record) {
        Memory.MemoryBuilder builder = Memory.builder()
                .id(record.getId())
                .text(record.getContent())
                .userId(record.getUserId())
                .agentId(record.getAgentId())
                .importance(record.getImportance())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .status("ACTIVE");

        // 从data map中提取memoryType
        if (record.getData() != null && record.getData().containsKey("memoryType")) {
            try {
                builder.memoryType(MemoryType.valueOf(record.getData().get("memoryType").toString()));
            } catch (Exception ignored) {
                builder.memoryType(MemoryType.DEFAULT);
            }
        } else {
            builder.memoryType(MemoryType.DEFAULT);
        }

        return builder.build();
    }
}
