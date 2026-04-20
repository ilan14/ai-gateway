package com.lava.ai_gateway.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageEntity {
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
