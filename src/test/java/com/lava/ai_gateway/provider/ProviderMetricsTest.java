package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProviderMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ModelProvider provider(String name, Mono<ClientResponse> response) {
        return provider(name, response, registry);
    }

    private ModelProvider provider(String name, Mono<ClientResponse> response, MeterRegistry meters) {
        var config = new GatewayProperties.ProviderConfig();
        config.setBaseUrl("http://model.test");
        config.setApiKey("test");
        config.setModels(List.of("model-a", "model-b"));
        var properties = new GatewayProperties();
        properties.setProviders(Map.of(name, config));
        var builder = WebClient.builder().exchangeFunction(request -> response);
        return name.equals("deepseek") ? new DeepSeekProvider(builder, properties, meters)
                : new QwenProvider(builder, properties, meters);
    }

    private ChatRequest request(String model, boolean stream) {
        return new ChatRequest(model, List.of(new Message("user", "你好")), stream, null, null);
    }

    private Mono<ClientResponse> stream(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream").body(body).build());
    }

    @Test
    void recordsBothProvidersAndModelsPerSubscription() {
        for (String name : List.of("deepseek", "qwen")) {
            var provider = provider(name, stream("data: hello\n\ndata: [DONE]\n\n"));
            for (String model : List.of("model-a", "model-b")) {
                var source = provider.streamChat(request(model, true));
                assertThat(registry.find("gateway.provider.started").tags("provider", name, "model", model)
                        .counter()).isNull();
                source.collectList().block();
                source.collectList().block();
                assertThat(registry.get("gateway.provider.started").tags("provider", name, "model", model)
                        .counter().count()).isEqualTo(2);
                assertThat(registry.get("gateway.provider.duration").tags("provider", name, "model", model,
                        "outcome", "success").timer().count()).isEqualTo(2);
                assertThat(registry.get("gateway.provider.first.frame").tags("provider", name, "model", model)
                        .timer().count()).isEqualTo(2);
                assertThat(registry.get("gateway.provider.inflight").tags("provider", name, "model", model)
                        .gauge().value()).isZero();
            }
        }
    }

    @Test
    void doneThenCancellationIsSuccessButMissingDoneIsError() {
        provider("deepseek", stream("data: [DONE]\n\n"))
                .streamChat(request("model-a", true)).takeUntil("[DONE]"::equals).collectList().block();
        assertOutcome("success", true);
        provider("deepseek", stream("data: hello\n\n"))
                .streamChat(request("model-a", true)).collectList().block();
        assertOutcome("error", true);
    }

    @Test
    void cancellationReleasesInflightAndUnknownModelsHaveBoundedLabel() {
        var provider = provider("deepseek", Mono.never());
        var subscription = provider.streamChat(request("arbitrary-model", true)).subscribe();
        assertThat(registry.get("gateway.provider.inflight").tag("model", "unknown").gauge().value()).isEqualTo(1);
        subscription.dispose();
        assertThat(registry.get("gateway.provider.inflight").tag("model", "unknown").gauge().value()).isZero();
        assertThat(registry.get("gateway.provider.requests").tag("outcome", "cancelled").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsNonStreamingResponseAndHttpFailure() {
        var ok = Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                .body("{\"id\":\"test\",\"created\":1,\"model\":\"model-a\",\"choices\":[]}").build());
        assertThat(provider("deepseek", ok).chat(request("model-a", false)).block().id()).isEqualTo("test");
        assertOutcome("success", false);
        var failure = Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        assertThatThrownBy(() -> provider("deepseek", failure).chat(request("model-a", false)).block())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
        assertOutcome("error", false);
        assertThat(registry.find("gateway.provider.first.frame").timer()).isNull();
    }

    @Test
    void concurrentSubscriptionsShareInflightCounter() {
        var provider = provider("deepseek", Mono.never());
        var first = provider.chat(request("model-a", false)).subscribe();
        var second = provider.chat(request("model-a", false)).subscribe();
        assertThat(registry.get("gateway.provider.inflight").gauge().value()).isEqualTo(2);
        first.dispose();
        assertThat(registry.get("gateway.provider.inflight").gauge().value()).isEqualTo(1);
        second.dispose();
        assertThat(registry.get("gateway.provider.inflight").gauge().value()).isZero();
        assertThat(registry.get("gateway.provider.requests").tag("outcome", "cancelled").counter().count()).isEqualTo(2);
    }

    @Test
    void prometheusExportsCountersAndHistogramBuckets() {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            provider("qwen", stream("data: hello\n\ndata: [DONE]\n\n"), meters)
                    .streamChat(request("model-a", true)).collectList().block();
            assertThat(meters.scrape()).contains("gateway_provider_started_total",
                    "gateway_provider_requests_total", "gateway_provider_inflight",
                    "gateway_provider_duration_seconds_bucket", "gateway_provider_first_frame_seconds_bucket",
                    "provider=\"qwen\"", "model=\"model-a\"", "outcome=\"success\"");
        } finally {
            meters.close();
        }
    }

    @Test
    void applicationEnablesHttpHistogramAndBuckets() throws Exception {
        var properties = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        assertThat(properties.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests")).isEqualTo(true);
        assertThat(properties.getProperty("management.metrics.distribution.slo.http.server.requests"))
                .isEqualTo("100ms,300ms,500ms,1s,3s,10s,30s,60s");
    }

    private void assertOutcome(String outcome, boolean stream) {
        var tags = new String[]{"provider", "deepseek", "model", "model-a", "stream", Boolean.toString(stream)};
        assertThat(registry.get("gateway.provider.requests").tags(tags).tag("outcome", outcome).counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.provider.duration").tags(tags).tag("outcome", outcome).timer().count()).isEqualTo(1);
        assertThat(registry.get("gateway.provider.inflight").tags(tags).gauge().value()).isZero();
    }
}
