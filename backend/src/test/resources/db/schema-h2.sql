-- 测试专用建表脚本（H2 / MySQL 兼容模式）
-- 与 db/schema.sql 的字段保持一致，只去掉 H2 不支持的 ENGINE、FULLTEXT ngram、ON UPDATE 子句
CREATE TABLE IF NOT EXISTS sys_user
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(128) NOT NULL,
    nickname    VARCHAR(64),
    role        VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status      TINYINT      NOT NULL DEFAULT 1,
    token_version INT        NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON sys_user (username);

-- 体验账号（BCrypt 密文）：admin/admin123(ADMIN)、student/123456(USER)
INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 1, 'admin', '$2a$10$W07oeVJepI477BktFAZBFer5Vea0oKVU8ApbqJnL3XVgW8MGJsQzG', '系统管理员', 'ADMIN', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 2, 'student', '$2a$10$.DGxHu5odVethPPz5bRK5ekuAEwhAP.jLxPvyGTb4Ma3ZS4PH5MN6', '体验学生', 'USER', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'student');

CREATE TABLE IF NOT EXISTS kb_space
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_by  BIGINT,
    doc_count   INT          NOT NULL DEFAULT 0,
    chunk_count INT          NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0
);

INSERT INTO kb_space (id, name, description, created_by)
SELECT 1, '默认知识库', '系统初始化的默认空间，历史文档自动归入此库', 1
WHERE NOT EXISTS (SELECT 1 FROM kb_space WHERE id = 1);

CREATE TABLE IF NOT EXISTS kb_space_member
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id    BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_space_user ON kb_space_member (space_id, user_id);
CREATE INDEX IF NOT EXISTS idx_member_user ON kb_space_member (user_id);

INSERT INTO kb_space_member (space_id, user_id)
SELECT s.id, COALESCE(s.created_by, 1) FROM kb_space s
WHERE s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM kb_space_member m WHERE m.space_id = s.id AND m.user_id = COALESCE(s.created_by, 1));

INSERT INTO kb_space_member (space_id, user_id)
SELECT 1, u.id FROM sys_user u
WHERE u.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM kb_space_member m WHERE m.space_id = 1 AND m.user_id = u.id);

CREATE TABLE IF NOT EXISTS kb_document
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id    BIGINT,
    title       VARCHAR(255) NOT NULL,
    file_type   VARCHAR(32)  NOT NULL,
    file_path   VARCHAR(512),
    file_size   BIGINT,
    file_hash   VARCHAR(64),
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    chunk_count INT          NOT NULL DEFAULT 0,
    error_msg   VARCHAR(500),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_doc_hash ON kb_document (space_id, title, file_hash);
CREATE INDEX IF NOT EXISTS idx_doc_space_status ON kb_document (space_id, status);
CREATE INDEX IF NOT EXISTS idx_doc_space_time ON kb_document (space_id, create_time);
CREATE INDEX IF NOT EXISTS idx_doc_space_del_id ON kb_document (space_id, deleted, id);
CREATE INDEX IF NOT EXISTS idx_doc_status_del_id ON kb_document (status, deleted, id);

CREATE TABLE IF NOT EXISTS kb_chunk
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT      NOT NULL,
    chunk_index INT         NOT NULL,
    page_number INT,
    content     CLOB        NOT NULL,
    char_count  INT,
    enabled     TINYINT     NOT NULL DEFAULT 1,
    vector_ref  VARCHAR(128),
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chunk_doc ON kb_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_enabled ON kb_chunk (enabled);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_enabled ON kb_chunk (document_id, enabled);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_index ON kb_chunk (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_chunk_enabled_doc ON kb_chunk (enabled, document_id);

CREATE TABLE IF NOT EXISTS chat_session
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,
    title       VARCHAR(128) NOT NULL DEFAULT '新对话',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_session_user ON chat_session (user_id);
CREATE INDEX IF NOT EXISTS idx_session_user_time ON chat_session (user_id, deleted, update_time, id);

CREATE TABLE IF NOT EXISTS chat_message
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT       NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     CLOB         NOT NULL,
    citations   CLOB,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_message_session ON chat_message (session_id, id);
CREATE INDEX IF NOT EXISTS idx_message_session_role ON chat_message (session_id, role, id);
CREATE INDEX IF NOT EXISTS idx_message_role_time ON chat_message (role, create_time, id);
CREATE INDEX IF NOT EXISTS idx_message_role_id ON chat_message (role, id);

CREATE TABLE IF NOT EXISTS chat_feedback
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id  BIGINT,
    session_id  BIGINT,
    user_id     BIGINT,
    rating      TINYINT      NOT NULL,
    issue_type  VARCHAR(32),
    comment     VARCHAR(1000),
    status      TINYINT      NOT NULL DEFAULT 0,
    admin_note  VARCHAR(1000),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 与真实库 V7 一致：一个用户对一条回答只能有一张工单（唯一键兼作 message_id 的查询索引）
CREATE UNIQUE INDEX IF NOT EXISTS uk_feedback_message_user ON chat_feedback (message_id, user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_status ON chat_feedback (status, rating);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON chat_feedback (user_id, create_time);

CREATE TABLE IF NOT EXISTS prompt_template
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    category       VARCHAR(32)  NOT NULL DEFAULT '概念解析',
    prompt_content CLOB         NOT NULL,
    description    VARCHAR(256),
    enabled        TINYINT      NOT NULL DEFAULT 1,
    sort_order     INT          NOT NULL DEFAULT 100,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_template_enabled ON prompt_template (enabled, sort_order);
CREATE INDEX IF NOT EXISTS idx_template_category ON prompt_template (category);

-- 种子模板：与 db/schema.sql、V6 迁移保持一致，按 title 判重
INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '学术摘要提炼', '学术总结',
       '请依据知识库资料，把关于 {topic} 的内容提炼成 200 字以内的学术摘要，先给核心结论，再列关键数据与适用边界，并注明依据的切片。',
       '把零散资料压成一段可直接进论文的摘要', 1, 10
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '学术摘要提炼');

INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '概念四步解析', '概念解析',
       '请用「定义 — 组成 — 工作原理 — 典型应用」四个层次解释 {topic}，每一层都指明依据的资料段落，最后用一句话点出最常见的误解。',
       '四步框架拆解一个概念，适合复习首轮精读', 1, 20
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '概念四步解析');

INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '易混概念辨析', '概念解析',
       '围绕 {topic}，请指出我最可能混淆的相近概念，给出区分标准、一句话记忆口诀，以及一道能暴露差异的判断题。',
       '专治「两个概念看着都一样」', 1, 30
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '易混概念辨析');

INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '对比分析表格', '对比分析',
       '请把资料中涉及 {topic} 的内容整理成对比表格，列为对比对象，行为对比维度，表格外补充一段它们各自的优劣势与适用场景。',
       '横向对比多个方案，输出成表', 1, 40
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '对比分析表格');

INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '复习提纲生成', '学术总结',
       '请把 {topic} 所在章节整理成三级复习提纲，每个叶子节点不超过 15 字，并标注对应的 PDF 页码，便于我回到原文核对。',
       '一页提纲带走，回原文有锚点', 1, 50
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '复习提纲生成');

INSERT INTO prompt_template (title, category, prompt_content, description, enabled, sort_order)
SELECT '自测出题官', '自测出题',
       '请基于资料中 {topic} 相关内容出 5 道自测题，难度从概念识记到应用分析递进；先只给题干，等我作答后再逐题公布答案与解析。',
       '先出题后揭答案，模拟自测节奏', 1, 60
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE title = '自测出题官');
