package com.lava.ai_gateway.dto;

import java.time.LocalDateTime;

public record MessageDTO(
        String role,
        String content,
        LocalDateTime createdAt
) {}
