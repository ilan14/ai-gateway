package com.lava.ai_gateway.mapper;

import com.lava.ai_gateway.entity.SessionEntity;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionMapper 单测，使用 H2 内存数据库。
 * @MybatisTest 只加载 MyBatis 相关 Bean，不启动完整 Spring 上下文。
 * @Transactional（@MybatisTest 默认开启）每个测试方法结束后自动回滚，保证测试隔离。
 */
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql("/test-schema.sql")
class SessionMapperTest {

    @Autowired
    private SessionMapper sessionMapper;

    // ── insert ─────────────────────────────────────────────────────────────────

    @Test
    void insert_shouldAssignGeneratedId() {
        SessionEntity session = buildSession("s-001", "deepseek-chat");

        sessionMapper.insert(session);

        assertThat(session.getId()).isNotNull().isPositive();
    }

    @Test
    void insert_shouldPersistAllFields() {
        SessionEntity session = buildSession("s-002", "qwen-turbo");
        session.setTitle("这是一个测试会话");

        sessionMapper.insert(session);
        SessionEntity found = sessionMapper.findBySessionId("s-002");

        assertThat(found.getSessionId()).isEqualTo("s-002");
        assertThat(found.getModel()).isEqualTo("qwen-turbo");
        assertThat(found.getTitle()).isEqualTo("这是一个测试会话");
    }

    // ── findBySessionId ────────────────────────────────────────────────────────

    @Test
    void findBySessionId_whenExists_shouldReturnSession() {
        SessionEntity session = buildSession("s-003", "deepseek-chat");
        sessionMapper.insert(session);

        SessionEntity found = sessionMapper.findBySessionId("s-003");

        assertThat(found).isNotNull();
        assertThat(found.getSessionId()).isEqualTo("s-003");
    }

    @Test
    void findBySessionId_whenNotExists_shouldReturnNull() {
        SessionEntity found = sessionMapper.findBySessionId("non-existent-id");

        assertThat(found).isNull();
    }

    // ── touchUpdatedAt ─────────────────────────────────────────────────────────

    @Test
    void touchUpdatedAt_forExistingSession_shouldNotThrow() {
        sessionMapper.insert(buildSession("s-004", "stub"));

        // 验证操作不抛异常即可（H2 不支持 ON UPDATE 触发器，只验证 SQL 执行成功）
        sessionMapper.touchUpdatedAt("s-004");

        assertThat(sessionMapper.findBySessionId("s-004")).isNotNull();
    }

    @Test
    void touchUpdatedAt_forNonExistingSession_shouldNotThrow() {
        // 不存在的 session 执行 UPDATE，影响行数为 0，不应抛异常
        sessionMapper.touchUpdatedAt("ghost-session");
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────────

    private SessionEntity buildSession(String sessionId, String model) {
        SessionEntity s = new SessionEntity();
        s.setSessionId(sessionId);
        s.setModel(model);
        s.setTitle("");
        return s;
    }
}
