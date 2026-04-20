package com.lava.ai_gateway.model;

import java.util.List;

public record ModelListResponse(String object, List<ModelInfo> data) {
    public ModelListResponse(List<ModelInfo> data) {
        this("list", data);
    }
}
