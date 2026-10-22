-- ============================================================================
-- V8：词法召回改走全文索引（把闲置的 FULLTEXT ngram 索引真正用起来）
--
--   背景：词法召回原本是「逐词 LIKE '%词%' + 每词一次分页查询」，每次提问展开十几个词
--   就是十几次全表扫描。语料只有几百条时无感，上万条后延迟线性恶化。
--   kb_chunk 上的 ft_chunk_content(content) WITH PARSER ngram 早在 V3 就随建表语句写好了，
--   但一直没被任何查询使用——本脚本负责保证它在存量库里存在，配合
--   rag.lexical-mode=fulltext（默认）让 RetrievalServiceImpl 用
--   MATCH(content) AGAINST(... IN BOOLEAN MODE) 一条 SQL 完成匹配、打分与排序。
--
--   ngram 说明：MySQL 8 自带 ngram 解析器，默认 ngram_token_size=2，正好与
--   RetrievalServiceImpl.buildTerms 的中文 2-gram 展开口径一致；
--   如果改过 ngram_token_size，索引需要 DROP 后重建才生效。
--
--   降级：若索引缺失或数据库不支持（例如 H2），SQL 异常会被捕获并自动退回 LIKE 分支，
--   测试环境由 application-test.yml 的 rag.lexical-mode=like 直接锁定兜底路径。
--
-- 幂等：可重复执行；索引已存在则跳过。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V8__lexical_fulltext.sql
-- ============================================================================

USE kb_rag;

-- 100 万级正文建索引会比较久，低峰期执行；期间可正常读写，只是 DDL 占用 IO。
SET @ft_exists = (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'kb_chunk' AND INDEX_NAME = 'ft_chunk_content');
SET @sql = IF(@ft_exists = 0,
              'ALTER TABLE kb_chunk ADD FULLTEXT KEY ft_chunk_content (content) WITH PARSER ngram',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 顺带确认停用切片不会污染召回：全文查询里带 enabled=1 过滤，这里补一个组合索引，
-- 让「启用中 + 全文命中」在数据量大时仍走索引而不是回表过滤。
SET @idx_exists = (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'kb_rag' AND TABLE_NAME = 'kb_chunk' AND INDEX_NAME = 'idx_chunk_enabled');
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE kb_chunk ADD KEY idx_chunk_enabled (enabled)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
