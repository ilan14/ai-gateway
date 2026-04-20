package com.lava.ai_gateway.repository.impl;

import com.lava.ai_gateway.entity.SessionEntity;
import com.lava.ai_gateway.mapper.SessionMapper;
import com.lava.ai_gateway.repository.SessionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SessionRepositoryImpl implements SessionRepository {

    private final SessionMapper sessionMapper;

    public SessionRepositoryImpl(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    @Override
    public SessionEntity findById(String sessionId) {
        return sessionMapper.findBySessionId(sessionId);
    }

    @Override
    public List<SessionEntity> listAll() {
        return sessionMapper.listAll();
    }

    @Override
    public void save(SessionEntity session) {
        sessionMapper.insert(session);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        sessionMapper.updateTitle(sessionId, title);
    }

    @Override
    public void delete(String sessionId) {
        sessionMapper.deleteBySessionId(sessionId);
    }

    @Override
    public void touch(String sessionId) {
        sessionMapper.touchUpdatedAt(sessionId);
    }
}
