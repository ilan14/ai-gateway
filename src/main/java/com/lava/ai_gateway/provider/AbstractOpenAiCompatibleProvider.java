package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties.ProviderConfig;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容协议的通用适配基类。
 *
 * 子类只需在构造器中传入配置，无需重写任何业务方法。
 * 私有协议厂商（如百度文心）跳过此基类，直接实现 ModelProvider 接口。
 */
public abstract class AbstractOpenAiCompatibleProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiCompatibleProvider.class);

    private final WebClient webClient;
    private final List<String> supportedModels;
    private final MeterRegistry registry;
    private final ConcurrentMap<String, AtomicInteger> inFlightCounters = new ConcurrentHashMap<>();

    protected AbstractOpenAiCompatibleProvider(WebClient.Builder webClientBuilder,
                                               ProviderConfig config, MeterRegistry registry) {
        this.registry = registry;
        this.webClient = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
        this.supportedModels = config.getModels();
        log.info("Provider [{}] initialized, baseUrl={}, models={}", name(), config.getBaseUrl(), config.getModels());
    }

    @Override
    public boolean supportsModel(String model) {
        return supportedModels.contains(model);
    }

    /**
     * 流式对话：接收上游 SSE，逐行过滤 "data:" 前缀后透传。
     * 每个元素是原始 JSON 字符串（或 "[DONE]"），由 Controller 负责加回 SSE 格式。
     */
    @Override
    public Flux<String> streamChat(ChatRequest request) {
        log.debug("Stream chat → provider={}, model={}, messages={}",
                name(), request.model(), request.messages().size());

        // bodyToFlux(String.class) 对 text/event-stream 响应会自动用 ServerSentEventHttpMessageReader
        // 解析，data: 前缀已被剥掉，直接得到 JSON 内容（或 "[DONE]"），无需再手动过滤
        return observe(request, true, webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
//                .doOnNext(chunk -> log.debug("Stream chunk → provider={}, data={}", name(), chunk))
                .doOnComplete(() -> log.debug("Stream completed → provider={}, model={}", name(), request.model()))
                .doOnError(e -> log.error("Stream error → provider={}, model={}, error={}", name(), request.model(), e.getMessage())));
    }

    /**
     * 非流式对话：直接将上游 JSON 响应反序列化为 ChatResponse。
     */
    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        log.debug("Chat → provider={}, model={}, messages={}", name(), request.model(), request.messages().size());

        return observe(request, false, webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .doOnSuccess(r -> log.debug("Chat completed → provider={}, model={}, id={}", name(),
                        request.model(), r.id()))
                .doOnError(e -> log.error("Chat error → provider={}, model={}, error={}", name(), request.model(), e.getMessage())).flux()).single();
    }

    /** 在订阅时计时，覆盖响应读取全过程；每次订阅独立维护结果和首帧状态。 */
    private <T> Flux<T> observe(ChatRequest request, boolean stream, Flux<T> source) {
        String model = request.model() != null && supportsModel(request.model()) ? request.model() : "unknown";
        String[] tags = {"provider", name(), "model", model, "stream", Boolean.toString(stream)};
        AtomicInteger inFlight = inFlightCounters.computeIfAbsent(model + ":" + stream, key ->
                registry.gauge("gateway.provider.inflight",
                        Tags.of(tags), new AtomicInteger()));
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            AtomicBoolean first = new AtomicBoolean();
            AtomicBoolean done = new AtomicBoolean();
            AtomicBoolean failed = new AtomicBoolean();
            registry.counter("gateway.provider.started", tags).increment();
            inFlight.incrementAndGet();
            return source.doOnNext(value -> {
                        if (stream && first.compareAndSet(false, true)) {
                            sample.stop(Timer.builder("gateway.provider.first.frame")
                                    .tags(tags).publishPercentileHistogram().register(registry));
                        }
                        if (stream && "[DONE]".equals(value)) done.set(true);
                    })
                    .doOnError(error -> failed.set(true))
                    .doFinally(signal -> {
                        // ChatService 在 [DONE] 后停止订阅，这种取消属于正常结束。
                        String outcome = failed.get() ? "error"
                                : signal == SignalType.CANCEL && !done.get() ? "cancelled"
                                : stream && !done.get() ? "error" : "success";
                        inFlight.decrementAndGet();
                        registry.counter("gateway.provider.requests", Tags.of(tags)
                                .and("outcome", outcome)).increment();
                        sample.stop(Timer.builder("gateway.provider.duration").tags(tags)
                                .tag("outcome", outcome).publishPercentileHistogram().register(registry));
                    });
        });
    }

}
