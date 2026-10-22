-- ============================================================================
-- V6：知识运营闭环（问答反馈工单）+ 快捷提示词模板
--   chat_feedback    ：每条 AI 回答的赞/踩留痕与管理员纠偏工单，rating 1 赞 / -1 踩，
--                      status 0 待处理 / 1 已修复 / 2 已忽略。
--   prompt_template  ：聊天页「快捷指令模板」的数据源，带 {topic} 占位符，管理员可增删改。
--
-- 幂等：可重复执行。建表用 IF NOT EXISTS；种子模板按 title 判重，不会越跑越多。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < V6__feedback_and_template.sql
-- ============================================================================

USE kb_rag;

-- 问答反馈与纠偏工单
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
    KEY idx_feedback_message (message_id),
    KEY idx_feedback_status (status, rating),
    KEY idx_feedback_user (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问答反馈与纠偏工单';

-- 快捷提示词模板
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

-- ---------------------------------------------------------------- 种子模板
-- 只填 {topic} 一个占位符：前端点击胶囊是把整段前缀塞进输入框，由用户补主题词，
-- 因此模板本身必须「读起来就像一句完整的话」，不能依赖后端做变量替换。

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
