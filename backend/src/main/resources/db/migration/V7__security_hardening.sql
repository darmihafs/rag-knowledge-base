-- ============================================================================
-- V7：安全加固（可吊销的登录态 + 反馈工单去重约束）
--
--   1) sys_user.token_version —— 令牌版本号。签发 JWT 时把它写进 tv 载荷，拦截器每次请求
--      与库中当前值比对；改密码 / 管理员重置密码 / 主动退出登录都会让它 +1，
--      从而一次性作废该用户此前签发的全部 token。补的是「JWT 无状态、泄露后无法吊销」这个洞。
--
--   2) chat_feedback 唯一键 uk_feedback_message_user(message_id, user_id) —— 一个用户对一条
--      回答只能有一张工单。此前只有普通索引，服务层「先查后写」在双击/并发下会插出两条，
--      质量指标（点赞率、BadCase 分布）随之失真；改成唯一键把这条规则交给数据库兜住。
--      加约束前先清理历史重复工单（同 (message_id, user_id) 只保留最早一条）。
--
-- 幂等：可重复执行；列/索引已存在则跳过对应 DDL。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V7__security_hardening.sql
-- ============================================================================

USE kb_rag;

-- ---------------------------------------------------------------- 1) 令牌版本号
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'token_version');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT ''令牌版本号：递增即作废此前签发的全部 JWT'' AFTER status',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- 2) 反馈工单唯一键
-- 先并历史重复：同 (message_id, user_id) 保留 id 最小的一条（最早那次评价），其余删除
DELETE f FROM chat_feedback f
    JOIN (SELECT message_id, user_id, MIN(id) AS keep_id
          FROM chat_feedback
          WHERE message_id IS NOT NULL
            AND user_id IS NOT NULL
          GROUP BY message_id, user_id
          HAVING COUNT(*) > 1) d
    ON f.message_id = d.message_id AND f.user_id = d.user_id AND f.id > d.keep_id;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'chat_feedback' AND INDEX_NAME = 'uk_feedback_message_user');
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE chat_feedback ADD UNIQUE KEY uk_feedback_message_user (message_id, user_id)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 唯一的 message_id 索引已能覆盖按消息查工单，旧的普通索引 idx_feedback_message 是它的前缀，
-- 属于纯冗余（白占写入开销）。与新库 schema.sql 的口径对齐：删掉。
SET @old_idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'chat_feedback' AND INDEX_NAME = 'idx_feedback_message');
SET @sql = IF(@old_idx_exists > 0,
              'ALTER TABLE chat_feedback DROP INDEX idx_feedback_message',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
