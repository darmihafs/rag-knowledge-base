-- ============================================================================
-- 词法召回压测收尾：删掉临时库，确认 kb_rag 没被碰过
--   用法：mysql -uroot -p --default-character-set=utf8mb4 < docs/bench/lexical_load_teardown.sql
-- ============================================================================

DROP DATABASE IF EXISTS kb_rag_bench;

-- 只剩生产库，且行数仍是压测前的量级（临时库不会影响这两条计数）
SELECT schema_name
FROM information_schema.schemata
WHERE schema_name LIKE 'kb_rag%';

SELECT (SELECT COUNT(*) FROM kb_rag.kb_chunk)   AS kb_rag_chunks,
       (SELECT COUNT(*) FROM kb_rag.kb_document) AS kb_rag_documents,
       (SELECT COUNT(*) FROM kb_rag.sys_user)    AS kb_rag_users;
