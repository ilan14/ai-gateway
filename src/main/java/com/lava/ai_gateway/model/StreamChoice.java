package com.lava.ai_gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamChoice(
        int index,
        Delta delta,
        @JsonProperty("finish_reason") String finishReason
) {}
