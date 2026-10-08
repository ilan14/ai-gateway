package com.lava.ai_gateway.controller;

import com.lava.ai_gateway.model.ModelListResponse;
import com.lava.ai_gateway.service.ModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "Models", description = "OpenAI 兼容的模型列表接口")
@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final ModelService modelService;

    public ModelsController(ModelService modelService) {
        this.modelService = modelService;
    }

    @Operation(summary = "List Models", description = "返回网关支持的所有模型，格式兼容 OpenAI API。")
    @GetMapping("/models")
    public Mono<ModelListResponse> listModels() {
        return Mono.fromSupplier(modelService::listModels);
    }
}
