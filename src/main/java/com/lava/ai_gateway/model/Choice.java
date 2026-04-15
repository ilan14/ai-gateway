package com.lava.ai_gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Choice(
        int index,
        Message message,
        @JsonProperty("finish_reason") String finishReason
) {}
