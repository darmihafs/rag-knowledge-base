-- ============================================================================
-- V4：用户认证与 RBAC 权限隔离
--   1) 新建 sys_user 用户表，内置两个体验账号（密码为 BCrypt 密文，不可逆）
--   2) chat_session 增加 user_id，历史会话回填归属给管理员(id=1)
--   3) kb_space 增加 created_by，历史知识库回填归属给管理员(id=1)
--
-- 幂等：可重复执行；已存在的表/列不会重复创建，已存在的种子账号不会重复插入。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V4__auth_rbac.sql
-- ============================================================================

USE kb_rag;

-- ---------------------------------------------------------------------------
-- 1. sys_user 用户表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)     NOT NULL COMMENT '登录用户名，全局唯一',
    password    VARCHAR(128)    NOT NULL COMMENT 'BCrypt 密文，绝不存明文',
    nickname    VARCHAR(64)              DEFAULT NULL COMMENT '展示昵称',
    role        VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 管理员 / USER 普通用户',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户';

-- 体验账号（密码为 BCrypt 密文）：
--   admin   / admin123  -> ADMIN
--   student / 123456    -> USER
INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 1, 'admin', '$2a$10$W07oeVJepI477BktFAZBFer5Vea0oKVU8ApbqJnL3XVgW8MGJsQzG', '系统管理员', 'ADMIN', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user (id, username, password, nickname, role, status)
SELECT 2, 'student', '$2a$10$.DGxHu5odVethPPz5bRK5ekuAEwhAP.jLxPvyGTb4Ma3ZS4PH5MN6', '体验学生', 'USER', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'student');

-- ---------------------------------------------------------------------------
-- 2. chat_session.user_id：会话归属，用于按用户隔离历史对话
-- ---------------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'user_id');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE chat_session ADD COLUMN user_id BIGINT UNSIGNED DEFAULT NULL COMMENT ''所属用户ID'' AFTER id',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史会话（无主）统一回填给管理员(id=1)
UPDATE chat_session SET user_id = 1 WHERE user_id IS NULL;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_user');
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE chat_session ADD KEY idx_session_user (user_id)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. kb_space.created_by：知识库创建人
-- ---------------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'kb_space' AND COLUMN_NAME = 'created_by');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE kb_space ADD COLUMN created_by BIGINT UNSIGNED DEFAULT NULL COMMENT ''创建人用户ID'' AFTER description',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史知识库（含默认库）统一回填给管理员(id=1)
UPDATE kb_space SET created_by = 1 WHERE created_by IS NULL;
