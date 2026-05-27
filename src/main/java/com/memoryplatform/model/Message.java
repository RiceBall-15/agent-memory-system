package com.memoryplatform.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 消息 - 对话消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private String content;

    @JsonProperty("timestamp")
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 兼容旧代码的2参数构造器，timestamp 使用当前时间。
     */
    public Message(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = Instant.now();
    }
}
