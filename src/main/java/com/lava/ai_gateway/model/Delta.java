package com.lava.ai_gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Delta(String role, String content) {}
