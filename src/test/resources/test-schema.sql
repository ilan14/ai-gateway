-- H2 兼容的建表脚本，用于 Mapper 单测
-- 与 schema.sql 保持结构一致，去掉 MySQL 专有语法

CREATE TABLE IF NOT EXISTS sessions (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36)  NOT NULL,
    title      VARCHAR(100) NOT NULL DEFAULT '',
    model      VARCHAR(50)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    role       VARCHAR(10) NOT NULL,
    content    TEXT        NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
