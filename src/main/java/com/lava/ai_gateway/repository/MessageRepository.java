package com.lava.ai_gateway.repository;

import com.lava.ai_gateway.entity.MessageEntity;

import java.util.List;

public interface MessageRepository {

    /**
     * 加载会话的全部消息。
     * 先查 Redis 缓存，未命中时从 MySQL 加载并回填缓存。
     */
    List<MessageEntity> findBySessionId(String sessionId);

    /**
     * 追加一条消息，写入 MySQL 并使 Redis 缓存失效。
     */
    void append(MessageEntity message);

    /**
     * 删除会话的所有消息，同时清除 Redis 缓存。
     */
    void deleteBySessionId(String sessionId);
}
