package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容的 Chat Completions 接口。
 *
 * 通过可选的 X-Session-Id 请求头关联会话，网关负责将每轮对话持久化到 MySQL/Redis。
 * 不传 X-Session-Id 时自动生成新会话 ID，仍会持久化，方便后续查询。
 */
@Tag(name = "Chat", description = "OpenAI 兼容的对话接口")
@RestController
@RequestMapping("/v1")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Chat Completions",
            description = "支持流式（stream=true）和非流式两种模式。" +
                    "可通过 X-Session-Id 请求头关联会话，网关自动持久化对话历史。响应头 X-Session-Id 返回会话 ID；非法 UUID 返回 400，会话不存在返回 404。" +
                    "model=mock 时绕过会话和存储，忽略 X-Session-Id，也不返回该响应头。"
    )
    @PostMapping("/chat/completions")
    public Mono<Void> chatCompletions(
            @RequestBody ChatRequest request,
            ServerHttpResponse response,
            @RequestHeader(value = "X-Session-Id", required = false) String rawSessionId) {
        validateRequest(request);

        if (chatService.isMock(request)) {
            return writeResponse(request, response, null);
        }

        return chatService.prepareSessionId(rawSessionId).flatMap(sessionId -> {
            response.getHeaders().set("X-Session-Id", sessionId);
            return writeResponse(request, response, sessionId);
        });
    }

    public void validateRequest(ChatRequest request) {
        String mode = request.contextMode();
        if (mode != null && !"client".equals(mode) && !"server".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context_mode must be client or server");
        }
        if ("server".equals(mode) && (request.messages() == null || request.messages().size() != 1
                || request.messages().get(0) == null
                || !"user".equals(request.messages().get(0).role())
                || request.messages().get(0).content() == null
                || request.messages().get(0).content().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "server context mode requires exactly one non-empty user message");
        }
    }

    private Mono<Void> writeResponse(ChatRequest request, ServerHttpResponse response, String sessionId) {
        if (Boolean.TRUE.equals(request.stream())) {
            response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            Flux<DataBuffer> body = chatService.streamChat(request, sessionId)
                    .map(chunk -> "data: " + chunk + "\n\n")
                    .map(chunk -> response.bufferFactory().wrap(chunk.getBytes(StandardCharsets.UTF_8)));
            return response.writeWith(body);
        }

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return chatService.chat(request, sessionId)
                .flatMap(resp -> {
                    try {
                        byte[] bytes = objectMapper.writeValueAsBytes(resp);
                        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
                    } catch (JacksonException e) {
                        return Mono.error(e);
                    }
                });
    }
}
