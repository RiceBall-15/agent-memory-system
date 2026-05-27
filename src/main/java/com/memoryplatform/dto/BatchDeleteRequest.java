package com.memoryplatform.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量删除记忆请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchDeleteRequest {

    /** 要删除的记忆ID列表（最多100个） */
    @NotEmpty(message = "ID列表不能为空")
    @Size(max = 100, message = "批量删除不能超过100个ID")
    private List<String> ids;
}
