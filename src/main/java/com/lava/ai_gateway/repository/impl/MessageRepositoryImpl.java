package com.lava.ai_gateway.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lava.ai_gateway.entity.MessageEntity;
import com.lava.ai_gateway.mapper.MessageMapper;
import com.lava.ai_gateway.repository.MessageRepository;
import com.lava.ai_gateway.common.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Slf4j
@Repository
public class MessageRepositoryImpl implements MessageRepository {

    private static final String CACHE_KEY_PREFIX = "session:messages:";
    private static final Duration CACHE_TTL = Duration.ofHours(2);

    private final MessageMapper messageMapper;
    private final StringRedisTemplate redis;
    private final JsonCodec jsonCodec;

    public MessageRepositoryImpl(MessageMapper messageMapper,
                                 StringRedisTemplate redis,
                                 JsonCodec jsonCodec) {
        this.messageMapper = messageMapper;
        this.redis = redis;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<MessageEntity> findBySessionId(String sessionId) {
        String key = CACHE_KEY_PREFIX + sessionId;
        String cached = redis.opsForValue().get(key);

        if (cached != null) {
            log.debug("Cache hit → sessionId={}", sessionId);
            return jsonCodec.deserialize(cached, new TypeReference<List<MessageEntity>>() {});
        }

        log.debug("Cache miss → sessionId={}, loading from MySQL", sessionId);
        List<MessageEntity> messages = messageMapper.findBySessionId(sessionId);
        redis.opsForValue().set(key, jsonCodec.serialize(messages), CACHE_TTL);
        return messages;
    }

    @Override
    public void append(MessageEntity message) {
        messageMapper.insert(message);
        redis.delete(CACHE_KEY_PREFIX + message.getSessionId());
        log.debug("Message appended and cache invalidated → sessionId={}, role={}", message.getSessionId(),
                message.getRole());
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        messageMapper.deleteBySessionId(sessionId);
        redis.delete(CACHE_KEY_PREFIX + sessionId);
        log.debug("Messages deleted and cache cleared → sessionId={}", sessionId);
    }
}
