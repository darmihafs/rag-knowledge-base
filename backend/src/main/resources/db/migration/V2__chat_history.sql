-- 第四阶段增量脚本：多轮对话记忆与历史会话持久化
-- 用法：mysql -uroot -p kb_rag < V2__chat_history.sql
--       （或在 IDEA 的 Database 面板里选中 kb_rag 后直接执行本文件）
-- 全部使用 IF NOT EXISTS，可重复执行，不会破坏已有数据。

USE kb_rag;

-- 会话表：一条记录 = 左侧历史列表里的一项
CREATE TABLE IF NOT EXISTS chat_session
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    title       VARCHAR(128)    NOT NULL DEFAULT '新对话' COMMENT '会话标题，首次提问后按问题前15字自动生成',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间，列表按此倒序',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_session_update_time (update_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='聊天会话';

-- 消息表：会话下的每一轮提问与回答
-- citations 存 JSON 数组字符串（[{documentId,title,chunkIndex,snippet,score}]），
-- 用 TEXT 而不是 MySQL 的 JSON 类型：H2 测试库不支持 JSON，保持两边 DDL 可对齐。
CREATE TABLE IF NOT EXISTS chat_message
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    session_id  BIGINT UNSIGNED NOT NULL COMMENT '所属会话ID',
    `role`      VARCHAR(16)     NOT NULL COMMENT 'user=提问，assistant=回答',
    content     MEDIUMTEXT      NOT NULL COMMENT '消息正文，assistant 存流式结束后的完整回答',
    citations   TEXT                     DEFAULT NULL COMMENT '引用来源JSON数组，仅 assistant 有值',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_message_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='聊天消息';
