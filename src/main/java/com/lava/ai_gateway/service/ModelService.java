package com.lava.ai_gateway.service;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.model.ModelInfo;
import com.lava.ai_gateway.model.ModelListResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ModelService {

    private final GatewayProperties gatewayProperties;

    public ModelService(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    public ModelListResponse listModels() {
        List<ModelInfo> models = new ArrayList<>();
        models.add(new ModelInfo("stub", "ai-gateway"));
        models.add(new ModelInfo("mock", "mock"));

        gatewayProperties.getProviders().forEach((providerName, config) ->
                config.getModels().forEach(modelId ->
                        models.add(new ModelInfo(modelId, providerName))
                )
        );

        return new ModelListResponse(models);
    }
}
