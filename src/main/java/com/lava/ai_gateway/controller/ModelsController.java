package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.config.GatewayProperties;
import com.lava.ai_gateway.model.ModelInfo;
import com.lava.ai_gateway.model.ModelListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Models", description = "OpenAI 兼容的模型列表接口")
@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final GatewayProperties gatewayProperties;

    public ModelsController(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    @Operation(summary = "List Models", description = "返回网关支持的所有模型，格式兼容 OpenAI API。")
    @GetMapping("/models")
    public Mono<ModelListResponse> listModels() {
        List<ModelInfo> models = new ArrayList<>();
        models.add(new ModelInfo("stub", "ai-gateway"));

        gatewayProperties.getProviders().forEach((providerName, config) ->
                config.getModels().forEach(modelId ->
                        models.add(new ModelInfo(modelId, providerName))
                )
        );

        return Mono.just(new ModelListResponse(models));
    }
}
