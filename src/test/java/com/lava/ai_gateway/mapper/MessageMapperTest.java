package com.lava.ai_gateway.mapper;

import com.lava.ai_gateway.entity.MessageEntity;
import com.lava.ai_gateway.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql("/test-schema.sql")
class MessageMapperTest {

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private SessionMapper sessionMapper;

    private static final String SESSION_ID = "test-session-001";

    @BeforeEach
    void setUp() {
        // 先建好 session，保持数据完整性
        SessionEntity session = new SessionEntity();
        session.setSessionId(SESSION_ID);
        session.setModel("stub");
        session.setTitle("");
        sessionMapper.insert(session);
    }

    // ── insert ─────────────────────────────────────────────────────────────────

    @Test
    void insert_shouldAssignGeneratedId() {
        MessageEntity msg = buildMessage(SESSION_ID, "user", "你好");

        messageMapper.insert(msg);

        assertThat(msg.getId()).isNotNull().isPositive();
    }

    @Test
    void insert_shouldPersistAllFields() {
        MessageEntity msg = buildMessage(SESSION_ID, "assistant", "你好，有什么可以帮助你？");

        messageMapper.insert(msg);
        List<MessageEntity> messages = messageMapper.findBySessionId(SESSION_ID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo("assistant");
        assertThat(messages.get(0).getContent()).isEqualTo("你好，有什么可以帮助你？");
        assertThat(messages.get(0).getSessionId()).isEqualTo(SESSION_ID);
    }

    // ── findBySessionId ────────────────────────────────────────────────────────

    @Test
    void findBySessionId_whenNoMessages_shouldReturnEmptyList() {
        List<MessageEntity> messages = messageMapper.findBySessionId(SESSION_ID);

        assertThat(messages).isEmpty();
    }

    @Test
    void findBySessionId_shouldReturnAllMessagesForSession() {
        messageMapper.insert(buildMessage(SESSION_ID, "user", "第一个问题"));
        messageMapper.insert(buildMessage(SESSION_ID, "assistant", "第一个回答"));
        messageMapper.insert(buildMessage(SESSION_ID, "user", "第二个问题"));

        List<MessageEntity> messages = messageMapper.findBySessionId(SESSION_ID);

        assertThat(messages).hasSize(3);
    }

    @Test
    void findBySessionId_shouldReturnOnlyMessagesForGivenSession() {
        // 另一个 session 的消息不应被查到
        SessionEntity other = new SessionEntity();
        other.setSessionId("other-session");
        other.setModel("stub");
        other.setTitle("");
        sessionMapper.insert(other);

        messageMapper.insert(buildMessage(SESSION_ID, "user", "属于 session-001 的消息"));
        messageMapper.insert(buildMessage("other-session", "user", "属于 other-session 的消息"));

        List<MessageEntity> messages = messageMapper.findBySessionId(SESSION_ID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).isEqualTo("属于 session-001 的消息");
    }

    @Test
    void findBySessionId_shouldReturnMessagesOrderedByCreatedAtAsc() {
        messageMapper.insert(buildMessage(SESSION_ID, "user", "消息1"));
        messageMapper.insert(buildMessage(SESSION_ID, "assistant", "消息2"));
        messageMapper.insert(buildMessage(SESSION_ID, "user", "消息3"));

        List<MessageEntity> messages = messageMapper.findBySessionId(SESSION_ID);

        assertThat(messages).extracting(MessageEntity::getContent)
                .containsExactly("消息1", "消息2", "消息3");
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────────

    private MessageEntity buildMessage(String sessionId, String role, String content) {
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }
}
