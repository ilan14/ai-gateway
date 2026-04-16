package com.lava.ai_gateway.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.router.ModelRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容的 Chat Completions 接口。
 * 根据请求中的 stream 字段分别处理流式和非流式响应。
 */
@Tag(name = "Chat", description = "OpenAI 兼容的对话接口")
@RestController
@RequestMapping("/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ModelRouter router;
    private final ObjectMapper objectMapper;

    public ChatController(ModelRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Chat Completions",
            description = "支持流式（stream=true）和非流式两种模式。" +
                    "流式响应使用 SSE 格式，兼容 OpenAI 客户端工具。"
    )
    @PostMapping("/chat/completions")
    public Mono<Void> chatCompletions(@RequestBody ChatRequest request, ServerHttpResponse response) {
        log.info("POST /v1/chat/completions model={}, stream={}, messages={}",
                request.model(), request.stream(), request.messages().size());

        ModelProvider provider = router.route(request.model());

        if (Boolean.TRUE.equals(request.stream())) {
            response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            Flux<DataBuffer> body = provider.streamChat(request)
                    .map(chunk -> "data: " + chunk + "\n\n")
                    .map(s -> wrap(response, s));
            return response.writeWith(body);
        }

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return provider.chat(request)
                .flatMap(resp -> {
                    try {
                        byte[] bytes = objectMapper.writeValueAsBytes(resp);
                        DataBuffer buffer = response.bufferFactory().wrap(bytes);
                        return response.writeWith(Mono.just(buffer));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                });
    }

    private DataBuffer wrap(ServerHttpResponse response, String text) {
        return response.bufferFactory().wrap(text.getBytes(StandardCharsets.UTF_8));
    }
}
