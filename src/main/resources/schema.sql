CREATE DATABASE IF NOT EXISTS ai_gateway DEFAULT CHARACTER SET utf8mb4;

USE ai_gateway;

CREATE TABLE IF NOT EXISTS sessions (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键，不对外暴露',
    session_id VARCHAR(36)  NOT NULL                COMMENT '对外业务 ID，UUID',
    title      VARCHAR(100) NOT NULL DEFAULT ''     COMMENT '会话标题',
    model      VARCHAR(50)  NOT NULL                COMMENT '使用的模型',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

CREATE TABLE IF NOT EXISTS messages (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL             COMMENT '关联 sessions.session_id',
    role       VARCHAR(10) NOT NULL             COMMENT 'user / assistant',
    content    TEXT        NOT NULL             COMMENT '消息内容',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';
