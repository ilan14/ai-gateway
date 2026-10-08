package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 转发到 mock-http-server；场景和 settings 完全由上游服务管理。 */
@Component
public class MockModelProvider implements ModelProvider {
    private final WebClient webClient;
    private final MeterRegistry registry;
    private final AtomicInteger inFlight = new AtomicInteger();

    public MockModelProvider(WebClient.Builder builder, MeterRegistry registry,
                             @Value("${gateway.mock.url}") String url,
                             @Value("${gateway.mock.port}") int port) {
        this.webClient = builder.baseUrl(UriComponentsBuilder.fromUriString(url).port(port)
                .build().toUriString()).build();
        this.registry = registry;
        registry.gauge("gateway.mock.inflight", List.of(io.micrometer.core.instrument.Tag.of("provider", "mock")),
                inFlight);
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean supportsModel(String model) {
        return "mock".equals(model);
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            inFlight.incrementAndGet();
            return observe(webClient.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                    .retrieve().bodyToMono(ChatResponse.class).flux(), false, sample).single();
        });
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            inFlight.incrementAndGet();
            // WebClient 的 SSE 解码器去掉 data: 前缀，Controller 再包装为 SSE。
            return observe(webClient.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request).retrieve().bodyToFlux(String.class), true, sample);
        });
    }

    private <T> Flux<T> observe(Flux<T> source, boolean stream, Timer.Sample sample) {
        AtomicBoolean first = new AtomicBoolean();
        String[] outcome = {"success"};
        return source.doOnNext(value -> {
                    // 首帧只记录一次；同一样本可用于测量从订阅起算的首帧和总耗时。
                    if (stream && first.compareAndSet(false, true))
                        sample.stop(timer("gateway.mock.first.frame", true, "received"));
                }).doOnError(error -> outcome[0] = error instanceof WebClientResponseException status
                        ? Integer.toString(status.getStatusCode().value()) : "error")
                .doFinally(signal -> {
                    // 上游挂起时，客户端取消也需要释放在途计数。
                    String result = signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : outcome[0];
                    inFlight.decrementAndGet();
                    sample.stop(timer("gateway.mock.duration", stream, result));
                    registry.counter("gateway.mock.requests", "provider", "mock",
                            "stream", Boolean.toString(stream), "outcome", result).increment();
                });
    }

    /** 使用有限的模式和结果标签，并导出直方图供 Grafana 计算分位数。 */
    private Timer timer(String name, boolean stream, String outcome) {
        return Timer.builder(name).tags("provider", "mock",
                "stream", Boolean.toString(stream), "outcome", outcome).publishPercentileHistogram().register(registry);
    }

}
