package com.memoryplatform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量创建记忆请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchCreateRequest {

    /** 批量记忆列表（最多100条） */
    @NotEmpty(message = "记忆列表不能为空")
    @Size(max = 100, message = "批量创建不能超过100条")
    private List<BatchMemoryItem> items;

    /** 用户ID（全局，可被item覆盖） */
    @NotBlank(message = "userId不能为空")
    private String userId;

    /** 智能体ID（可选） */
    private String agentId;

    /**
     * 批量创建单条记忆项
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchMemoryItem {
        /** 记忆文本 */
        @NotBlank(message = "记忆文本不能为空")
        private String text;

        /** 重要度 */
        private Double importance;
    }
}
