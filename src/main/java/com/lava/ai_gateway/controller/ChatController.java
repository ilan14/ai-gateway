package com.lava.ai_gateway.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.Message;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.router.ModelRouter;
import com.lava.ai_gateway.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ModelRouter router;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    public ChatController(ModelRouter router,
                          ObjectMapper objectMapper,
                          SessionService sessionService) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
    }

    @Operation(
            summary = "Chat Completions",
            description = "支持流式（stream=true）和非流式两种模式。" +
                    "可通过 X-Session-Id 请求头关联会话，网关自动持久化对话历史。"
    )
    @PostMapping("/chat/completions")
    public Mono<Void> chatCompletions(
            @RequestBody ChatRequest request,
            ServerHttpResponse response,
            @RequestHeader(value = "X-Session-Id", required = false) String rawSessionId) {

        String sessionId = (rawSessionId != null && !rawSessionId.isBlank())
                ? rawSessionId
                : UUID.randomUUID().toString();

        String model = request.model() != null ? request.model() : "stub";
        String userContent = extractLastUserContent(request);

        log.info("POST /v1/chat/completions sessionId={}, model={}, stream={}, messages={}",
                sessionId, model, request.stream(), request.messages().size());

        // 持久化用户消息（blocking I/O 放到 boundedElastic 线程池）
        Mono<Void> saveUser = Mono.fromRunnable(() -> {
            sessionService.getOrCreate(sessionId, model);
            if (userContent != null) {
                sessionService.appendUserMessage(sessionId, userContent);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();

        ModelProvider provider = router.route(request.model());

        return saveUser.then(
                Boolean.TRUE.equals(request.stream())
                        ? handleStream(request, response, provider, sessionId)
                        : handleNonStream(request, response, provider, sessionId)
        );
    }

    // ── 流式响应 ──────────────────────────────────────────────────────────────

    private Mono<Void> handleStream(ChatRequest request, ServerHttpResponse response,
                                    ModelProvider provider, String sessionId) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);

        StringBuilder assistantContent = new StringBuilder();

        Flux<DataBuffer> body = provider.streamChat(request)
                .doOnNext(chunk -> accumulateContent(chunk, assistantContent))
                .doOnComplete(() -> saveAssistantAsync(sessionId, assistantContent.toString()))
                .map(chunk -> "data: " + chunk + "\n\n")
                .map(s -> wrap(response, s));

        return response.writeWith(body);
    }

    // ── 非流式响应 ─────────────────────────────────────────────────────────────

    private Mono<Void> handleNonStream(ChatRequest request, ServerHttpResponse response,
                                       ModelProvider provider, String sessionId) {
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        return provider.chat(request)
                .flatMap(resp -> {
                    // 持久化 AI 回复
                    String content = resp.choices() != null && !resp.choices().isEmpty()
                            ? resp.choices().get(0).message().content()
                            : "";
                    saveAssistantAsync(sessionId, content);

                    try {
                        byte[] bytes = objectMapper.writeValueAsBytes(resp);
                        DataBuffer buffer = response.bufferFactory().wrap(bytes);
                        return response.writeWith(Mono.just(buffer));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                });
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────────

    /** 从流式 chunk JSON 中提取 delta.content 并累积到 buffer */
    private void accumulateContent(String chunk, StringBuilder buffer) {
        if ("[DONE]".equals(chunk)) {
            return;
        }
        try {
            var json = objectMapper.readTree(chunk);
            String content = json.at("/choices/0/delta/content").asText("");
            buffer.append(content);
        } catch (Exception ignored) {
            // 忽略无法解析的帧
        }
    }

    /** 异步保存 AI 回复，不阻塞主流程 */
    private void saveAssistantAsync(String sessionId, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Mono.fromRunnable(() -> sessionService.appendAssistantMessage(sessionId, content))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        e -> log.error("Failed to save assistant message → sessionId={}", sessionId, e)
                );
    }

    /** 取请求中最后一条 user 消息的内容，作为本轮用户输入 */
    private String extractLastUserContent(ChatRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return null;
        }

        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message msg = request.messages().get(i);
            if ("user".equals(msg.role())) {
                return msg.content();
            }
        }
        return null;
    }

    private DataBuffer wrap(ServerHttpResponse response, String text) {
        return response.bufferFactory().wrap(text.getBytes(StandardCharsets.UTF_8));
    }
}
