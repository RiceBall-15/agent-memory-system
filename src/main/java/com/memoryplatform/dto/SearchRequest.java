package com.memoryplatform.dto;

import com.memoryplatform.model.MemoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 搜索请求DTO
 *
 * <pre>{@code
 * {
 *   "text": "用户的偏好是什么",
 *   "userId": "user-123",
 *   "topK": 10,
 *   "threshold": 0.5,
 *   "filters": {"agentId": "agent-456"}
 * }
 * }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {

    /** 搜索文本 */
    @NotBlank(message = "搜索文本不能为空")
    private String text;

    /** 用户ID */
    @NotBlank(message = "userId不能为空")
    private String userId;

    /** 智能体ID（可选） */
    private String agentId;

    /** 返回数量，默认10 */
    @Min(value = 1, message = "topK至少为1")
    private int topK = 10;

    /** 最低分数阈值，默认0.5 */
    private double threshold = 0.5;

    /** 记忆类型过滤（可选），不设置则搜索所有类型 */
    @Schema(description = "记忆类型过滤（可选），不设置则搜索所有类型",
            allowableValues = {"SEMANTIC", "EPISODIC", "PROCEDURAL", "WORKING"})
    private MemoryType memoryType;

    /** 附加过滤条件 */
    private Map<String, Object> filters;
}
