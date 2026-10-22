-- ============================================================================
-- 词法召回压测数据准备（一次性临时库 kb_rag_bench，跑完即 DROP，绝不碰 kb_rag）
--
--   目的：在 10 万条切片量级下，对比「逐词 LIKE 全表扫描」与「MATCH AGAINST 全文索引」
--   的真实延迟与命中情况，为 rag.lexical-mode=fulltext 这个默认值提供数字依据。
--
--   用法（本地 MySQL，命令行见 README「压测」一节）：
--     mysql -uroot -p --default-character-set=utf8mb4 < docs/bench/lexical_load_setup.sql
--
--   注意：本脚本建在 kb_rag_bench 这个独立 schema 上，只读 kb_rag 的表结构与 sys_user，
--   不写入 kb_rag 的任何业务数据；跑完用 lexical_load_teardown.sql 清理。
-- ============================================================================

DROP DATABASE IF EXISTS kb_rag_bench;
CREATE DATABASE kb_rag_bench DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE kb_rag_bench;

-- 结构与生产完全一致
CREATE TABLE kb_space LIKE kb_rag.kb_space;
CREATE TABLE kb_document LIKE kb_rag.kb_document;
CREATE TABLE kb_chunk LIKE kb_rag.kb_chunk;
CREATE TABLE sys_user LIKE kb_rag.sys_user;
INSERT INTO sys_user SELECT * FROM kb_rag.sys_user;

-- 先删掉 ngram 全文索引再灌数据：10 万行的索引构建放到最后一次性做，快一个数量级
ALTER TABLE kb_chunk DROP INDEX ft_chunk_content;
ALTER TABLE kb_chunk ADD KEY idx_chunk_enabled (enabled);

-- 四篇「文档」，让检索对比实验室能回显标题
INSERT INTO kb_space (id, name, description, doc_count, chunk_count, deleted)
VALUES (1, '压测语料库', '仅用于词法召回压测', 4, 0, 0);
INSERT INTO kb_document (id, space_id, title, file_type, status, chunk_count, deleted)
VALUES (1, 1, '计算机网络压测语料.pdf', 'pdf', 'INDEXED', 0, 0),
       (2, 1, '数据库压测语料.pdf', 'pdf', 'INDEXED', 0, 0),
       (3, 1, '中间件压测语料.pdf', 'pdf', 'INDEXED', 0, 0),
       (4, 1, '安全压测语料.pdf', 'pdf', 'INDEXED', 0, 0);

-- 四个词表，交叉组合出足够多彼此不同的技术句子
CREATE TABLE t_a (i INT PRIMARY KEY, s VARCHAR(32)) ENGINE = InnoDB;
CREATE TABLE t_b (i INT PRIMARY KEY, s VARCHAR(32)) ENGINE = InnoDB;
CREATE TABLE t_c (i INT PRIMARY KEY, s VARCHAR(32)) ENGINE = InnoDB;
CREATE TABLE t_d (i INT PRIMARY KEY, s VARCHAR(64)) ENGINE = InnoDB;
CREATE TABLE t_e (i INT PRIMARY KEY, s VARCHAR(16)) ENGINE = InnoDB;

INSERT INTO t_a VALUES (1, 'TCP'), (2, 'HTTP'), (3, 'DNS'), (4, 'MySQL'), (5, 'Redis'),
                       (6, 'Kafka'), (7, 'Nginx'), (8, 'JWT'), (9, 'B+ 树'), (10, '倒排索引'),
                       (11, '布隆过滤器'), (12, '消息队列');
INSERT INTO t_b VALUES (1, '滑动窗口'), (2, '拥塞控制'), (3, '三次握手'), (4, '连接池'),
                       (5, '索引下推'), (6, '心跳保活'), (7, '限流降级'), (8, '缓存过期'),
                       (9, '分区 rebalance'), (10, '幂等去重'), (11, '令牌刷新'), (12, '预取读缓存');
INSERT INTO t_c VALUES (1, '避免接收方缓冲区溢出'), (2, '把长尾延迟压回可接受区间'),
                       (3, '防止连接被中途重置'), (4, '减少频繁建连的开销'),
                       (5, '让范围查询少回表'), (6, '及时发现失联节点'),
                       (7, '挡住突发流量打穿后端'), (8, '避免脏数据长期驻留'),
                       (9, '保证消费者数量变化时仍能均衡'), (10, '消除重复消费带来的资损'),
                       (11, '让泄露的凭证尽快失效'), (12, '用一次顺序读换掉多次随机读');
INSERT INTO t_d VALUES (1, '实现时要留意超时与重传相互放大的问题'), (2, '上线前需要用压测确认阈值'),
                       (3, '在跨机房分区场景下会退化'), (4, '效果与数据分布高度相关'),
                       (5, '需要配合监控指标一起观察'), (6, '默认参数在小流量下并不最优'),
                       (7, '生产环境建议灰度放量再全量'), (8, '改动后必须回归检索质量'),
                       (9, '与底层解析器配置有关'), (10, '边界条件要同业务侧确认'),
                       (11, '该配置直接影响召回条数'), (12, '重建索引建议放在低峰期');

-- 变体表只用来把同一句话改写成不同副本，避免 12^4 爆炸
INSERT INTO t_e VALUES (1, '基线'), (2, '变体一'), (3, '变体二'), (4, '变体三'), (5, '变体四'),
                       (6, '变体五'), (7, '变体六'), (8, '变体七'), (9, '变体八'), (10, '变体九'),
                       (11, '变体十'), (12, '变体十一'), (13, '变体十二'), (14, '变体十三'),
                       (15, '变体十四'), (16, '变体十五'), (17, '变体十六'), (18, '变体十七'),
                       (19, '变体十八'), (20, '变体十九'), (21, '变体二十'), (22, '变体二十一'),
                       (23, '变体二十二'), (24, '变体二十三'), (25, '变体二十四'), (26, '变体二十五'),
                       (27, '变体二十六'), (28, '变体二十七'), (29, '变体二十八'), (30, '变体二十九'),
                       (31, '变体三十'), (32, '变体三十一'), (33, '变体三十二'), (34, '变体三十三'),
                       (35, '变体三十四'), (36, '变体三十五'), (37, '变体三十六'), (38, '变体三十七'),
                       (39, '变体三十八'), (40, '变体三十九'), (41, '变体四十'), (42, '变体四十一'),
                       (43, '变体四十二'), (44, '变体四十三'), (45, '变体四十四'), (46, '变体四十五'),
                       (47, '变体四十六'), (48, '变体四十七'), (49, '变体四十八'), (50, '变体四十九'),
                       (51, '变体五十'), (52, '变体五十一'), (53, '变体五十二'), (54, '变体五十三'),
                       (55, '变体五十四'), (56, '变体五十五'), (57, '变体五十六'), (58, '变体五十七'),
                       (59, '变体五十八'), (60, '变体五十九');

-- 12 * 12 * 12 * 60 = 103,680 条切片（每个 a/b/c 组合锁定一个 d，再乘 e 的 60 个副本）
INSERT INTO kb_chunk (document_id, chunk_index, content, char_count, enabled)
SELECT 1 + MOD(a.i + b.i + c.i, 4),
       ROW_NUMBER() OVER (ORDER BY a.i, b.i, c.i, e.i),
       CONCAT(a.s, ' 的 ', b.s, '，用于', c.s, '；', d.s, '（', e.s, '）'),
       CHAR_LENGTH(CONCAT(a.s, ' 的 ', b.s, '，用于', c.s, '；', d.s, '（', e.s, '）')),
       1
FROM t_a a
         CROSS JOIN t_b b
         CROSS JOIN t_c c
         CROSS JOIN t_d d
         CROSS JOIN t_e e
WHERE d.i = 1 + MOD(a.i + b.i + c.i, 12);

-- 数据就位后再建 ngram 全文索引，与 V8 迁移到真实库时的 DDL 完全一致
ALTER TABLE kb_chunk ADD FULLTEXT INDEX ft_chunk_content (content) WITH PARSER ngram;

DROP TABLE t_a;
DROP TABLE t_b;
DROP TABLE t_c;
DROP TABLE t_d;
DROP TABLE t_e;

SELECT COUNT(*)                AS chunks,
       SUM(enabled = 1)        AS enabled_chunks,
       ROUND(AVG(char_count), 1) AS avg_chars,
       MIN(char_count)         AS min_chars,
       MAX(char_count)         AS max_chars
FROM kb_chunk;

SELECT ROUND(data_length / 1024 / 1024, 1)  AS data_mb,
       ROUND(index_length / 1024 / 1024, 1) AS index_mb
FROM information_schema.tables
WHERE table_schema = 'kb_rag_bench' AND table_name = 'kb_chunk';
