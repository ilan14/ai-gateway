package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnProperty("gateway.providers.qwen.api-key")
public class QwenProvider extends AbstractOpenAiCompatibleProvider {

    public QwenProvider(WebClient.Builder webClientBuilder, GatewayProperties properties) {
        super(webClientBuilder, properties.getProviders().get("qwen"));
    }

    @Override
    public String name() {
        return "qwen";
    }
}
