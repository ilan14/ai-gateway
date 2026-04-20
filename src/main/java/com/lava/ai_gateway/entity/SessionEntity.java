package com.lava.ai_gateway.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionEntity {
    private Long id;
    private String sessionId;
    private String title;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
