package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.model.AuditLog;
import com.memoryplatform.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆审计日志处理器
 * <p>
 * 处理记忆相关的审计日志查询操作：
 * <ul>
 *   <li>GET /api/audit-logs - 查询审计日志</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryStatsHandler {

    private final AuditLogService auditLogService;

    // ==================== 审计日志 API ====================

    /**
     * 查询审计日志
     *
     * @param memoryId 按记忆ID过滤（可选）
     * @param userId   按用户ID过滤（可选）
     * @param limit    返回的最大日志数（默认50）
     * @return 审计日志列表
     */
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLogs(
            String memoryId, String userId, int limit) {
        try {
            List<AuditLog> logs;
            if (memoryId != null) {
                logs = auditLogService.queryByMemoryId(memoryId, limit);
            } else if (userId != null) {
                logs = auditLogService.queryByUserId(userId, limit);
            } else {
                logs = auditLogService.getRecentLogs(limit);
            }
            return ResponseEntity.ok(ApiResponse.success(logs));
        } catch (Exception e) {
            log.error("[MemoryStatsHandler] 查询审计日志失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("查询日志失败: " + e.getMessage()));
        }
    }
}
