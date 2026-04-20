package com.lava.ai_gateway.mapper;

import com.lava.ai_gateway.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper {

    void insert(MessageEntity message);

    List<MessageEntity> findBySessionId(@Param("sessionId") String sessionId);

    void deleteBySessionId(@Param("sessionId") String sessionId);
}
