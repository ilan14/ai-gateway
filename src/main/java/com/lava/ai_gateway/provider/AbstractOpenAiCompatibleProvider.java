package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties.ProviderConfig;
import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
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

    private final WebClient webClient;
    private final List<String> supportedModels;

    protected AbstractOpenAiCompatibleProvider(WebClient.Builder webClientBuilder,
                                               ProviderConfig config) {
        this.webClient = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
        this.supportedModels = config.getModels();
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
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim());
    }

    /**
     * 非流式对话：直接将上游 JSON 响应反序列化为 ChatResponse。
     */
    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class);
    }
}
