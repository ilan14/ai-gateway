package com.lava.ai_gateway.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * @author benjamin
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        // 自定义 Jackson 序列化细节
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        Jackson2JsonEncoder encoder = new Jackson2JsonEncoder(objectMapper);
        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(objectMapper);

        configurer.defaultCodecs().jackson2JsonEncoder(encoder);
        configurer.defaultCodecs().jackson2JsonDecoder(decoder);
    }

}
