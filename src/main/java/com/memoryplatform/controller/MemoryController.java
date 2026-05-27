package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.BatchDeleteRequest;
import com.memoryplatform.dto.CreateMemoryRequest;
import com.memoryplatform.dto.SearchRequest;
import com.memoryplatform.dto.UpdateMemoryRequest;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.HybridRetrievalService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆管理REST Controller
 * <p>
 * 提供记忆的CRUD操作：
 * <ul>
 *   <li>POST /api/memories - 从对话中提取并保存记忆</li>
 *   <li>GET /api/memories/{id} - 获取单条记忆</li>
 *   <li>GET /api/memories - 搜索/列表查询记忆</li>
 *   <li>PUT /api/memories/{id} - 更新记忆</li>
 *   <li>DELETE /api/memories/{id} - 删除记忆</li>
 *   <li>POST /api/memories/search - 语义搜索记忆</li>
 *   <li>POST /api/memories/batch-delete - 批量删除记忆</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
@Tag(name = "记忆管理", description = "记忆的CRUD操作")
public class MemoryController {

    private static final String METADATA_TABLE = "memories";
    private static final String VECTOR_COLLECTION = "memories";
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    private final MemoryExtractionService extractionService;
    private final ConcurrentWriteService writeService;
    private final HybridRetrievalService retrievalService;
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
    @PostMapping
    @Operation(summary = "创建记忆", description = "从对话文本中提取记忆并保存到三层存储")
    public ResponseEntity<ApiResponse<List<WriteResult>>> createMemory(
            @Valid @RequestBody CreateMemoryRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("[MemoryController] 创建记忆请求: userId={}, agentId={}, requestId={}",
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

            log.info("[MemoryController] 提取到 {} 条记忆, requestId={}", memories.size(), requestId);

            if (memories.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.ok("未提取到记忆", Collections.emptyList()));
            }

            // 3. 异步写入存储层（阻塞等待结果）
            List<WriteResult> results = new ArrayList<>();
            for (Memory memory : memories) {
                try {
                    CompletableFuture<WriteResult> future = writeService.write(memory);
                    WriteResult result = future.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    results.add(result);
                } catch (Exception e) {
                    log.error("[MemoryController] 写入记忆失败: id={}, error={}",
                            memory.getId(), e.getMessage());
                    results.add(WriteResult.failureBuilder()
                            .error("写入失败: " + e.getMessage())
                            .build());
                }
            }

            long successCount = results.stream().filter(WriteResult::isSuccess).count();
            log.info("[MemoryController] 记忆创建完成: total={}, success={}, requestId={}",
                    results.size(), successCount, requestId);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("记忆创建成功", results));
        } catch (Exception e) {
            log.error("[MemoryController] 创建记忆异常: requestId={}", requestId, e);
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
    @GetMapping("/{id}")
    @Operation(summary = "获取记忆", description = "根据ID获取记忆详情")
    public ResponseEntity<ApiResponse<Memory>> getMemory(
            @Parameter(description = "记忆ID") @PathVariable String id) {
        log.info("[MemoryController] 获取记忆: id={}", id);

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

            log.info("[MemoryController] 获取记忆成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok(memory));
        } catch (Exception e) {
            log.error("[MemoryController] 获取记忆异常: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("获取记忆失败: " + e.getMessage()));
        }
    }

    // ==================== 搜索/列表查询记忆 ====================

    /**
     * 搜索/列表查询记忆
     * <p>
     * 支持两种模式：
     * <ul>
     *   <li>语义搜索：提供searchText参数时，使用混合检索服务进行语义搜索</li>
     *   <li>列表查询：不提供searchText时，按userId/agentId过滤列表</li>
     * </ul>
     * </p>
     *
     * @param userId    用户ID（必需）
     * @param agentId   智能体ID（可选）
     * @param searchText 搜索文本（可选，提供时触发语义搜索）
     * @param limit     返回数量（默认20，最大100）
     * @param offset    偏移量（默认0）
     * @return 记忆列表
     */
    @GetMapping
    @Operation(summary = "搜索/列表查询记忆", description = "支持语义搜索和列表查询")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMemories(
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "智能体ID") @RequestParam(required = false) String agentId,
            @Parameter(description = "搜索文本（可选）") @RequestParam(required = false) String searchText,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "偏移量") @RequestParam(defaultValue = "0") int offset) {

        log.info("[MemoryController] 搜索/列表查询: userId={}, agentId={}, searchText={}, limit={}, offset={}",
                userId, agentId, searchText, limit, offset);

        try {
            // 限制参数范围
            limit = Math.min(Math.max(limit, 1), 100);
            offset = Math.max(offset, 0);

            Map<String, Object> result;

            if (searchText != null && !searchText.isBlank()) {
                // 语义搜索模式
                result = executeSemanticSearch(userId, agentId, searchText, limit, offset);
            } else {
                // 列表查询模式
                result = executeListQuery(userId, agentId, limit, offset);
            }

            log.info("[MemoryController] 查询完成: total={}, returned={}",
                    result.get("total"), ((List<?>) result.get("memories")).size());
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("[MemoryController] 查询异常: userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("查询失败: " + e.getMessage()));
        }
    }

    // ==================== 语义搜索记忆 ====================

    /**
     * 语义搜索记忆（POST方式，支持复杂搜索条件）
     *
     * @param request 搜索请求
     * @return 搜索结果
     */
    @PostMapping("/search")
    @Operation(summary = "语义搜索记忆", description = "使用混合检索服务进行语义搜索")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMemories(
            @Valid @RequestBody SearchRequest request) {
        log.info("[MemoryController] 语义搜索: userId={}, text={}", request.getUserId(), request.getText());

        try {
            Map<String, Object> result = executeSemanticSearch(
                    request.getUserId(), request.getAgentId(), request.getText(),
                    request.getTopK(), 0);

            log.info("[MemoryController] 搜索完成: 返回{}条结果", ((List<?>) result.get("memories")).size());
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("[MemoryController] 搜索异常: userId={}", request.getUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("搜索失败: " + e.getMessage()));
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
    @PutMapping("/{id}")
    @Operation(summary = "更新记忆", description = "更新记忆的文本、重要度或状态")
    public ResponseEntity<ApiResponse<Memory>> updateMemory(
            @Parameter(description = "记忆ID") @PathVariable String id,
            @Valid @RequestBody UpdateMemoryRequest request) {
        log.info("[MemoryController] 更新记忆: id={}", id);

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

            log.info("[MemoryController] 记忆更新成功: id={}, fields={}", id, updates.keySet());
            return ResponseEntity.ok(ApiResponse.ok("更新成功", memory));
        } catch (Exception e) {
            log.error("[MemoryController] 更新记忆异常: id={}", id, e);
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
    @DeleteMapping("/{id}")
    @Operation(summary = "删除记忆", description = "从所有存储层删除记忆")
    public ResponseEntity<ApiResponse<Void>> deleteMemory(
            @Parameter(description = "记忆ID") @PathVariable String id) {
        log.info("[MemoryController] 删除记忆: id={}", id);

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
                log.warn("[MemoryController] 元数据删除返回false: id={}", id);
            }

            // 3. 从向量存储删除（尽力删除，不影响整体结果）
            try {
                if (vectorStore != null) {
                    vectorStore.delete(VECTOR_COLLECTION, List.of(id));
                }
            } catch (Exception e) {
                log.warn("[MemoryController] 向量存储删除失败: id={}, error={}", id, e.getMessage());
            }

            // 4. 从图存储删除（尽力删除，不影响整体结果）
            try {
                if (graphStore != null) {
                    graphStore.delete(id);
                }
            } catch (Exception e) {
                log.warn("[MemoryController] 图存储删除失败: id={}, error={}", id, e.getMessage());
            }

            log.info("[MemoryController] 记忆删除成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok("删除成功", null));
        } catch (Exception e) {
            log.error("[MemoryController] 删除记忆异常: id={}", id, e);
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
    @PostMapping("/batch-delete")
    @Operation(summary = "批量删除记忆", description = "批量从所有存储层删除记忆（最多100条）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDeleteMemories(
            @Valid @RequestBody BatchDeleteRequest request) {
        log.info("[MemoryController] 批量删除记忆: count={}", request.getIds().size());

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
                        log.warn("[MemoryController] 批量删除-向量存储删除失败: id={}", id);
                    }

                    // 从图存储删除（尽力）
                    try {
                        if (graphStore != null) {
                            graphStore.delete(id);
                        }
                    } catch (Exception e) {
                        log.warn("[MemoryController] 批量删除-图存储删除失败: id={}", id);
                    }

                    if (deleted) {
                        deletedIds.add(id);
                    } else {
                        failedIds.add(id);
                    }
                } catch (Exception e) {
                    log.warn("[MemoryController] 批量删除-删除失败: id={}, error={}", id, e.getMessage());
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

            log.info("[MemoryController] 批量删除完成: deleted={}, failed={}",
                    deletedIds.size(), failedIds.size());
            return ResponseEntity.ok(ApiResponse.ok("批量删除完成", responseData));
        } catch (Exception e) {
            log.error("[MemoryController] 批量删除异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("批量删除失败: " + e.getMessage()));
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 执行语义搜索
     *
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param searchText 搜索文本
     * @param topK      返回数量
     * @param offset    偏移量
     * @return 搜索结果Map
     */
    private Map<String, Object> executeSemanticSearch(String userId, String agentId,
                                                       String searchText, int topK, int offset) {
        SearchQuery query = SearchQuery.builder()
                .text(searchText)
                .userId(userId)
                .agentId(agentId)
                .topK(topK)
                .threshold(0.3) // 降低阈值以获取更多结果
                .build();

        List<SearchResult> searchResults = retrievalService.search(query);

        // 应用偏移量
        if (offset > 0 && offset < searchResults.size()) {
            searchResults = searchResults.subList(offset, searchResults.size());
        } else if (offset >= searchResults.size()) {
            searchResults = Collections.emptyList();
        }

        // 转换为Memory对象
        List<Memory> memories = searchResults.stream()
                .map(this::buildMemoryFromSearchResult)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memories", memories);
        result.put("total", searchResults.size());
        result.put("searchText", searchText);
        result.put("userId", userId);
        result.put("agentId", agentId);

        return result;
    }

    /**
     * 执行列表查询
     *
     * @param userId  用户ID
     * @param agentId 智能体ID
     * @param limit   返回数量
     * @param offset  偏移量
     * @return 查询结果Map
     */
    private Map<String, Object> executeListQuery(String userId, String agentId,
                                                  int limit, int offset) {
        // 构建过滤条件
        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", userId);
        if (agentId != null && !agentId.isBlank()) {
            filters.put("agent_id", agentId);
        }

        // 查询总数
        long totalCount = metadataStore.count(METADATA_TABLE, filters);

        // 查询记录（带分页）
        List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, limit, offset);

        if (records == null) {
            records = Collections.emptyList();
        }

        // 转换为Memory对象
        List<Memory> memories = records.stream()
                .map(this::buildMemoryFromRecord)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memories", memories);
        result.put("total", totalCount);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("userId", userId);
        result.put("agentId", agentId);

        return result;
    }

    /**
     * 从MetadataRecord构建Memory对象
     *
     * @param record 元数据记录
     * @return Memory对象
     */
    private Memory buildMemoryFromRecord(MetadataRecord record) {
        return Memory.builder()
                .id(record.getId())
                .text(record.getContent())
                .userId(record.getUserId())
                .agentId(record.getAgentId())
                .importance(record.getImportance())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .status("ACTIVE")
                .build();
    }

    /**
     * 从SearchResult构建Memory对象
     *
     * @param result 搜索结果
     * @return Memory对象
     */
    private Memory buildMemoryFromSearchResult(SearchResult result) {
        Memory.MemoryBuilder builder = Memory.builder()
                .id(result.getId())
                .text(result.getText())
                .importance(0.5)
                .status("ACTIVE");

        // 从metadata提取额外信息
        if (result.getMetadata() != null) {
            Map<String, Object> meta = result.getMetadata();
            if (meta.containsKey("userId")) {
                builder.userId(meta.get("userId").toString());
            }
            if (meta.containsKey("agentId")) {
                builder.agentId(meta.get("agentId").toString());
            }
            if (meta.containsKey("importance")) {
                builder.importance(((Number) meta.get("importance")).doubleValue());
            }
            if (meta.containsKey("createdAt")) {
                try {
                    builder.createdAt(Instant.parse(meta.get("createdAt").toString()));
                } catch (Exception ignored) {}
            }
            if (meta.containsKey("updatedAt")) {
                try {
                    builder.updatedAt(Instant.parse(meta.get("updatedAt").toString()));
                } catch (Exception ignored) {}
            }
        }

        return builder.build();
    }
}
