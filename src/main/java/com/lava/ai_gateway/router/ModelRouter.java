package com.lava.ai_gateway.router;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.provider.ModelProvider;
import com.lava.ai_gateway.provider.StubModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据请求中的 model 字段路由到对应的 ModelProvider。
 * 优先精确匹配，匹配不到时回退到默认模型对应的 Provider，最终兜底为 StubModelProvider。
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final List<ModelProvider> providers;
    private final String defaultModel;
    private final ModelProvider fallback;

    public ModelRouter(List<ModelProvider> providers,
                       GatewayProperties properties,
                       StubModelProvider fallback) {
        this.providers = providers;
        this.defaultModel = properties.getDefaultModel();
        this.fallback = fallback;
        log.info("ModelRouter initialized, defaultModel={}, providers={}",
                defaultModel, providers.stream().map(ModelProvider::name).toList());
    }

    public ModelProvider route(String requestModel) {
        String target = (requestModel != null && !requestModel.isBlank())
                ? requestModel
                : defaultModel;

        ModelProvider provider = providers.stream()
                .filter(p -> p.supportsModel(target))
                .findFirst()
                .orElse(fallback);

        log.debug("Route → model={}, provider={}", target, provider.name());
        return provider;
    }
}
