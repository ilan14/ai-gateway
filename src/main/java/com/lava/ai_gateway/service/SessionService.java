package com.lava.ai_gateway.service;

import com.lava.ai_gateway.dto.MessageDTO;
import com.lava.ai_gateway.dto.SessionDTO;
import com.lava.ai_gateway.dto.SessionDetailDTO;
import com.lava.ai_gateway.entity.MessageEntity;
import com.lava.ai_gateway.entity.SessionEntity;
import com.lava.ai_gateway.repository.MessageRepository;
import com.lava.ai_gateway.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public SessionService(SessionRepository sessionRepository,
                          MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    // ── 对话流程调用 ───────────────────────────────────────────────────────────

    public SessionEntity getOrCreate(String sessionId, String model) {
        SessionEntity session = sessionRepository.findById(sessionId);
        if (session == null) {
            session = new SessionEntity();
            session.setSessionId(sessionId);
            session.setModel(model);
            session.setTitle("");
            sessionRepository.save(session);
            log.info("Session created → sessionId={}, model={}", sessionId, model);
        }
        return session;
    }

    public void appendUserMessage(String sessionId, String content) {
        SessionEntity session = sessionRepository.findById(sessionId);
        if (session != null && session.getTitle().isBlank()) {
            String title = content.length() > 20 ? content.substring(0, 20) + "…" : content;
            sessionRepository.updateTitle(sessionId, title);
            log.debug("Session title set → sessionId={}, title={}", sessionId, title);
        }
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        messageRepository.append(msg);
        sessionRepository.touch(sessionId);
        log.debug("User message appended → sessionId={}", sessionId);
    }

    public void appendAssistantMessage(String sessionId, String content) {
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent(content);
        messageRepository.append(msg);
        sessionRepository.touch(sessionId);
        log.debug("Assistant message appended → sessionId={}, length={}", sessionId, content.length());
    }

    // ── Session 管理 API ───────────────────────────────────────────────────────

    public List<SessionDTO> listSessions() {
        return sessionRepository.listAll().stream()
                .map(this::toDTO)
                .toList();
    }

    public Optional<SessionDetailDTO> getSessionDetail(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId);
        if (session == null) {
            return Optional.empty();
        }

        List<MessageDTO> messages = messageRepository.findBySessionId(sessionId).stream()
                .map(m -> new MessageDTO(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();

        return Optional.of(new SessionDetailDTO(
                session.getSessionId(), session.getTitle(), session.getModel(),
                session.getCreatedAt(), session.getUpdatedAt(), messages));
    }

    public boolean updateTitle(String sessionId, String title) {
        SessionEntity session = sessionRepository.findById(sessionId);
        if (session == null) {
            return false;
        }
        sessionRepository.updateTitle(sessionId, title);
        log.info("Session title updated → sessionId={}, title={}", sessionId, title);
        return true;
    }

    @Transactional
    public boolean deleteSession(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId);
        if (session == null) {
            return false;
        }
        messageRepository.deleteBySessionId(sessionId);  // 先删消息（含 Redis 缓存）
        sessionRepository.delete(sessionId);
        log.info("Session deleted → sessionId={}", sessionId);
        return true;
    }

    // ── 私有转换 ──────────────────────────────────────────────────────────────

    private SessionDTO toDTO(SessionEntity s) {
        return new SessionDTO(s.getSessionId(), s.getTitle(), s.getModel(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
