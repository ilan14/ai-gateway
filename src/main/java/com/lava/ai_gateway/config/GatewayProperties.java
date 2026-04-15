package com.lava.ai_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String defaultModel = "stub";
    private Map<String, ProviderConfig> providers = new HashMap<>();

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private List<String> models = new ArrayList<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = models; }
    }
}
