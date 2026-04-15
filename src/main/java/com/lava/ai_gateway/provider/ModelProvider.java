package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.model.ChatRequest;
import com.lava.ai_gateway.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 统一的模型提供商接口。
 * 每个 AI 厂商实现此接口，屏蔽各自的 API 协议差异。
 */
public interface ModelProvider {

    /** 提供商标识，如 "qwen"、"deepseek"、"wenxin" */
    String name();

    /** 判断该提供商是否支持指定模型名 */
    boolean supportsModel(String model);

    /**
     * 流式对话：返回 Flux<String>，每个元素是一帧 SSE data 字段的原始 JSON。
     * 最后一帧固定为字符串 "[DONE]"。
     */
    Flux<String> streamChat(ChatRequest request);

    /** 非流式对话：返回完整响应 */
    Mono<ChatResponse> chat(ChatRequest request);
}
