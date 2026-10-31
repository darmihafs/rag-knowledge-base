-- ============================================================================
-- V10：慢查询联合索引 + 上传内容去重列
--
--   背景：审计发现三处查询在数据量上来后会退化成全表扫描 / filesort：
--     1) 审计与大盘按 role='user' 倒序分页、按 create_time 统计近 7 日走势；
--     2) 会话内按 role 统计提问数、按 session+role 配对回答；
--     3) 切片列表按 document_id + chunk_index 排序、按 document_id + enabled 过滤。
--
--   索引设计原则：等值列在前、排序列在后，让 MySQL 能在索引内完成过滤与排序。
--   例如 idx_message_role_time(role, create_time, id) 覆盖
--   「where role='user' and create_time >= ? order by create_time/id」。
--
--   另外增加 kb_document.file_hash：上传时流式计算 SHA-256，
--   同一空间 + 同标题 + 同类型 + 同哈希视为重复上传，直接拒绝，避免双击 / 刷新造成重复入库。
--
--   注意：MySQL 8.0 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS，
--   本脚本只需在升级时执行一次；重复执行会因「列/索引已存在」报错，不会破坏数据。
--   执行：mysql -uroot -p --default-character-set=utf8mb4 < V10__query_indexes_and_dedup.sql
-- ============================================================================

USE kb_rag;

-- 1) 文档：内容哈希 + 去重键 + 空间维度查询
ALTER TABLE kb_document
    ADD COLUMN file_hash CHAR(64) NULL COMMENT '上传文件 SHA-256，用于同空间同标题同类型去重',
    ADD KEY idx_doc_hash (space_id, title, file_hash),
    ADD KEY idx_doc_space_status (space_id, status),
    ADD KEY idx_doc_space_time (space_id, create_time),
    -- 空间文档列表：where space_id=? and deleted=0 order by id desc
    ADD KEY idx_doc_space_del_id (space_id, deleted, id),
    -- 启动恢复：where status in (...) and deleted=0 order by id
    ADD KEY idx_doc_status_del_id (status, deleted, id);

-- 2) 分段：按文档取切片 / 排序 / 启用过滤
ALTER TABLE kb_chunk
    ADD KEY idx_chunk_doc_enabled (document_id, enabled),
    ADD KEY idx_chunk_doc_index (document_id, chunk_index),
    ADD KEY idx_chunk_enabled_doc (enabled, document_id);

-- 3) 消息：审计按角色倒序分页、近 7 日走势、会话内按角色统计与配对
ALTER TABLE chat_message
    ADD KEY idx_message_session_role (session_id, role, id),
    ADD KEY idx_message_role_time (role, create_time, id),
    -- 问答审计：where role='user' order by id desc（补上 role 与 id 之间不能有排序列的缺口）
    ADD KEY idx_message_role_id (role, id);

-- 4) 会话：用户历史列表 where user_id=? and deleted=0 order by update_time desc, id desc
ALTER TABLE chat_session
    ADD KEY idx_session_user_time (user_id, deleted, update_time, id);
