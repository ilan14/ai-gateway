package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties.ProviderConfig;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

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

    protected AbstractOpenAiCompatibleProvider(WebClient.Builder webClientBuilder,
                                               ProviderConfig config) {
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
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
//                .doOnNext(chunk -> log.debug("Stream chunk → provider={}, data={}", name(), chunk))
                .doOnComplete(() -> log.debug("Stream completed → provider={}, model={}", name(), request.model()))
                .doOnError(e -> log.error("Stream error → provider={}, model={}, error={}", name(), request.model(), e.getMessage()));
    }

    /**
     * 非流式对话：直接将上游 JSON 响应反序列化为 ChatResponse。
     */
    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        log.debug("Chat → provider={}, model={}, messages={}", name(), request.model(), request.messages().size());

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .doOnSuccess(r -> log.debug("Chat completed → provider={}, model={}, id={}", name(),
                        request.model(), r.id()))
                .doOnError(e -> log.error("Chat error → provider={}, model={}, error={}", name(), request.model(), e.getMessage()));
    }
}
