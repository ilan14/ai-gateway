package com.lava.ai_gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Gateway")
                        .description("统一 AI 大模型网关，提供 OpenAI 兼容接口，支持多模型路由")
                        .version("0.0.1"));
    }
}
