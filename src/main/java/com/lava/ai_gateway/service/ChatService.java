package com.lava.ai_gateway.service;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
import com.lava.ai_gateway.model.Message;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.router.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** 聊天业务编排：模型路由、会话维护和回复持久化。 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ModelRouter router;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    public ChatService(ModelRouter router, ObjectMapper objectMapper, SessionService sessionService) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
    }

    public Flux<String> streamChat(ChatRequest request, String rawSessionId) {
        return Flux.defer(() -> {
            if (isMock(request)) {
                return router.route("mock").streamChat(request);
            }

            String sessionId = resolveSessionId(rawSessionId);
            ModelProvider provider = router.route(request.model());
            StringBuilder assistantContent = new StringBuilder();

            return saveUser(request, sessionId).thenMany(Flux.defer(() -> provider.streamChat(request)))
                    .doOnNext(chunk -> accumulateContent(chunk, assistantContent))
                    .doOnComplete(() -> saveAssistantAsync(sessionId, assistantContent.toString()));
        });
    }

    public Mono<ChatResponse> chat(ChatRequest request, String rawSessionId) {
        return Mono.defer(() -> {
            if (isMock(request)) {
                return router.route("mock").chat(request);
            }

            String sessionId = resolveSessionId(rawSessionId);
            ModelProvider provider = router.route(request.model());

            return saveUser(request, sessionId).then(Mono.defer(() -> provider.chat(request)))
                    .doOnNext(resp -> {
                        String content = resp.choices() != null && !resp.choices().isEmpty()
                                ? resp.choices().get(0).message().content()
                                : "";
                        saveAssistantAsync(sessionId, content);
                    });
        });
    }

    public boolean isMock(ChatRequest request) {
        return "mock".equals(request.model());
    }

    /** 在响应提交前确定会话 ID，数据库查询在阻塞任务线程执行。 */
    public Mono<String> prepareSessionId(String rawSessionId) {
        return Mono.fromCallable(() -> {
            String sessionId = resolveSessionId(rawSessionId);
            if (rawSessionId != null && !rawSessionId.isBlank()) {
                sessionService.requireSession(sessionId);
            }
            return sessionId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String resolveSessionId(String rawSessionId) {
        if (rawSessionId == null || rawSessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            String canonical = UUID.fromString(rawSessionId).toString();
            if (!canonical.equals(rawSessionId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "X-Session-Id must be a canonical UUID");
        }
    }

    private Mono<Void> saveUser(ChatRequest request, String sessionId) {
        String model = request.model() != null ? request.model() : "stub";
        String userContent = extractLastUserContent(request);
        log.info("Chat sessionId={}, model={}, stream={}, messages={}",
                sessionId, model, request.stream(), request.messages().size());

        return Mono.fromRunnable(() -> {
            sessionService.getOrCreate(sessionId, model);
            if (userContent != null) {
                sessionService.appendUserMessage(sessionId, userContent);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

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

}
