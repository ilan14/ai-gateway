package com.lava.ai_gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String defaultModel = "stub";
    private Map<String, ProviderConfig> providers = new HashMap<>();

    @Getter
    @Setter
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private List<String> models = new ArrayList<>();
    }
}
