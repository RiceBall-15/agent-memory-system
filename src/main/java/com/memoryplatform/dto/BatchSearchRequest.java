package com.memoryplatform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量搜索请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSearchRequest {

    /** 批量搜索查询列表（最多10个） */
    @NotEmpty(message = "查询列表不能为空")
    @Size(max = 10, message = "批量搜索不能超过10个查询")
    @Valid
    private List<SearchRequest> queries;
}
