package com.lava.ai_gateway.model;

import java.util.List;

public record StreamChunk(
        String id,
        String object,
        long created,
        String model,
        List<StreamChoice> choices
) {}
