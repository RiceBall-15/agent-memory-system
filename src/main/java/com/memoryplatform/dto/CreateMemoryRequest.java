package com.memoryplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建记忆请求DTO
 * <p>
 * 从对话文本中提取记忆的请求体。
 * </p>
 *
 * <pre>{@code
 * {
 *   "messages": [{"role":"user","content":"..."}],
 *   "userId": "user-123",
 *   "agentId": "agent-456"
 * }
 * }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateMemoryRequest {

    /** 对话消息列表 */
    @NotEmpty(message = "消息列表不能为空")
    @Size(max = 50, message = "消息数量不能超过50条")
    private List<MessageDto> messages;

    /** 用户ID */
    @NotBlank(message = "userId不能为空")
    private String userId;

    /** 智能体ID（可选） */
    private String agentId;

    /**
     * 消息DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageDto {
        /** 角色: user, assistant, system */
        @NotBlank(message = "消息角色不能为空")
        private String role;

        /** 消息内容 */
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 10000, message = "消息内容不能超过10000个字符")
        private String content;
    }
}
