package com.lava.ai_gateway.dto;

import java.time.LocalDateTime;

public record SessionDTO(
        String sessionId,
        String title,
        String model,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
