-- 第六阶段增量脚本：多知识库空间 + 分块启停
-- 用法：mysql -uroot -p kb_rag < V3__multi_space.sql
--       （或在 IDEA 的 Database 面板里选中 kb_rag 后直接执行本文件）
-- 全部幂等：建表用 IF NOT EXISTS，加列/加索引先查 information_schema 再动态执行，可重复运行。

USE kb_rag;

-- 1. 知识库空间表：一个空间 = 一个知识库，文档归属到空间下
CREATE TABLE IF NOT EXISTS kb_space
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '空间ID',
    name        VARCHAR(128)    NOT NULL COMMENT '知识库名称',
    description VARCHAR(512)             DEFAULT NULL COMMENT '知识库描述',
    doc_count   INT             NOT NULL DEFAULT 0 COMMENT '文档数（冗余统计，recount 时刷新）',
    chunk_count INT             NOT NULL DEFAULT 0 COMMENT '分段数（冗余统计，recount 时刷新）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_space_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库空间';

-- 2. 默认空间（固定 id=1），已有文档全部归入此库；可重复执行不重复插入
INSERT INTO kb_space (id, name, description)
SELECT 1, '默认知识库', '系统初始化的默认空间，历史文档自动归入此库'
WHERE NOT EXISTS (SELECT 1 FROM kb_space WHERE id = 1);

-- 3. kb_document 增加 space_id 列（幂等）
SET @col_exists = (SELECT COUNT(*)
                   FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag'
                     AND TABLE_NAME = 'kb_document'
                     AND COLUMN_NAME = 'space_id');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE kb_document ADD COLUMN space_id BIGINT UNSIGNED DEFAULT NULL COMMENT ''所属知识库空间ID'' AFTER id',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3.1 回填历史文档到默认空间
UPDATE kb_document SET space_id = 1 WHERE space_id IS NULL;

-- 3.2 按回填结果重算各空间的冗余计数（升级路径下历史文档已存在，需同步 doc_count/chunk_count）
UPDATE kb_space s
SET s.doc_count   = (SELECT COUNT(*) FROM kb_document d WHERE d.space_id = s.id AND d.deleted = 0),
    s.chunk_count = (SELECT IFNULL(SUM(d.chunk_count), 0) FROM kb_document d WHERE d.space_id = s.id AND d.deleted = 0);

-- 3.3 space_id 索引（幂等）
SET @idx_exists = (SELECT COUNT(*)
                   FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'kb_rag'
                     AND TABLE_NAME = 'kb_document'
                     AND INDEX_NAME = 'idx_doc_space');
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE kb_document ADD KEY idx_doc_space (space_id)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. kb_chunk 增加 enabled 列（幂等）：1=参与检索，0=人工禁用不召回
SET @col_exists = (SELECT COUNT(*)
                   FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'kb_rag'
                     AND TABLE_NAME = 'kb_chunk'
                     AND COLUMN_NAME = 'enabled');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE kb_chunk ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''是否启用：1召回 0禁用'' AFTER char_count',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
