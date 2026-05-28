package com.memoryplatform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一API响应包装
 *
 * @param <T> 响应数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 是否成功 */
    private boolean success;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 错误详情 */
    private ErrorDetail error;

    // ==================== 静态工厂方法 ====================

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "success", data, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "success", data, null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, "created", data, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(false, message, null, new ErrorDetail(code, message));
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, new ErrorDetail(500, message));
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(400, message);
    }

    public static <T> ApiResponse<T> internalError(String message) {
        return error(500, message);
    }

    /**
     * 错误详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        private int code;
        private String message;
    }
}
