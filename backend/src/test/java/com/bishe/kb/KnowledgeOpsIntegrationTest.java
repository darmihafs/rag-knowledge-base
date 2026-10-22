package com.bishe.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第十一阶段「知识运营闭环」的四组接口集成测试：
 * 全局切片语料中心 / 问答反馈与 BadCase 工单 / 提示词模板市场 / 个人中心与安全设置。
 *
 * <p>同第十阶段一样关掉测试放行（{@code auth.test-bypass=false}）用真实 JWT 跑 RBAC，
 * 因为这一批接口有一个明确的权限分界：语料中心与工单处理仅管理员，
 * 而「提交反馈」「读启用中模板」「个人中心」是普通用户的本职功能，必须放行。
 *
 * <p>数据全部落在 H2 内存库；{@code @BeforeEach} 重建确定性种子，
 * {@code @AfterEach} 把被密码用例改动的账号密文与昵称还原，避免污染同 JVM 内其他测试类。
 */
@SpringBootTest(properties = "auth.test-bypass=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeOpsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** schema-h2.sql 的出厂种子，密码用例结束后要还原成它 */
    private static final String ADMIN_SEED_HASH = "$2a$10$W07oeVJepI477BktFAZBFer5Vea0oKVU8ApbqJnL3XVgW8MGJsQzG";
    private static final String STUDENT_SEED_HASH = "$2a$10$.DGxHu5odVethPPz5bRK5ekuAEwhAP.jLxPvyGTb4Ma3ZS4PH5MN6";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String studentToken;

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.update("delete from chat_feedback");
        jdbcTemplate.update("delete from prompt_template");
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");
        jdbcTemplate.update("delete from sys_user where id > 2");

        adminToken = login("admin", "admin123");
        studentToken = login("student", "123456");

        // 两个空间、两篇文档、四条切片：够覆盖 spaceId / documentId / enabled / keyword 四种过滤组合
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (1, '默认知识库', '系统默认', 1, 2, 0)");
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (2, '论文资料库', '阶段十一新增', 1, 2, 0)");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (1, 1, '计算机网络.pdf', 'pdf', 'INDEXED', 2, 0)");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (2, 2, '机器学习笔记.md', 'md', 'INDEXED', 2, 0)");
        insertChunk(1, 1, 0, 3, "TCP 使用滑动窗口进行流量控制，窗口大小由接收方决定", 1);
        insertChunk(2, 1, 1, 4, "滑动窗口机制可以避免接收方缓冲区溢出", 1);
        insertChunk(3, 2, 0, null, "梯度下降通过反向传播更新参数", 1);
        insertChunk(4, 2, 1, null, "过拟合时先检查正则化系数与训练集规模", 0);

        // 学生的会话（可反馈）与管理员的会话（用来验证越权反馈被拒）
        jdbcTemplate.update("insert into chat_session (id, user_id, title, deleted) values (1, 2, '关于滑动窗口', 0)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (1, 1, 'user', 'TCP 的滑动窗口是做什么的', null)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (2, 1, 'assistant', '滑动窗口用于流量控制，接收方通过窗口大小限制发送方速率', "
                + "'[{\"documentId\":1,\"title\":\"计算机网络.pdf\",\"chunkIndex\":0,\"pageNumber\":3}]')");
        jdbcTemplate.update("insert into chat_session (id, user_id, title, deleted) values (2, 1, '管理员自测', 0)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (3, 2, 'user', '梯度下降怎么调学习率', null)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (4, 2, 'assistant', '建议从小学习率开始并配合学习率衰减', null)");

        // 三条模板：两条启用、一条下架，用于验证 /templates/active 的过滤与排序
        insertTemplate(1, "概念四步解析", "概念解析", 1, 10);
        insertTemplate(2, "自测出题官", "自测出题", 1, 20);
        insertTemplate(3, "旧版摘要指令", "学术总结", 0, 30);
    }

    @AfterEach
    void restoreAccounts() {
        jdbcTemplate.update("update sys_user set password = ?, nickname = ? where id = 1",
                ADMIN_SEED_HASH, "系统管理员");
        jdbcTemplate.update("update sys_user set password = ?, nickname = ? where id = 2",
                STUDENT_SEED_HASH, "体验学生");
        // 改密/重置用例会把 token_version 顶上去。这里复位只是让种子保持与 schema-h2.sql 一致：
        // 真正的安全性在于每个用例的 @BeforeEach 都重新登录，token 永远和当前版本号配对。
        jdbcTemplate.update("update sys_user set token_version = 0 where id in (1, 2)");
    }

    // ------------------------------------------------------------------ 全局切片语料中心

    @Test
    @DisplayName("GET /api/chunks/page：跨文档分页，每行带所属文档标题与空间名称")
    void corpusPageShouldCarryOwnership() throws Exception {
        JsonNode data = okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10")));

        assertThat(data.get("total").asLong()).isEqualTo(4L);
        JsonNode records = data.get("records");
        assertThat(records).hasSize(4);
        // ID 倒序：切片 4 在前
        assertThat(records.get(0).get("id").asLong()).isEqualTo(4L);
        assertThat(records.get(0).get("documentTitle").asText()).isEqualTo("机器学习笔记.md");
        assertThat(records.get(0).get("spaceName").asText()).isEqualTo("论文资料库");
        assertThat(records.get(0).get("enabled").asInt()).isZero();
        assertThat(records.get(0).get("fileType").asText()).isEqualTo("md");
        // 非 PDF 没有页码，序列化后为 null 而不是伪造 0
        assertThat(records.get(0).get("pageNumber").isNull()).isTrue();
        assertThat(records.get(3).get("pageNumber").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /api/chunks/page：spaceId / documentId / enabled / keyword 四个条件可独立生效")
    void corpusPageShouldFilterByFourConditions() throws Exception {
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&spaceId=2")))
                .get("total").asLong()).isEqualTo(2L);
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&documentId=1")))
                .get("total").asLong()).isEqualTo(2L);
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&enabled=0")))
                .get("records").get(0).get("id").asLong()).isEqualTo(4L);
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&enabled=1")))
                .get("total").asLong()).isEqualTo(3L);
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&keyword=滑动窗口")))
                .get("total").asLong()).isEqualTo(2L);
        // 组合：论文空间里含「窗口」的切片 -> 没有
        assertThat(okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&spaceId=2&keyword=窗口")))
                .get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("GET /api/chunks/page：空间下还没有文档时直接空页，不再打无谓的 SQL")
    void corpusPageShouldHandleEmptySpace() throws Exception {
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (3, '空空间', '还没有文档', 0, 0, 0)");

        JsonNode data = okData(admin(get("/api/chunks/page?pageNum=1&pageSize=10&spaceId=3")));
        assertThat(data.get("total").asLong()).isZero();
        assertThat(data.get("records")).isEmpty();
    }

    @Test
    @DisplayName("POST /api/chunks/batch-status：批量禁用后库里状态同步，重复提交不再产生变更")
    void batchStatusShouldUpdateAndBeIdempotent() throws Exception {
        assertThat(okData(admin(postJson("/api/chunks/batch-status",
                Map.of("ids", List.of(1, 2), "enabled", 0)))).asInt()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from kb_chunk where enabled = 0", Integer.class))
                .isEqualTo(3);

        // 已经是目标状态：返回 0 条变更，不重复写库
        assertThat(okData(admin(postJson("/api/chunks/batch-status",
                Map.of("ids", List.of(1), "enabled", 0)))).asInt()).isZero();

        // 混合状态：1、2 由禁用转启用、3 本来就启用不变 -> 只算 3 条
        assertThat(okData(admin(postJson("/api/chunks/batch-status",
                Map.of("ids", List.of(1, 2, 3, 4), "enabled", 1)))).asInt()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select count(*) from kb_chunk where enabled = 1", Integer.class))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("POST /api/chunks/batch-status：空列表 4001、非法状态 4001、全部不存在 4040")
    void batchStatusShouldRejectBadInput() throws Exception {
        assertThat(perform(admin(postJson("/api/chunks/batch-status", Map.of("ids", List.of(), "enabled", 0))))
                .get("code").asInt()).isEqualTo(4001);
        assertThat(perform(admin(postJson("/api/chunks/batch-status", Map.of("ids", List.of(1), "enabled", 5))))
                .get("code").asInt()).isEqualTo(4001);
        assertThat(perform(admin(postJson("/api/chunks/batch-status", Map.of("ids", List.of(99999), "enabled", 0))))
                .get("code").asInt()).isEqualTo(4040);
        // 拒绝发生在写库前
        assertThat(jdbcTemplate.queryForObject("select count(*) from kb_chunk where enabled = 1", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("GET /api/chunks/corpus-stats：全库口径的切片量与启停分布")
    void corpusStatsShouldReportWholeLibrary() throws Exception {
        JsonNode data = okData(admin(get("/api/chunks/corpus-stats")));

        assertThat(data.get("totalChunks").asLong()).isEqualTo(4L);
        assertThat(data.get("enabledChunks").asLong()).isEqualTo(3L);
        assertThat(data.get("disabledChunks").asLong()).isEqualTo(1L);
        assertThat(data.get("indexedDocuments").asLong()).isEqualTo(2L);
        assertThat(data.get("activeSpaces").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("语料中心是治理入口：普通用户访问切片接口 -> 403")
    void normalUserCannotTouchCorpus() throws Exception {
        mockMvc.perform(withToken(get("/api/chunks/page?pageNum=1&pageSize=10"), studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/chunks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"enabled\":0}"), studentToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 问答反馈与 BadCase 工单

    @Test
    @DisplayName("POST /api/feedback：普通用户点踩自己的回答即可成单，工单归属取 JWT 身份")
    void normalUserCanSubmitBadCase() throws Exception {
        JsonNode data = okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "答案幻觉", "comment", "漏了拥塞控制"))));

        assertThat(data.get("messageId").asLong()).isEqualTo(2L);
        assertThat(data.get("rating").asInt()).isEqualTo(-1);
        assertThat(data.get("issueType").asText()).isEqualTo("答案幻觉");
        // 归属由服务端写入，不是前端传的
        Integer ownerId = jdbcTemplate.queryForObject(
                "select user_id from chat_feedback where message_id = 2", Integer.class);
        assertThat(ownerId).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select status from chat_feedback where message_id = 2", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("POST /api/feedback：同一回答改评只有一条工单，状态退回待处理")
    void repeatedFeedbackShouldUpdateNotDuplicate() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "引用不准", "comment", "页码对不上"))));

        JsonNode up = okData(student(postJson("/api/feedback", Map.of("messageId", 2, "rating", 1))));
        assertThat(up.get("rating").asInt()).isEqualTo(1);
        // 点赞不保留归因与说明，避免「好评却写着问题类型」的脏数据
        assertThat(up.has("issueType") && !up.get("issueType").isNull()).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_feedback", Integer.class)).isEqualTo(1);

        // 管理员先结单，用户再改评 -> 状态必须回到待处理
        long ticketId = jdbcTemplate.queryForObject("select id from chat_feedback limit 1", Long.class);
        okData(admin(putJson("/api/admin/feedback/" + ticketId + "/resolve",
                Map.of("status", 1, "adminNote", "已补充引用"))));
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "检索漏检"))));
        assertThat(jdbcTemplate.queryForObject("select status from chat_feedback where id = ?",
                Integer.class, ticketId)).isZero();
    }

    @Test
    @DisplayName("唯一键 uk_feedback_message_user：绕开服务层重复插同一工单被数据库挡下（V7）")
    void duplicateFeedbackRowShouldBeRejectedByUniqueKey() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "答案幻觉"))));
        long keep = ticketIdOf(2);

        // 直接撞约束，验证防护在数据库这一层，而不是只靠服务里的「先查后写」
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into chat_feedback (message_id, user_id, session_id, rating, status) values (2, 2, 1, 1, 0)"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_feedback", Integer.class)).isEqualTo(1);

        // 换个人评价同一条回答不受影响：唯一键的粒度是「回答 × 用户」
        jdbcTemplate.update(
                "insert into chat_feedback (message_id, user_id, session_id, rating, status) values (2, 1, 1, 1, 0)");
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_feedback", Integer.class)).isEqualTo(2);

        // 有唯一键之后，服务层的改评仍然是「合并到既有工单」而不是新增或报错
        okData(student(postJson("/api/feedback", Map.of("messageId", 2, "rating", 1))));
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_feedback where user_id = 2", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select rating from chat_feedback where id = ?", Integer.class, keep))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/feedback：不能评价别人的回答；消息或角色不合法按不存在处理")
    void feedbackShouldRejectForeignAndMissingMessage() throws Exception {
        // 消息 4 属于管理员的会话
        assertThat(perform(student(postJson("/api/feedback",
                Map.of("messageId", 4, "rating", -1)))).get("code").asInt()).isEqualTo(4030);
        assertThat(perform(student(postJson("/api/feedback",
                Map.of("messageId", 99999, "rating", 1)))).get("code").asInt()).isEqualTo(4040);
        // 提问消息不是「回答」，没有评价意义
        assertThat(perform(student(postJson("/api/feedback",
                Map.of("messageId", 1, "rating", 1)))).get("code").asInt()).isEqualTo(4040);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_feedback", Integer.class)).isZero();
    }

    @Test
    @DisplayName("POST /api/feedback：管理员也能提交反馈（替用户补录归因）")
    void adminCanAlsoSubmitFeedback() throws Exception {
        JsonNode data = okData(admin(postJson("/api/feedback",
                Map.of("messageId", 4, "rating", -1, "issueType", "瞎写的类型"))));
        // 非法归因不报错，落到「其他」兜底，保证工单一定能被统计
        assertThat(data.get("issueType").asText()).isEqualTo("其他");
    }

    @Test
    @DisplayName("GET /api/admin/feedback/page：工单带当时的提问、回答与提交人")
    void adminPageShouldJoinQaContext() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "答案幻觉", "comment", "漏了拥塞控制"))));

        JsonNode data = okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10")));
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        JsonNode row = data.get("records").get(0);
        assertThat(row.get("username").asText()).isEqualTo("student");
        assertThat(row.get("nickname").asText()).isEqualTo("体验学生");
        assertThat(row.get("question").asText()).isEqualTo("TCP 的滑动窗口是做什么的");
        assertThat(row.get("answer").asText()).contains("流量控制");
        assertThat(row.get("status").asInt()).isZero();
        assertThat(row.get("comment").asText()).isEqualTo("漏了拥塞控制");
    }

    @Test
    @DisplayName("GET /api/admin/feedback/page：rating / status / issueType 三个条件可筛选")
    void adminPageShouldFilter() throws Exception {
        okData(student(postJson("/api/feedback", Map.of("messageId", 2, "rating", 1))));
        // 消息 4 属于管理员自己的会话，由管理员来点踩（学生的越权用例在前面单独验证）
        okData(admin(postJson("/api/feedback",
                Map.of("messageId", 4, "rating", -1, "issueType", "引用不准"))));
        long downTicket = ticketIdOf(4);

        assertThat(okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10&rating=-1")))
                .get("total").asLong()).isEqualTo(1L);
        okData(admin(putJson("/api/admin/feedback/" + downTicket + "/resolve",
                Map.of("status", 2, "adminNote", "资料本身没有该内容"))));
        assertThat(okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10&status=2")))
                .get("records").get(0).get("id").asLong()).isEqualTo(downTicket);
        assertThat(okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10&issueType=答案幻觉")))
                .get("total").asLong()).isZero();
        assertThat(okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10&issueType=引用不准")))
                .get("total").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("PUT /api/admin/feedback/{id}/resolve：结单必须写备注，非法状态被 DTO 挡下")
    void resolveShouldRequireNote() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "检索漏检"))));

        long ticket = ticketIdOf(2);

        assertThat(perform(admin(putJson("/api/admin/feedback/" + ticket + "/resolve", Map.of("status", 1))))
                .get("code").asInt()).isEqualTo(4001);
        assertThat(perform(admin(putJson("/api/admin/feedback/" + ticket + "/resolve", Map.of("status", 9))))
                .get("code").asInt()).isEqualTo(4001);
        assertThat(perform(admin(putJson("/api/admin/feedback/" + (ticket + 9999) + "/resolve",
                Map.of("status", 1, "adminNote", "x")))).get("code").asInt()).isEqualTo(4040);

        JsonNode resolved = okData(admin(putJson("/api/admin/feedback/" + ticket + "/resolve",
                Map.of("status", 1, "adminNote", "已把该切片设为禁用并补传资料"))));
        assertThat(resolved.get("status").asInt()).isEqualTo(1);
        assertThat(resolved.get("adminNote").asText()).contains("补传资料");
        assertThat(resolved.get("question").asText()).isEqualTo("TCP 的滑动窗口是做什么的");
    }

    @Test
    @DisplayName("GET /api/admin/feedback/stats：好评率、待处理 BadCase 与问题类型占比")
    void statsShouldSummarizeQuality() throws Exception {
        okData(student(postJson("/api/feedback", Map.of("messageId", 2, "rating", 1))));
        okData(admin(postJson("/api/feedback",
                Map.of("messageId", 4, "rating", -1, "issueType", "答案幻觉"))));
        // 再给学生的会话补一条点踩，凑成「1 赞 2 踩」
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (5, 1, 'assistant', '第二次回答仍然漏了拥塞控制', null)");
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 5, "rating", -1, "issueType", "检索漏检"))));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_feedback where rating = -1 and status = 0", Long.class)).isEqualTo(2L);

        JsonNode data = okData(admin(get("/api/admin/feedback/stats")));
        assertThat(data.get("totalFeedback").asLong()).isEqualTo(3L);
        assertThat(data.get("thumbsUp").asLong()).isEqualTo(1L);
        assertThat(data.get("thumbsDown").asLong()).isEqualTo(2L);
        assertThat(data.get("positiveRate").asDouble()).isCloseTo(0.3333, offset(0.001));
        assertThat(data.get("pendingBadCase").asLong()).isEqualTo(2L);
        assertThat(data.get("fixedTickets").asLong()).isZero();

        JsonNode issueTypes = data.get("issueTypes");
        assertThat(issueTypes).hasSize(2);
        // 占比只在点踩样本内计算：两类各占一半
        assertThat(issueTypes.get(0).get("ratio").asDouble()).isCloseTo(0.5, offset(0.001));
        assertThat(issueTypes.get(0).get("issueType").asText()).isIn("答案幻觉", "检索漏检");
    }

    @Test
    @DisplayName("工单要活过消息：会话删除后列表仍留痕，问答原文显示为空")
    void ticketShouldSurviveMessageCleanup() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "引用不准"))));
        // 模拟删除会话的连带清理（走接口需本人 token，这里直接按同一语义清库）
        jdbcTemplate.update("delete from chat_message where session_id = 1");

        JsonNode row = okData(admin(get("/api/admin/feedback/page?pageNum=1&pageSize=10")))
                .get("records").get(0);
        assertThat(row.get("id").asLong()).isPositive();
        assertThat(row.get("answer").isNull()).isTrue();
        assertThat(row.get("question").isNull()).isTrue();
        assertThat(row.get("issueType").asText()).isEqualTo("引用不准");
    }

    @Test
    @DisplayName("GET /api/feedback/mine：只回显本人评价，可按会话收窄")
    void mineShouldOnlyReturnOwnFeedback() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "答案幻觉", "comment", "漏了拥塞控制"))));
        okData(admin(postJson("/api/feedback", Map.of("messageId", 4, "rating", 1))));

        JsonNode all = okData(student(get("/api/feedback/mine")));
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("messageId").asLong()).isEqualTo(2L);
        assertThat(all.get(0).get("comment").asText()).isEqualTo("漏了拥塞控制");
        // 管理员名下的那条不串味
        assertThat(okData(student(get("/api/feedback/mine?sessionId=2")))).isEmpty();
        // 工单状态与管理员备注不下放到问答页
        assertThat(all.get(0).has("status")).isFalse();
    }

    @Test
    @DisplayName("反馈工单的管理端读写仅管理员：普通用户访问 -> 403")
    void normalUserCannotManageTickets() throws Exception {
        mockMvc.perform(withToken(get("/api/admin/feedback/page?pageNum=1&pageSize=10"), studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/admin/feedback/stats"), studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(putJson("/api/admin/feedback/1/resolve",
                Map.of("status", 1, "adminNote", "越权尝试")), studentToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 提示词模板市场

    @Test
    @DisplayName("GET /api/templates/active：普通用户可读，只含启用中模板且按权重升序")
    void activeTemplatesShouldBeReadableByEveryone() throws Exception {
        JsonNode data = okData(student(get("/api/templates/active")));

        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("title").asText()).isEqualTo("概念四步解析");
        assertThat(data.get(1).get("title").asText()).isEqualTo("自测出题官");
        assertThat(data.get(0).get("enabled").asInt()).isEqualTo(1);
        // 点击胶囊要塞进输入框，正文原样下发、不做任何服务端替换
        assertThat(data.get(0).get("promptContent").asText()).contains("{topic}");
    }

    @Test
    @DisplayName("GET /api/admin/templates：管理端能看到已下架的模板")
    void adminSeesDisabledTemplates() throws Exception {
        JsonNode data = okData(admin(get("/api/admin/templates")));
        assertThat(data).hasSize(3);
        assertThat(data.get(2).get("title").asText()).isEqualTo("旧版摘要指令");
        assertThat(data.get(2).get("enabled").asInt()).isZero();
    }

    @Test
    @DisplayName("POST/PUT/DELETE /api/admin/templates：新建、下架、删除全链路生效")
    void templateCrudShouldWork() throws Exception {
        JsonNode created = okData(admin(postJson("/api/admin/templates", Map.of(
                "title", "英文摘要互译", "category", "学术总结",
                "promptContent", "请把 {topic} 的资料翻译成英文摘要，保持术语一致。",
                "description", "中英术语对照", "enabled", 1, "sortOrder", 5))));
        long id = created.get("id").asLong();
        assertThat(created.get("category").asText()).isEqualTo("学术总结");
        // 权重 5 排在最前，聊天页立刻可见
        assertThat(okData(student(get("/api/templates/active"))).get(0).get("title").asText())
                .isEqualTo("英文摘要互译");

        okData(admin(putJson("/api/admin/templates/" + id, Map.of(
                "title", "英文摘要互译", "category", "对比分析",
                "promptContent", "请对比 {topic} 的中英文表述差异并给出术语表。",
                "enabled", 0))));
        // 三条种子 + 本次新建
        assertThat(okData(admin(get("/api/admin/templates")))).hasSize(4);
        boolean stillActive = false;
        for (JsonNode node : okData(student(get("/api/templates/active")))) {
            stillActive |= node.get("id").asLong() == id;
        }
        assertThat(stillActive).as("下架后聊天页不该再出现该模板").isFalse();

        okData(admin(delete("/api/admin/templates/" + id)));
        assertThat(okData(admin(get("/api/admin/templates")))).hasSize(3);
        // 重复删除按不存在处理
        assertThat(perform(admin(delete("/api/admin/templates/" + id))).get("code").asInt()).isEqualTo(4040);
    }

    @Test
    @DisplayName("POST /api/admin/templates：重名 4001、非法分类 4004、正文为空 4001")
    void templateCreateShouldValidate() throws Exception {
        assertThat(perform(admin(postJson("/api/admin/templates", Map.of(
                "title", "概念四步解析", "category", "概念解析", "promptContent", "x"))))
                .get("code").asInt()).isEqualTo(4001);
        assertThat(perform(admin(postJson("/api/admin/templates", Map.of(
                "title", "怪名字", "category", "瞎写", "promptContent", "x"))))
                .get("code").asInt()).isEqualTo(4004);
        assertThat(perform(admin(postJson("/api/admin/templates", Map.of(
                "title", "空正文", "promptContent", "  "))))
                .get("code").asInt()).isEqualTo(4001);
        // 被拒的写入没有留下半成品
        assertThat(okData(admin(get("/api/admin/templates")))).hasSize(3);
    }

    @Test
    @DisplayName("模板管理写操作仅管理员：普通用户读 active 可以，碰管理端 403")
    void normalUserCannotManageTemplates() throws Exception {
        mockMvc.perform(withToken(get("/api/admin/templates"), studentToken)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(postJson("/api/admin/templates", Map.of("title", "t", "promptContent", "p")),
                studentToken)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete("/api/admin/templates/1"), studentToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 个人中心与安全设置

    @Test
    @DisplayName("PUT /api/auth/profile：改昵称后 /api/auth/me 立即反映，且不影响他人")
    void profileUpdateShouldApply() throws Exception {
        JsonNode data = okData(student(putJson("/api/auth/profile", Map.of("nickname", "爱提问的同学"))));
        assertThat(data.get("nickname").asText()).isEqualTo("爱提问的同学");
        assertThat(data.has("password")).isFalse();
        assertThat(okData(student(get("/api/auth/me"))).get("nickname").asText()).isEqualTo("爱提问的同学");
        // 管理员昵称没被串改
        assertThat(okData(admin(get("/api/auth/me"))).get("nickname").asText()).isEqualTo("系统管理员");

        assertThat(perform(student(putJson("/api/auth/profile", Map.of("nickname", "   "))))
                .get("code").asInt()).isEqualTo(4001);
    }

    @Test
    @DisplayName("PUT /api/auth/password：原密码错 4001，改密后新密码可登录、旧密码失效")
    void passwordUpdateShouldVerifyOldPassword() throws Exception {
        assertThat(perform(student(putJson("/api/auth/password",
                Map.of("oldPassword", "wrong-1", "newPassword", "new-pass-1")))).get("code").asInt())
                .isEqualTo(4001);
        // 新密码与原密码相同：无意义的改动，直接拒
        assertThat(perform(student(putJson("/api/auth/password",
                Map.of("oldPassword", "123456", "newPassword", "123456")))).get("code").asInt()).isEqualTo(4001);
        // 长度不足被 DTO 挡下
        assertThat(perform(student(putJson("/api/auth/password",
                Map.of("oldPassword", "123456", "newPassword", "1")))).get("code").asInt()).isEqualTo(4001);

        okData(student(putJson("/api/auth/password",
                Map.of("oldPassword", "123456", "newPassword", "new-pass-1"))));
        assertThat(login("student", "new-pass-1")).isNotBlank();
        assertThat(perform(postJson("/api/auth/login", Map.of("username", "student", "password", "123456")))
                .get("code").asInt()).isEqualTo(4001);
        // 库里换成了新的 BCrypt 密文，明文不落库
        String stored = jdbcTemplate.queryForObject("select password from sys_user where id = 2", String.class);
        assertThat(stored).doesNotContain("new-pass-1").startsWith("$2a$");
    }

    @Test
    @DisplayName("PUT /api/auth/password：改密成功后手里的旧 token 立刻 401，必须重新登录")
    void passwordUpdateShouldRevokeExistingToken() throws Exception {
        assertThat(okData(student(get("/api/auth/me"))).get("username").asText()).isEqualTo("student");
        int versionBefore = jdbcTemplate.queryForObject("select token_version from sys_user where id = 2", Integer.class);

        okData(student(putJson("/api/auth/password",
                Map.of("oldPassword", "123456", "newPassword", "new-pass-2"))));

        // token 里的 tv 载荷落后于库里的版本号，拦截器直接吊销
        assertThat(jdbcTemplate.queryForObject("select token_version from sys_user where id = 2", Integer.class))
                .isEqualTo(versionBefore + 1);
        mockMvc.perform(withToken(get("/api/auth/me"), studentToken)).andExpect(status().isUnauthorized());
        // 重新登录拿到的新 token 一切照旧
        mockMvc.perform(withToken(get("/api/auth/me"), login("student", "new-pass-2")))
                .andExpect(status().isOk());
        // 别人的会话不受牵连
        mockMvc.perform(withToken(get("/api/auth/me"), adminToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/auth/my-stats：本人会话数 / 提问数 / 回答数 / 反馈数 / 注册时间")
    void myStatsShouldCountOnlyOwnActivity() throws Exception {
        okData(student(postJson("/api/feedback",
                Map.of("messageId", 2, "rating", -1, "issueType", "答案幻觉"))));

        JsonNode studentStats = okData(student(get("/api/auth/my-stats")));
        assertThat(studentStats.get("sessionCount").asLong()).isEqualTo(1L);
        assertThat(studentStats.get("questionCount").asLong()).isEqualTo(1L);
        assertThat(studentStats.get("answerCount").asLong()).isEqualTo(1L);
        assertThat(studentStats.get("feedbackCount").asLong()).isEqualTo(1L);
        assertThat(studentStats.get("badCaseCount").asLong()).isEqualTo(1L);
        assertThat(studentStats.get("registerTime").asText()).isNotBlank();

        // 管理员自己只有一条 feedback，看不到学生的数据
        JsonNode adminStats = okData(admin(get("/api/auth/my-stats")));
        assertThat(adminStats.get("sessionCount").asLong()).isEqualTo(1L);
        assertThat(adminStats.get("feedbackCount").asLong()).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    /**
     * 工单主键由自增序列分配，同一 H2 上下文里会跨用例累积，因此不能硬编码，
     * 只能按发起反馈时的 messageId 动态查回来。
     */
    private long ticketIdOf(long messageId) {
        Long id = jdbcTemplate.queryForObject(
                "select id from chat_feedback where message_id = ? order by id desc limit 1", Long.class, messageId);
        assertThat(id).as("messageId=%s 的工单应已落库", messageId).isNotNull();
        return id;
    }

    private void insertChunk(long id, long documentId, int chunkIndex, Integer pageNumber,
                             String content, int enabled) {
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, page_number, content, char_count, enabled) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, documentId, chunkIndex, pageNumber, content, content.length(), enabled);
    }

    private void insertTemplate(long id, String title, String category, int enabled, int sortOrder) {
        jdbcTemplate.update("insert into prompt_template (id, title, category, prompt_content, description, enabled, sort_order) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, title, category, "请围绕 " + title + " 与 {topic} 展开，逐条给出依据。", "种子模板", enabled, sortOrder);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withToken(builder, adminToken);
    }

    private MockHttpServletRequestBuilder student(MockHttpServletRequestBuilder builder) {
        return withToken(builder, studentToken);
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String login(String username, String password) throws Exception {
        return okData(postJson("/api/auth/login", Map.of("username", username, "password", password)))
                .get("token").asText();
    }

    /** 发请求、断言 HTTP 200 且 code=0，返回 data 节点 */
    private JsonNode okData(MockHttpServletRequestBuilder builder) throws Exception {
        JsonNode root = MAPPER.readTree(mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("接口返回码：%s", root).isZero();
        return root.get("data");
    }

    /** 发请求，返回完整响应体（用于断言业务码） */
    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        return MAPPER.readTree(mockMvc.perform(builder)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder putJson(String url, Object body) throws Exception {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }
}
