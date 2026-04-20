package com.lava.ai_gateway.mapper;

import com.lava.ai_gateway.entity.SessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SessionMapper {

    void insert(SessionEntity session);

    SessionEntity findBySessionId(@Param("sessionId") String sessionId);

    /** 按 updated_at 倒序列出所有会话 */
    List<SessionEntity> listAll();

    void updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    void deleteBySessionId(@Param("sessionId") String sessionId);

    /** 每次有新消息时刷新 updated_at，用于按最近活跃排序 */
    void touchUpdatedAt(@Param("sessionId") String sessionId);
}
