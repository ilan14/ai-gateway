package com.lava.ai_gateway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lava.ai_gateway.model.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 桩实现：不调用任何真实模型，直接返回固定的 "ok" 响应。
 * 用于验证整体链路的连通性。
 */
@Component
public class StubModelProvider implements ModelProvider {

    private final ObjectMapper objectMapper;

    public StubModelProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean supportsModel(String model) {
        return "stub".equals(model);
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        String modelName = request.model() != null ? request.model() : "stub";
        long now = System.currentTimeMillis() / 1000;

        StreamChunk content = new StreamChunk(
                "stub-1", "chat.completion.chunk", now, modelName,
                List.of(new StreamChoice(0, new Delta(null, "ok"), null))
        );
        StreamChunk done = new StreamChunk(
                "stub-1", "chat.completion.chunk", now, modelName,
                List.of(new StreamChoice(0, new Delta(null, null), "stop"))
        );

        return Flux.just(toJson(content), toJson(done), "[DONE]");
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        String modelName = request.model() != null ? request.model() : "stub";
        ChatResponse response = new ChatResponse(
                "stub-1", "chat.completion",
                System.currentTimeMillis() / 1000,
                modelName,
                List.of(new Choice(0, new Message("assistant", "ok"), "stop")),
                new Usage(0, 0, 0)
        );
        return Mono.just(response);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }
}
