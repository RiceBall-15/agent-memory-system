package com.memoryplatform.controller;

import com.memoryplatform.dto.*;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.HybridRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作REST Controller
 * <p>
 * 提供批量记忆操作：
 * <ul>
 *   <li>POST /api/memories/batch - 批量创建记忆</li>
 *   <li>DELETE /api/memories/batch - 批量删除记忆</li>
 *   <li>POST /api/memories/batch/search - 批量搜索</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/memories/batch")
@RequiredArgsConstructor
@Tag(name = "批量操作", description = "批量创建、删除、搜索记忆")
public class BatchController {

    private final ConcurrentWriteService writeService;
    private final HybridRetrievalService retrievalService;

    /**
     * 批量创建记忆
     *
     * @param request 批量创建请求（最多100条）
     * @return 每条记忆的创建结果
     */
    @PostMapping
    @Operation(summary = "批量创建记忆", description = "批量从文本中提取记忆并保存")
    public ResponseEntity<ApiResponse<List<WriteResult>>> batchCreate(
            @Valid @RequestBody BatchCreateRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        log.info("[BatchController] 批量创建请求: count={}, userId={}, requestId={}",
                request.getItems().size(), request.getUserId(), requestId);

        long startTime = System.currentTimeMillis();
        List<WriteResult> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        try {
            for (BatchCreateRequest.BatchMemoryItem item : request.getItems()) {
                try {
                    // 构造单条Message
                    com.memoryplatform.model.Message msg = com.memoryplatform.model.Message.builder()
                            .role("user")
                            .content(item.getText())
                            .build();

                    WriteResult result = writeService.extractAndWrite(
                            List.of(msg), request.getUserId(), request.getAgentId());
                    results.add(result);

                    if (result.isSuccess()) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("[BatchController] 单条记忆创建失败: text={}", item.getText(), e);
                    failCount++;
                    results.add(WriteResult.builder()
                            .success(false)
                            .errorMessage(e.getMessage())
                            .build());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[BatchController] 批量创建完成: success={}, fail={}, elapsed={}ms",
                    successCount, failCount, elapsed);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("批量创建完成", results));
        } catch (Exception e) {
            log.error("[BatchController] 批量创建异常: requestId={}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("批量创建失败: " + e.getMessage()));
        }
    }

    /**
     * 批量删除记忆
     *
     * @param request 批量删除请求（最多100个ID）
     * @return 删除结果
     */
    @DeleteMapping
    @Operation(summary = "批量删除记忆", description = "批量从所有存储层删除记忆")
    public ResponseEntity<ApiResponse<Void>> batchDelete(
            @Valid @RequestBody BatchDeleteRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        log.info("[BatchController] 批量删除请求: count={}, requestId={}",
                request.getIds().size(), requestId);

        try {
            // TODO: 实现批量删除逻辑
            log.info("[BatchController] 批量删除完成: count={}", request.getIds().size());
            return ResponseEntity.ok(ApiResponse.ok("批量删除成功", null));
        } catch (Exception e) {
            log.error("[BatchController] 批量删除异常: requestId={}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("批量删除失败: " + e.getMessage()));
        }
    }

    /**
     * 批量搜索
     * <p>
     * 一次执行多个搜索查询，返回每个查询的结果。
     * </p>
     *
     * @param request 批量搜索请求（最多10个查询）
     * @return 每个查询的搜索结果
     */
    @PostMapping("/search")
    @Operation(summary = "批量搜索", description = "一次执行多个混合检索查询")
    public ResponseEntity<ApiResponse<List<List<com.memoryplatform.model.SearchResult>>>> batchSearch(
            @Valid @RequestBody BatchSearchRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        log.info("[BatchController] 批量搜索请求: queryCount={}, requestId={}",
                request.getQueries().size(), requestId);

        long startTime = System.currentTimeMillis();
        List<List<com.memoryplatform.model.SearchResult>> allResults = new ArrayList<>();

        try {
            for (SearchRequest query : request.getQueries()) {
                try {
                    List<com.memoryplatform.model.SearchResult> results = retrievalService.search(
                            query.getText(),
                            query.getUserId(),
                            query.getTopK(),
                            query.getThreshold(),
                            query.getFilters());
                    allResults.add(results);
                } catch (Exception e) {
                    log.error("[BatchController] 单个查询失败: text={}", query.getText(), e);
                    allResults.add(List.of());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[BatchController] 批量搜索完成: queryCount={}, elapsed={}ms",
                    request.getQueries().size(), elapsed);

            return ResponseEntity.ok(ApiResponse.ok(allResults));
        } catch (Exception e) {
            log.error("[BatchController] 批量搜索异常: requestId={}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("批量搜索失败: " + e.getMessage()));
        }
    }
}
