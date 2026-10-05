package com.lava.ai_gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ChatRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("context_mode") @JsonInclude(JsonInclude.Include.NON_NULL) String contextMode
) {
    public ChatRequest(String model, List<Message> messages, Boolean stream, Double temperature, Integer maxTokens) {
        this(model, messages, stream, temperature, maxTokens, null);
    }
}
