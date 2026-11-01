package com.lava.ai_gateway.protection;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ProtectionErrorHandlerTest {
    private final ProtectionErrorHandler handler = new ProtectionErrorHandler(new ObjectMapper());

    @Test
    void rejectionUsesCompatibleJson() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
        handler.handle(exchange, new ProtectionException(429)).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.at("/error/type").asText()).isEqualTo("rate_limit_error");
        assertThat(json.at("/error/code").asText()).isEqualTo("model_protection");
    }

    @Test
    void unrelatedErrorPropagates() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        assertThatThrownBy(() -> handler.handle(exchange, new IllegalStateException("bad")).block())
                .isInstanceOf(IllegalStateException.class);
    }
}
