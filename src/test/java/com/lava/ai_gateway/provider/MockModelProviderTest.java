package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class MockModelProviderTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();
    private final ChatRequest request = new ChatRequest("mock", List.of(new Message("user", "hello")), false, 0.5, 32);

    @Test
    void forwardsChatToConfiguredServerAndDecodesResponse() {
        var provider = provider(Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"id\":\"upstream-id\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"mock\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"remote\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}")
                .build()));
        var response = provider.chat(request).block(Duration.ofSeconds(2));
        assertThat(response.id()).isEqualTo("upstream-id");
        assertThat(response.choices().getFirst().message().content()).isEqualTo("remote");
        assertThat(sent.get().url().toString()).isEqualTo("http://mock.example:9099/v1/chat/completions");
        assertThat(sent.get().method().name()).isEqualTo("POST");
        assertThat(sent.get().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertForwardedBody(request);
        assertMetrics("success");
    }

    @Test
    void forwardsSseDataAndDoneWithoutDataPrefix() {
        var provider = provider(Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream")
                .body("data: {\"id\":\"remote-chunk\"}\n\ndata: [DONE]\n\n").build()));
        var streaming = new ChatRequest("mock", request.messages(), true, 0.5, 32);
        assertThat(provider.streamChat(streaming).collectList().block(Duration.ofSeconds(2)))
                .containsExactly("{\"id\":\"remote-chunk\"}", "[DONE]");
        assertThat(sent.get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
        assertForwardedBody(streaming);
        assertThat(registry.get("gateway.mock.first.frame").timer().count()).isEqualTo(1);
        assertMetrics("success");
    }

    @Test
    void propagatesHttpErrorsAndRecordsStatus() {
        for (var status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.SERVICE_UNAVAILABLE)) {
            var provider = provider(Mono.just(ClientResponse.create(status).body("upstream failure").build()));
            assertThatThrownBy(() -> provider.chat(request).block(Duration.ofSeconds(2)))
                    .isInstanceOf(WebClientResponseException.class)
                    .satisfies(error -> assertThat(((WebClientResponseException) error).getStatusCode()).isEqualTo(status));
            assertMetrics(Integer.toString(status.value()));
        }
    }

    @Test
    void propagatesStreamingHttpError() {
        var provider = provider(Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));
        assertThatThrownBy(() -> provider.streamChat(request).collectList().block(Duration.ofSeconds(2)))
                .isInstanceOf(WebClientResponseException.ServiceUnavailable.class);
        assertMetrics("503");
    }

    @Test
    void cancellationReleasesInflightRequest() {
        var provider = provider(Mono.never());
        var subscription = provider.streamChat(request).subscribe();
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isEqualTo(1);
        subscription.dispose();
        assertMetrics("cancelled");
    }

    @Test
    void connectionFailureReleasesInflightRequest() {
        var provider = provider(Mono.error(new IllegalStateException("connection failed")));
        assertThatThrownBy(() -> provider.chat(request).block(Duration.ofSeconds(2)))
                .hasMessage("connection failed");
        assertMetrics("error");
    }

    @Test
    void configurationDefaultsAndOverridesAreInjected() throws IOException {
        assertConfiguredAddress(Map.of(), "http://localhost:8081/v1/chat/completions");
        assertConfiguredAddress(Map.of("gateway.mock.url", "https://mock.example", "gateway.mock.port", "9443"),
                "https://mock.example:9443/v1/chat/completions");
    }

    private void assertConfiguredAddress(Map<String, Object> properties, String expected) throws IOException {
        registry.clear();
        try (var context = new AnnotationConfigApplicationContext()) {
            // 普通 Spring 上下文不会自动加载 Boot 配置；使用实际 YAML 验证默认值。
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
            sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.registerBean(WebClient.Builder.class, () -> builder(Mono.never()));
            context.registerBean(SimpleMeterRegistry.class, () -> registry);
            context.register(MockModelProvider.class);
            context.refresh();
            var provider = context.getBean(MockModelProvider.class);
            assertThat(provider.name()).isEqualTo("mock");
            assertThat(provider.supportsModel("mock")).isTrue();
            assertThat(provider.supportsModel("stub")).isFalse();
            var subscription = provider.chat(request).subscribe();
            assertThat(sent.get().url().toString()).isEqualTo(expected);
            subscription.dispose();
        }
    }

    private WebClient.Builder builder(Mono<ClientResponse> response) {
        return WebClient.builder().exchangeFunction(outgoing -> {
            sent.set(outgoing);
            return response;
        });
    }

    private MockModelProvider provider(Mono<ClientResponse> response) {
        registry.clear();
        return new MockModelProvider(builder(response), registry, "http://mock.example", 9099);
    }

    private void assertForwardedBody(ChatRequest expected) {
        var output = new MockClientHttpRequest(sent.get().method(), sent.get().url());
        sent.get().body().insert(output, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block(Duration.ofSeconds(2));
        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(output.getBodyAsString().block(Duration.ofSeconds(2))))
                .isEqualTo(mapper.valueToTree(expected));
    }

    private void assertMetrics(String outcome) {
        assertThat(registry.get("gateway.mock.inflight").gauge().value()).isZero();
        assertThat(registry.get("gateway.mock.requests").tag("outcome", outcome).counter().count()).isEqualTo(1);
    }
}
