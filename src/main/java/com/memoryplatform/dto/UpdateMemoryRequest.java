package com.memoryplatform.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新记忆请求DTO
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemoryRequest {

    /** 记忆文本 */
    @Size(max = 10000, message = "文本内容不能超过10000个字符")
    private String text;

    /** 重要度 (0.0 ~ 1.0) */
    private Double importance;

    /** 状态: ACTIVE, ARCHIVED, DELETED */
    private String status;
}
