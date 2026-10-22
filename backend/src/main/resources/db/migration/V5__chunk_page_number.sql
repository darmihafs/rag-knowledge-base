-- ============================================================================
-- V5：切片页码定位（PDF Citation Highlight 的数据基础）
--   kb_chunk 增加 page_number：PDF 分段记录其起始页码（从 1 开始），
--   非 PDF（docx/md/txt…）没有稳定的页概念，保持为 NULL。
--
-- 用途：流式问答的引用（Citation）据此带出 pageNumber，前端原文溯源抽屉
--       打开 PDF 后直接 #page=N 跳到命中页。
--
-- 幂等：可重复执行；列已存在则跳过 ALTER。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V5__chunk_page_number.sql
-- ============================================================================

USE kb_rag;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'page_number');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE kb_chunk ADD COLUMN page_number INT DEFAULT NULL COMMENT ''PDF 起始页码(从1开始)，非PDF为空'' AFTER chunk_index',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
