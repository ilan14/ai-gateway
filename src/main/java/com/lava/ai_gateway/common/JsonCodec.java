package com.lava.ai_gateway.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lava.ai_gateway.exception.ServerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author benjamin
 */
@Slf4j
@Component
public class JsonCodec {
    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("serialize error, obj:{}", object);
            throw new ServerException(e);
        }
    }

    public <T> T deserialize(String str, Class<T> clazz) {
        try {
            return objectMapper.readValue(str, clazz);
        } catch (JsonProcessingException e) {
            log.error("deserialize error, str:{}, className:{}", str, clazz.getSimpleName());
            throw new ServerException(e);
        }
    }

    public <T> T deserialize(String str, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(str, typeReference);
        } catch (JsonProcessingException e) {
            log.error("deserialize error, str:{}, className:{}", str, typeReference.getClass().getSimpleName());
            throw new ServerException(e);
        }
    }
}
