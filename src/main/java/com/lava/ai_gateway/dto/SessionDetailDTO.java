package com.lava.ai_gateway.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SessionDetailDTO(
        String sessionId,
        String title,
        String model,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<MessageDTO> messages
) {}
