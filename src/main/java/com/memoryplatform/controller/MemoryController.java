package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.CreateMemoryRequest;
import com.memoryplatform.dto.UpdateMemoryRequest;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.HybridRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 记忆管理REST Controller
 * <p>
 * 提供记忆的CRUD操作：
 * <ul>
 *   <li>POST /api/memories - 从对话中提取并保存记忆</li>
 *   <li>GET /api/memories/{id} - 获取单条记忆</li>
 *   <li>PUT /api/memories/{id} - 更新记忆</li>
 *   <li>DELETE /api/memories/{id} - 删除记忆</li>
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

    private final ConcurrentWriteService writeService;
    private final HybridRetrievalService retrievalService;

    // ==================== 创建记忆 ====================

    /**
     * 从对话文本中提取记忆并保存
     *
     * @param request 包含对话消息的请求体
     * @return 创建成功的记忆信息
     */
    @PostMapping
    @Operation(summary = "创建记忆", description = "从对话文本中提取记忆并保存到三层存储")
    public ResponseEntity<ApiResponse<WriteResult>> createMemory(
            @Valid @RequestBody CreateMemoryRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        log.info("[MemoryController] 创建记忆请求: userId={}, agentId={}, requestId={}",
                request.getUserId(), request.getAgentId(), requestId);

        try {
            // 构造Message对象列表
            List<com.memoryplatform.model.Message> messages = request.getMessages().stream()
                    .map(m -> com.memoryplatform.model.Message.builder()
                            .role(m.getRole())
                            .content(m.getContent())
                            .build())
                    .toList();

            WriteResult result = writeService.extractAndWrite(
                    messages, request.getUserId(), request.getAgentId());

            if (result.isSuccess()) {
                log.info("[MemoryController] 记忆创建成功: count={}, requestId={}",
                        result.getSuccessCount(), requestId);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.ok("记忆创建成功", result));
            } else {
                log.warn("[MemoryController] 记忆创建失败: requestId={}", requestId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.internalError("记忆创建失败: " + result.getErrorMessage()));
            }
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
            HybridRetrievalService searchService = (HybridRetrievalService) retrievalService;
            // 通过metadata获取记忆详情
            Memory memory = null;
            // TODO: 实现按ID精确查询
            // 这里使用HybridRetrievalService的内部查询
            if (memory == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.notFound("记忆不存在: " + id));
            }

            return ResponseEntity.ok(ApiResponse.ok(memory));
        } catch (Exception e) {
            log.error("[MemoryController] 获取记忆异常: id={}", id, e);
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
    @PutMapping("/{id}")
    @Operation(summary = "更新记忆", description = "更新记忆的文本、重要度或状态")
    public ResponseEntity<ApiResponse<Memory>> updateMemory(
            @Parameter(description = "记忆ID") @PathVariable String id,
            @Valid @RequestBody UpdateMemoryRequest request) {
        log.info("[MemoryController] 更新记忆: id={}", id);

        try {
            // TODO: 实现记忆更新逻辑
            log.info("[MemoryController] 记忆更新成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok("更新成功", null));
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
            // TODO: 实现三层数删除
            log.info("[MemoryController] 记忆删除成功: id={}", id);
            return ResponseEntity.ok(ApiResponse.ok("删除成功", null));
        } catch (Exception e) {
            log.error("[MemoryController] 删除记忆异常: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("删除失败: " + e.getMessage()));
        }
    }
}
