package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.SearchRequest;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.service.HybridRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索REST Controller
 * <p>
 * 提供记忆搜索功能：
 * <ul>
 *   <li>POST /api/search - 混合检索（向量+图+关键词）</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "记忆搜索", description = "混合检索API")
public class SearchController {

    private final HybridRetrievalService retrievalService;

    /**
     * 混合检索
     * <p>
     * 结合向量相似度、图遍历和BM25关键词搜索，
     * 通过WeightedScorer进行多维度打分排序。
     * </p>
     *
     * @param request 搜索请求（文本 + userId + topK + 阈值）
     * @return 搜索结果列表
     */
    @PostMapping
    @Operation(summary = "混合检索", description = "向量+图+关键词混合检索，返回排序后的记忆列表")
    public ResponseEntity<ApiResponse<List<SearchResult>>> search(
            @Valid @RequestBody SearchRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        log.info("[SearchController] 混合检索请求: userId={}, topK={}, requestId={}",
                request.getUserId(), request.getTopK(), requestId);

        long startTime = System.currentTimeMillis();

        try {
            List<SearchResult> results = retrievalService.search(
                    request.getText(),
                    request.getUserId(),
                    request.getTopK(),
                    request.getThreshold(),
                    request.getFilters());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[SearchController] 检索完成: count={}, elapsed={}ms, requestId={}",
                    results.size(), elapsed, requestId);

            return ResponseEntity.ok(ApiResponse.ok(results));
        } catch (Exception e) {
            log.error("[SearchController] 检索异常: requestId={}", requestId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.internalError("检索失败: " + e.getMessage()));
        }
    }
}
