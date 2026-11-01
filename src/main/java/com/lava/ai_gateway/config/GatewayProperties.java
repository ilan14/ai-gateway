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

    private ProtectionConfig protection = new ProtectionConfig();

    @Getter
    @Setter
    public static class ProtectionConfig {
        private boolean enabled;
        /** 键为 provider:model，配置该模型的完整保护参数。 */
        private Map<String, ProtectionConfig> models = new HashMap<>();
        private int providerRpm;
        private int providerConcurrency;
        private int globalRpm = 600;
        private int globalConcurrency = 100;
        private int rpm = 60;
        private int concurrency = 10;
        private int windowSeconds = 60;
        private int minimumCalls = 20;
        private int failurePercent = 50;
        private int consecutiveFailures = 5;
        private int cooldownSeconds = 30;
        private int leaseSeconds = 60;
        private int timeoutSeconds = 120;

        public void validate() {
            if (globalRpm < 0 || globalConcurrency < 0 || rpm < 0 || concurrency < 0
                    || providerRpm < 0 || providerConcurrency < 0 || windowSeconds < 1
                    || minimumCalls < 1 || failurePercent < 1 || failurePercent > 100
                    || consecutiveFailures < 1 || cooldownSeconds < 1 || leaseSeconds < 3 || timeoutSeconds < 1) {
                throw new IllegalArgumentException("模型保护配置不合法");
            }
            models.values().forEach(ProtectionConfig::validate);
        }
    }

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
