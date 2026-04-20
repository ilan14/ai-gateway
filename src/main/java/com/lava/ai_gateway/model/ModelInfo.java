package com.lava.ai_gateway.model;

public record ModelInfo(
        String id,
        String object,
        long created,
        String ownedBy
) {
    public ModelInfo(String id, String ownedBy) {
        this(id, "model", System.currentTimeMillis() / 1000L, ownedBy);
    }
}
