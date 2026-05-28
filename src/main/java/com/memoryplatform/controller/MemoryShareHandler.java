package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.model.AuditLog;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryVersion;
import com.memoryplatform.service.AuditLogService;
import com.memoryplatform.service.MemoryVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 记忆版本管理处理器
 * <p>
 * 处理记忆的版本历史和回滚操作：
 * <ul>
 *   <li>GET /api/memories/{memoryId}/history - 获取记忆版本历史</li>
 *   <li>POST /api/memories/{memoryId}/rollback/{version} - 回滚记忆到指定版本</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryShareHandler {

    private final MemoryVersionService versionService;
    private final AuditLogService auditLogService;

    // ==================== 版本历史 API ====================

    /**
     * 获取记忆的版本历史
     *
     * @param memoryId 记忆ID
     * @param limit    返回的最大版本数（默认10）
     * @return 版本历史列表
     */
    public ResponseEntity<ApiResponse<List<MemoryVersion>>> getMemoryHistory(String memoryId, int limit) {
        try {
            List<MemoryVersion> versions = versionService.getVersions(memoryId, limit);
            return ResponseEntity.ok(ApiResponse.success(versions));
        } catch (Exception e) {
            log.error("[MemoryShareHandler] 获取记忆历史失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("获取历史失败: " + e.getMessage()));
        }
    }

    /**
     * 回滚记忆到指定版本
     *
     * @param memoryId 记忆ID
     * @param version  目标版本号
     * @return 回滚后的记忆
     */
    public ResponseEntity<ApiResponse<Memory>> rollbackMemory(String memoryId, int version) {
        try {
            Memory rolledBack = versionService.rollback(memoryId, version);
            auditLogService.log("ROLLBACK", null, null, memoryId,
                    Map.of("targetVersion", version), null);
            return ResponseEntity.ok(ApiResponse.success(rolledBack));
        } catch (Exception e) {
            log.error("[MemoryShareHandler] 回滚失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("回滚失败: " + e.getMessage()));
        }
    }
}
