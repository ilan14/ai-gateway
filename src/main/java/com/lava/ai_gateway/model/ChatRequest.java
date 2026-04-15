package com.lava.ai_gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens
) {}
