package com.lava.ai_gateway.provider;

import com.lava.ai_gateway.config.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnProperty("gateway.providers.deepseek.api-key")
public class DeepSeekProvider extends AbstractOpenAiCompatibleProvider {

    public DeepSeekProvider(WebClient.Builder webClientBuilder, GatewayProperties properties) {
        super(webClientBuilder, properties.getProviders().get("deepseek"));
    }

    @Override
    public String name() {
        return "deepseek";
    }
}
