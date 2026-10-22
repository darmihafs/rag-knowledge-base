-- ============================================================================
-- V9：知识库空间成员授权（把「空间隔离」从管理员手选的一个过滤参数，变成对每个账号生效的边界）
--
--   V3 建了 kb_space、V8 之前 spaceId 才真正下推进召回 SQL，但「谁能看哪个空间」始终没有落库：
--   任何登录用户在 /api/knowledge/ask 里传任意 spaceId 都能检索到别人私有空间的切片，
--   答案里的引用还会直接带出原文片段——这是本文件要堵的口子。
--
--   模型：kb_space_member 一行 = 「某用户可读某空间」。
--     - ADMIN 角色在代码里直接放行全部空间，不需要成员行（否则新空间一建出来管理员自己看不见）；
--     - 普通用户只看得见自己有成员行的空间，未授权空间在检索 SQL 里就已被排除，
--       不是召回后再在内存里过滤（内存过滤会让 topK 被越权切片占满，等于没隔离）。
--
--   回填策略（关键是别让收紧权限把现有账号变成「问什么都没有」）：
--     1) 每个空间的 created_by 自动获得该空间成员资格，历史数据没有创建人的归到 admin(id=1)；
--     2) 默认空间(id=1，公共知识库语义)对全部在册用户开放，所以升级后既有账号照常问答；
--     3) 其余空间一律不自动授权，由管理员在「空间成员」里显式添加。
--
--   唯一键 (space_id, user_id) 兼作幂等保障：本脚本重复执行不会插出重复成员行，
--   授权接口也因此可以直接 insert ignore，不必先查再插（先查再插在并发双击下会重复）。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V9__space_member.sql
-- ============================================================================

USE kb_rag;

CREATE TABLE IF NOT EXISTS kb_space_member
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    space_id    BIGINT UNSIGNED NOT NULL COMMENT '知识库空间ID',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '授权到的用户ID',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_user (space_id, user_id),
    KEY idx_member_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库空间成员授权（一行=某用户可读某空间）';

-- 1) 空间创建人自动成为成员；历史空间没有 created_by 的，归到 admin(id=1)
INSERT IGNORE INTO kb_space_member (space_id, user_id)
SELECT s.id, COALESCE(s.created_by, 1)
FROM kb_space s
WHERE s.deleted = 0;

-- 2) 默认空间对所有在册用户开放（公共库），保证收紧后既有账号仍能正常问答
INSERT IGNORE INTO kb_space_member (space_id, user_id)
SELECT 1, u.id
FROM sys_user u
WHERE u.deleted = 0
  AND EXISTS (SELECT 1 FROM kb_space WHERE id = 1 AND deleted = 0);
