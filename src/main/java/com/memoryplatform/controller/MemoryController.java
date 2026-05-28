package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.BatchDeleteRequest;
import com.memoryplatform.dto.CreateMemoryRequest;
import com.memoryplatform.dto.SearchRequest;
import com.memoryplatform.dto.UpdateMemoryRequest;
import com.memoryplatform.model.AuditLog;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryType;
import com.memoryplatform.model.MemoryVersion;
import com.memoryplatform.model.WriteResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理REST Controller（重构版）
 * <p>
 * 作为API门面层，将请求委托给专用处理器：
 * <ul>
 *   <li>{@link MemoryCrudHandler} - CRUD操作</li>
 *   <li>{@link MemorySearchHandler} - 搜索操作</li>
 *   <li>{@link MemoryShareHandler} - 版本管理操作</li>
 *   <li>{@link MemoryStatsHandler} - 审计日志操作</li>
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

    private final MemoryCrudHandler crudHandler;
    private final MemorySearchHandler searchHandler;
    private final MemoryShareHandler shareHandler;
    private final MemoryStatsHandler statsHandler;

    // ==================== CRUD操作（委托给MemoryCrudHandler） ====================

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
        return crudHandler.createMemory(request);
    }

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
        return crudHandler.getMemory(id);
    }

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
        return crudHandler.updateMemory(id, request);
    }

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
        return crudHandler.deleteMemory(id);
    }

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
        return crudHandler.batchDeleteMemories(request);
    }

    // ==================== 搜索操作（委托给MemorySearchHandler） ====================

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
     * @param userId     用户ID（必需）
     * @param agentId    智能体ID（可选）
     * @param searchText 搜索文本（可选，提供时触发语义搜索）
     * @param memoryType 记忆类型过滤（可选）
     * @param limit      返回数量（默认20，最大100）
     * @param offset     偏移量（默认0）
     * @return 记忆列表
     */
    @GetMapping
    @Operation(summary = "搜索/列表查询记忆", description = "支持语义搜索和列表查询")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMemories(
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "智能体ID") @RequestParam(required = false) String agentId,
            @Parameter(description = "搜索文本（可选）") @RequestParam(required = false) String searchText,
            @Parameter(description = "记忆类型过滤（可选）") @RequestParam(required = false) MemoryType memoryType,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "偏移量") @RequestParam(defaultValue = "0") int offset) {
        return searchHandler.listMemories(userId, agentId, searchText, memoryType, limit, offset);
    }

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
        return searchHandler.searchMemories(request);
    }

    // ==================== 版本管理操作（委托给MemoryShareHandler） ====================

    /**
     * 获取记忆的版本历史
     *
     * @param memoryId 记忆ID
     * @param limit    返回的最大版本数（默认10）
     * @return 版本历史列表
     */
    @GetMapping("/{memoryId}/history")
    @Operation(summary = "获取记忆版本历史", description = "获取指定记忆的所有历史变更版本")
    public ResponseEntity<ApiResponse<List<MemoryVersion>>> getMemoryHistory(
            @PathVariable String memoryId,
            @RequestParam(defaultValue = "10") int limit) {
        return shareHandler.getMemoryHistory(memoryId, limit);
    }

    /**
     * 回滚记忆到指定版本
     *
     * @param memoryId 记忆ID
     * @param version  目标版本号
     * @return 回滚后的记忆
     */
    @PostMapping("/{memoryId}/rollback/{version}")
    @Operation(summary = "回滚记忆版本", description = "将记忆回滚到指定的历史版本")
    public ResponseEntity<ApiResponse<Memory>> rollbackMemory(
            @PathVariable String memoryId,
            @PathVariable int version) {
        return shareHandler.rollbackMemory(memoryId, version);
    }

    // ==================== 审计日志操作（委托给MemoryStatsHandler） ====================

    /**
     * 查询审计日志
     *
     * @param memoryId 按记忆ID过滤（可选）
     * @param userId   按用户ID过滤（可选）
     * @param limit    返回的最大日志数（默认50）
     * @return 审计日志列表
     */
    @GetMapping("/audit-logs")
    @Operation(summary = "查询审计日志", description = "查询系统审计日志，支持按记忆ID和用户ID过滤")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLogs(
            @RequestParam(required = false) String memoryId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit) {
        return statsHandler.getAuditLogs(memoryId, userId, limit);
    }
}
