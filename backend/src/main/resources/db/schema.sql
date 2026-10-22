-- 私有知识库问答系统（RAG）初始化脚本
-- 用法：mysql -uroot -p < schema.sql

CREATE DATABASE IF NOT EXISTS kb_rag DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE kb_rag;

-- 系统用户表：JWT 登录 + RBAC 角色（增量脚本见 db/migration/V4__auth_rbac.sql、V7__security_hardening.sql）
CREATE TABLE IF NOT EXISTS sys_user
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)     NOT NULL COMMENT '登录用户名，全局唯一',
    password    VARCHAR(128)    NOT NULL COMMENT 'BCrypt 密文，绝不存明文',
    nickname    VARCHAR(64)              DEFAULT NULL COMMENT '展示昵称',
    role        VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 管理员 / USER 普通用户',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
    token_version INT           NOT NULL DEFAULT 0 COMMENT '令牌版本号：递增即作废此前签发的全部 JWT（增量脚本见 V7）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户';

-- 体验账号（密码为 BCrypt 密文）：admin/admin123(ADMIN)、student/123456(USER)
INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 1, 'admin', '$2a$10$W07oeVJepI477BktFAZBFer5Vea0oKVU8ApbqJnL3XVgW8MGJsQzG', '系统管理员', 'ADMIN', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 2, 'student', '$2a$10$.DGxHu5odVethPPz5bRK5ekuAEwhAP.jLxPvyGTb4Ma3ZS4PH5MN6', '体验学生', 'USER', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'student');

-- 知识库空间表：一个空间 = 一个知识库，文档归属到空间下（增量脚本见 db/migration/V3__multi_space.sql）
CREATE TABLE IF NOT EXISTS kb_space
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '空间ID',
    name        VARCHAR(128)    NOT NULL COMMENT '知识库名称',
    description VARCHAR(512)             DEFAULT NULL COMMENT '知识库描述',
    created_by  BIGINT UNSIGNED          DEFAULT NULL COMMENT '创建人用户ID',
    doc_count   INT             NOT NULL DEFAULT 0 COMMENT '文档数（冗余统计，recount 时刷新）',
    chunk_count INT             NOT NULL DEFAULT 0 COMMENT '分段数（冗余统计，recount 时刷新）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_space_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库空间';

-- 默认空间（固定 id=1），可重复执行不重复插入
INSERT INTO kb_space (id, name, description, created_by)
SELECT 1, '默认知识库', '系统初始化的默认空间，历史文档自动归入此库', 1
WHERE NOT EXISTS (SELECT 1 FROM kb_space WHERE id = 1);

-- 空间成员授权：一行 = 「某用户可读某空间」。增量脚本见 db/migration/V9__space_member.sql。
-- ADMIN 角色在代码里直接放行全部空间，不依赖本表；未授权空间在召回 SQL 里就被排除。
CREATE TABLE IF NOT EXISTS kb_space_member
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    space_id    BIGINT UNSIGNED NOT NULL COMMENT '知识库空间ID',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '授权到的用户ID',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_user (space_id, user_id),
    KEY idx_member_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库空间成员授权';

-- 回填 1：空间创建人自动成为本空间成员（历史空间没有 created_by 的归到 admin）
INSERT IGNORE INTO kb_space_member (space_id, user_id)
SELECT s.id, COALESCE(s.created_by, 1) FROM kb_space s WHERE s.deleted = 0;

-- 回填 2：默认空间(1) 对全部在册用户开放，保证收紧权限后既有账号照常问答
INSERT IGNORE INTO kb_space_member (space_id, user_id)
SELECT 1, u.id FROM sys_user u WHERE u.deleted = 0;

-- 文档主表
CREATE TABLE IF NOT EXISTS kb_document
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    space_id    BIGINT UNSIGNED          DEFAULT NULL COMMENT '所属知识库空间ID',
    title       VARCHAR(255)    NOT NULL COMMENT '文档标题（去掉后缀的文件名）',
    file_type   VARCHAR(32)     NOT NULL COMMENT '文件类型：pdf/docx/md...',
    file_path   VARCHAR(512)             DEFAULT NULL COMMENT '落盘相对路径',
    file_size   BIGINT                   DEFAULT NULL COMMENT '文件字节数',
    status      VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSING/INDEXED/FAILED',
    chunk_count INT             NOT NULL DEFAULT 0 COMMENT '分段数量',
    error_msg   VARCHAR(500)             DEFAULT NULL COMMENT '失败原因',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_doc_status (status),
    KEY idx_doc_title (title),
    KEY idx_doc_space (space_id),
    KEY idx_doc_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档';

-- 文档分段表（RAG 检索单元）
CREATE TABLE IF NOT EXISTS kb_chunk
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分段ID',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '所属文档ID',
    chunk_index INT             NOT NULL COMMENT '文档内序号，重建向量库时按此顺序回放',
    page_number INT                      DEFAULT NULL COMMENT 'PDF 起始页码（从1开始），非PDF为空',
    content     MEDIUMTEXT      NOT NULL COMMENT '分段正文',
    char_count  INT                      DEFAULT NULL COMMENT '字符数',
    enabled     TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用：1召回 0禁用',
    vector_ref  VARCHAR(128)             DEFAULT NULL COMMENT '外部向量库ID，内存模式为空',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_chunk_doc (document_id),
    KEY idx_chunk_enabled (enabled),
    FULLTEXT KEY ft_chunk_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文档分段';

-- 会话表：一条记录 = 前端左侧历史列表里的一项
CREATE TABLE IF NOT EXISTS chat_session
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    user_id     BIGINT UNSIGNED          DEFAULT NULL COMMENT '所属用户ID，按用户隔离历史对话',
    title       VARCHAR(128)    NOT NULL DEFAULT '新对话' COMMENT '会话标题，首次提问后按问题前15字自动生成',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间，列表按此倒序',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_session_update_time (update_time),
    KEY idx_session_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='聊天会话';

-- 消息表：会话下的每一轮提问与回答（增量脚本见 db/migration/V2__chat_history.sql）
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

-- 问答反馈与纠偏工单表（建表见 db/migration/V6__feedback_and_template.sql，唯一键见 V7__security_hardening.sql）
CREATE TABLE IF NOT EXISTS chat_feedback
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工单ID',
    message_id  BIGINT UNSIGNED          DEFAULT NULL COMMENT '被评价的 assistant 消息ID（chat_message.id）',
    session_id  BIGINT UNSIGNED          DEFAULT NULL COMMENT '所属会话ID，消息被清理后仍可定位会话',
    user_id     BIGINT UNSIGNED          DEFAULT NULL COMMENT '提交反馈的用户ID',
    rating      TINYINT         NOT NULL COMMENT '评价：1 点赞 / -1 点踩',
    issue_type  VARCHAR(32)              DEFAULT NULL COMMENT '问题类型：检索漏检/答案幻觉/引用不准/其他，点赞为空',
    comment     VARCHAR(1000)            DEFAULT NULL COMMENT '用户补充说明',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '处理状态：0 待处理 / 1 已修复 / 2 已忽略',
    admin_note  VARCHAR(1000)            DEFAULT NULL COMMENT '管理员处理备注',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '处理时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_message_user (message_id, user_id),
    KEY idx_feedback_status (status, rating),
    KEY idx_feedback_user (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问答反馈与纠偏工单';

-- 快捷提示词模板表：聊天页胶囊的数据源（增量脚本见 db/migration/V6__feedback_and_template.sql）
CREATE TABLE IF NOT EXISTS prompt_template
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    title          VARCHAR(128)    NOT NULL COMMENT '模板名称，同时作为种子判重键',
    category       VARCHAR(32)     NOT NULL DEFAULT '概念解析' COMMENT '分类：学术总结/概念解析/对比分析/自测出题',
    prompt_content TEXT            NOT NULL COMMENT '模板正文，含 {topic} 占位符，点击后整体填入输入框',
    description    VARCHAR(256)             DEFAULT NULL COMMENT '一句话说明，展示在胶囊 title 提示上',
    enabled        TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用：1 聊天页可见 / 0 下架',
    sort_order     INT             NOT NULL DEFAULT 100 COMMENT '排序权重，越小越靠前',
    create_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_template_enabled (enabled, sort_order),
    KEY idx_template_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='快捷提示词模板';

-- 种子模板：{topic} 由用户自己替换，前端点击胶囊只是把整段前缀塞进输入框，不做变量替换
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
