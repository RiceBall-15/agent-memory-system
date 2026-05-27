package com.memoryplatform.model;

import java.time.Instant;

/**
 * 消息 - 对话消息
 */
public class Message {
    private final String role;
    private final String content;
    private final Instant timestamp;

    public Message(String role, String content) {
        this(role, content, Instant.now());
    }

    public Message(String role, String content, Instant timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
}
