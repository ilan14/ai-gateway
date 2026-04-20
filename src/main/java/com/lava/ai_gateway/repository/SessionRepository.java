package com.lava.ai_gateway.repository;

import com.lava.ai_gateway.entity.SessionEntity;

import java.util.List;

public interface SessionRepository {

    SessionEntity findById(String sessionId);

    List<SessionEntity> listAll();

    void save(SessionEntity session);

    void updateTitle(String sessionId, String title);

    void delete(String sessionId);

    void touch(String sessionId);
}
