package com.lava.ai_gateway.protection;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 保护拒绝使用 OpenAI 兼容错误体，已提交的 SSE 发送错误事件。 */
@Component
@Order(-2)
public class ProtectionErrorHandler implements WebExceptionHandler {
    private final ObjectMapper mapper;
    public ProtectionErrorHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (!(error instanceof ProtectionException rejection)) return Mono.error(error);
        var response = exchange.getResponse();
        String json = mapper.writeValueAsString(Map.of("error", Map.of("message", rejection.getReason(),
                "type", rejection.getStatusCode().value() == 429 ? "rate_limit_error" : "service_unavailable",
                "code", "model_protection")));
        boolean committed = response.isCommitted();
        if (!committed) {
            response.setStatusCode(rejection.getStatusCode());
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }
        byte[] body = (committed ? "data: " + json + "\n\n" : json).getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
