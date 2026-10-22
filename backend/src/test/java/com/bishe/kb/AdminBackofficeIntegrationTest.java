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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第十阶段企业级后台的五组管理接口集成测试（检索对比 / 用户管理 / 问答审计 / 参数配置 / 大盘第二排）。
 *
 * <p>与 {@code AuthIntegrationTest} 一样关掉测试放行（{@code auth.test-bypass=false}），
 * 用真实 JWT 验证 RBAC：管理员放行、普通用户 403——这四个模块的接口全都涉及跨用户数据，
 * 权限门是它们最重要的行为契约，因此不走「无 token 即管理员」的捷径。
 *
 * <p>数据跑在 H2 内存库，{@code @BeforeEach} 重建确定性种子；参数配置用例会改容器里的
 * {@code RagProperties} 单例，{@code @AfterEach} 统一恢复出厂快照，避免污染同上下文的其他用例。
 */
@SpringBootTest(properties = "auth.test-bypass=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBackofficeIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String studentToken;

    @BeforeEach
    void seed() throws Exception {
        // 先清历史用例留下的账号，只保留 schema-h2.sql 的 admin(1)/student(2)
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");
        jdbcTemplate.update("delete from sys_user where id > 2");

        adminToken = login("admin", "admin123");
        studentToken = login("student", "123456");

        // 一个管理员建的会话 + 一轮问答（回答带 2 条引用），供审计与活跃度统计使用
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (1, '默认知识库', '系统默认', 1, 2, 0)");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (1, 1, '计算机网络.pdf', 'pdf', 'INDEXED', 2, 0)");
        // 混合检索的词法路要靠正文命中，这里给两条含「滑动窗口」的分段
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, page_number, content, char_count, enabled) "
                + "values (1, 1, 0, 3, 'TCP 使用滑动窗口进行流量控制，窗口大小由接收方决定', 30, 1)");
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, page_number, content, char_count, enabled) "
                + "values (2, 1, 1, 4, '滑动窗口机制可以避免接收方缓冲区溢出', 18, 1)");

        jdbcTemplate.update("insert into chat_session (id, user_id, title, deleted) values (1, 2, '关于滑动窗口', 0)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (1, 1, 'user', 'TCP 的滑动窗口是做什么的', null)");
        jdbcTemplate.update("insert into chat_message (id, session_id, role, content, citations) "
                + "values (2, 1, 'assistant', '滑动窗口用于流量控制，接收方通过窗口大小限制发送方速率', "
                + "'[{\"documentId\":1,\"title\":\"计算机网络.pdf\",\"chunkIndex\":0,\"pageNumber\":3,\"snippet\":\"TCP 使用滑动窗口\",\"score\":0.82},"
                + "{\"documentId\":1,\"title\":\"计算机网络.pdf\",\"chunkIndex\":1,\"pageNumber\":4,\"snippet\":\"避免缓冲区溢出\",\"score\":0.75}]')");
        // 另一名管理员，用来验证「可以动别人、不能动自己」
        jdbcTemplate.update("insert into sys_user (id, username, password, nickname, role, status, deleted) "
                + "values (3, 'backup_admin', '$2a$10$W07oeVJepI477BktFAZBFer5Vea0oKVU8ApbqJnL3XVgW8MGJsQzG', '备用管理员', 'ADMIN', 1, 0)");
    }

    @AfterEach
    void restoreConfig() throws Exception {
        // 参数配置用例改的是容器内单例，收尾恢复，保证其他用例看到的仍是 test yml 的出厂值
        okData(admin(postJson("/api/admin/config/reset", Map.of())));
    }

    // ------------------------------------------------------------------ 检索对比实验室

    @Test
    @DisplayName("POST /api/knowledge/retrieve-compare：管理员可跑三路，返回名次、耗时与可用性说明")
    void adminCanCompareThreeRetrievalRoutes() throws Exception {
        JsonNode data = okData(admin(postJson("/api/knowledge/retrieve-compare",
                Map.of("question", "TCP 的滑动窗口是做什么的", "topK", 3))));

        assertThat(data.get("topK").asInt()).isEqualTo(3);
        // 引擎参数块：RRF 常数与向量模型名要能展示出来，前端才有「为什么混合这么算」的说明材料
        assertThat(data.get("engine").get("rrfK").asInt()).isPositive();
        assertThat(data.get("engine").get("vectorAvailable").asBoolean()).isFalse();

        JsonNode routes = data.get("routes");
        assertThat(routes).hasSize(3);
        assertThat(routes.get(0).get("strategy").asText()).isEqualTo("LEXICAL");
        assertThat(routes.get(1).get("strategy").asText()).isEqualTo("SEMANTIC");
        assertThat(routes.get(2).get("strategy").asText()).isEqualTo("HYBRID");

        // 词法路靠 2-gram 命中，两条分段都该被召回，且名次从 1 开始连续
        JsonNode lexical = routes.get(0);
        assertThat(lexical.get("available").asBoolean()).isTrue();
        assertThat(lexical.get("hitCount").asInt()).isGreaterThan(0);
        assertThat(lexical.get("elapsedMillis").asLong()).isGreaterThanOrEqualTo(0L);
        JsonNode items = lexical.get("items");
        assertThat(items.get(0).get("rank").asInt()).isEqualTo(1);
        // 文档标题与页码由回表补齐，前端卡片直接展示
        assertThat(items.get(0).get("documentTitle").asText()).isEqualTo("计算机网络.pdf");
        assertThat(items.get(0).has("pageNumber")).isTrue();

        // 测试库关闭了向量化：语义路必须明确标为不可用并给出原因，而不是静默返回空列表
        JsonNode semantic = routes.get(1);
        assertThat(semantic.get("available").asBoolean()).isFalse();
        assertThat(semantic.get("hitCount").asInt()).isZero();
        assertThat(semantic.get("note").asText()).contains("语义路返回空");
        // 混合路在语义缺失时自动退化为纯词法，说明里要讲清楚
        assertThat(routes.get(2).get("note").asText()).contains("退化为纯词法");
        assertThat(routes.get(2).get("hitCount").asInt()).isEqualTo(lexical.get("hitCount").asInt());
    }

    @Test
    @DisplayName("POST /api/knowledge/retrieve-compare：普通用户 403（精确路径管理员专属，问答接口不受影响）")
    void normalUserCannotCompareRoutes() throws Exception {
        mockMvc.perform(withToken(post("/api/knowledge/retrieve-compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"滑动窗口\",\"topK\":3}"), studentToken))
                .andExpect(status().isForbidden());

        // 同前缀下的会话接口仍对普通用户开放，证明没有把 /api/knowledge 整段封掉
        assertThat(okData(withToken(get("/api/sessions"), studentToken)).isArray()).isTrue();
    }

    @Test
    @DisplayName("POST /api/knowledge/retrieve-compare：问题为空 -> 业务码 4001 参数校验失败")
    void blankQuestionShouldFailValidation() throws Exception {
        JsonNode body = perform(admin(postJson("/api/knowledge/retrieve-compare", Map.of("question", "  "))));
        assertThat(body.get("code").asInt()).isEqualTo(4001);
    }

    // ------------------------------------------------------------------ 用户与权限管理

    @Test
    @DisplayName("GET /api/users：分页返回账号，并带会话数与提问数")
    void pageUsersShouldIncludeActivity() throws Exception {
        JsonNode data = okData(admin(get("/api/users?pageNum=1&pageSize=10")));

        assertThat(data.get("total").asLong()).isEqualTo(3L);
        JsonNode records = data.get("records");
        // id 倒序：backup_admin(3) > student(2) > admin(1)
        assertThat(records.get(0).get("username").asText()).isEqualTo("backup_admin");
        assertThat(records.get(0).get("role").asText()).isEqualTo("ADMIN");
        // student 名下 1 个会话、1 条提问
        JsonNode student = byUsername(records, "student");
        assertThat(student.get("sessionCount").asLong()).isEqualTo(1L);
        assertThat(student.get("questionCount").asLong()).isEqualTo(1L);
        assertThat(byUsername(records, "admin").get("sessionCount").asLong()).isZero();
    }

    @Test
    @DisplayName("GET /api/users?keyword=：用户名与昵称任一命中都算匹配")
    void keywordShouldMatchUsernameAndNickname() throws Exception {
        JsonNode byNickname = okData(admin(get("/api/users?pageNum=1&pageSize=10&keyword=备用")));
        assertThat(byNickname.get("total").asLong()).isEqualTo(1L);
        assertThat(byNickname.get("records").get(0).get("username").asText()).isEqualTo("backup_admin");

        JsonNode byRole = okData(admin(get("/api/users?pageNum=1&pageSize=10&role=USER")));
        assertThat(byRole.get("total").asLong()).isEqualTo(1L);
        assertThat(byRole.get("records").get(0).get("username").asText()).isEqualTo("student");
    }

    @Test
    @DisplayName("POST /api/users：新建账号默认启用，密码不回传且以密文入库")
    void createUserShouldPersistEncodedPassword() throws Exception {
        JsonNode data = okData(admin(postJson("/api/users",
                Map.of("username", "new_staff", "password", "plain123", "nickname", "新同事", "role", "USER"))));

        assertThat(data.get("username").asText()).isEqualTo("new_staff");
        assertThat(data.get("status").asInt()).isEqualTo(1);
        assertThat(data.get("role").asText()).isEqualTo("USER");
        assertThat(data.has("password")).isFalse();
        // 库里存的是 BCrypt 密文，明文不落库
        String stored = jdbcTemplate.queryForObject(
                "select password from sys_user where username = 'new_staff'", String.class);
        assertThat(stored).doesNotContain("plain123").startsWith("$2a$");

        // 新账号能用原密码登录，说明编码没写坏
        assertThat(login("new_staff", "plain123")).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/users：用户名重复 -> 业务码 4002；非法角色 -> 4004")
    void createUserShouldRejectDuplicateAndBadRole() throws Exception {
        assertThat(perform(admin(postJson("/api/users",
                Map.of("username", "student", "password", "plain123")))).get("code").asInt()).isEqualTo(4002);

        assertThat(perform(admin(postJson("/api/users",
                Map.of("username", "bad_role", "password", "plain123", "role", "ROOT")))).get("code").asInt())
                .isEqualTo(4004);
    }

    @Test
    @DisplayName("PUT /api/users/{id}/role：禁止管理员把自己降级，但可以调整他人")
    void roleUpdateShouldProtectSelf() throws Exception {
        long adminId = jdbcTemplate.queryForObject(
                "select id from sys_user where username = 'admin'", Long.class);

        JsonNode self = perform(admin(putJson("/api/users/" + adminId + "/role", Map.of("role", "USER"))));
        assertThat(self.get("code").asInt()).isEqualTo(4005);
        assertThat(self.get("message").asText()).contains("不能修改自己的角色");
        // 被拒后角色保持不变
        assertThat(jdbcTemplate.queryForObject("select role from sys_user where id = ?", String.class, adminId))
                .isEqualTo("ADMIN");

        // 把备用管理员降为普通用户：系统仍有 admin 一名管理员，允许
        JsonNode other = okData(admin(putJson("/api/users/3/role", Map.of("role", "USER"))));
        assertThat(other.get("role").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("PUT /api/users/{id}/status：不能禁用自己；禁用他人后其 token 立即失效")
    void statusUpdateShouldProtectSelfAndRevokeToken() throws Exception {
        JsonNode self = perform(admin(putJson("/api/users/1/status", Map.of("status", 0))));
        assertThat(self.get("code").asInt()).isEqualTo(4005);

        JsonNode disabled = okData(admin(putJson("/api/users/2/status", Map.of("status", 0))));
        assertThat(disabled.get("status").asInt()).isZero();
        // 拦截器每次回表确认账号状态，禁用能即时生效，不依赖 JWT 过期
        mockMvc.perform(withToken(get("/api/sessions"), studentToken)).andExpect(status().isUnauthorized());

        // 重新启用后即可登录
        okData(admin(putJson("/api/users/2/status", Map.of("status", 1))));
        assertThat(login("student", "123456")).isNotBlank();
    }

    @Test
    @DisplayName("PUT /api/users/{id}/status：非法取值被 DTO 校验挡下 -> 4001，不会走到写库")
    void statusUpdateShouldRejectBadValue() throws Exception {
        assertThat(perform(admin(putJson("/api/users/2/status", Map.of("status", 7)))).get("code").asInt())
                .isEqualTo(4001);
        // 状态未被改动
        assertThat(jdbcTemplate.queryForObject("select status from sys_user where id = 2", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /api/users/{id}/reset-password：重置为 123456 且旧密码立刻不可用")
    void resetPasswordShouldApplyImmediately() throws Exception {
        okData(admin(postJson("/api/users", Map.of("username", "forget_me", "password", "old12345"))));
        long id = jdbcTemplate.queryForObject("select id from sys_user where username = 'forget_me'", Long.class);

        JsonNode data = okData(admin(put("/api/users/" + id + "/reset-password")));
        assertThat(data.asText()).isEqualTo("123456");

        assertThat(login("forget_me", "123456")).isNotBlank();
        assertThat(perform(postJson("/api/auth/login", Map.of("username", "forget_me", "password", "old12345")))
                .get("code").asInt()).isEqualTo(4001);
    }

    @Test
    @DisplayName("GET /api/users：普通用户访问账号管理 -> 403")
    void normalUserCannotManageUsers() throws Exception {
        mockMvc.perform(withToken(get("/api/users"), studentToken)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 问答审计日志

    @Test
    @DisplayName("GET /api/admin/audit/messages：一行一问，带提问人、会话标题与引用切片数")
    void auditPageShouldPairQuestionWithAnswer() throws Exception {
        JsonNode data = okData(admin(get("/api/admin/audit/messages?pageNum=1&pageSize=10")));

        assertThat(data.get("total").asLong()).isEqualTo(1L);
        JsonNode row = data.get("records").get(0);
        assertThat(row.get("question").asText()).isEqualTo("TCP 的滑动窗口是做什么的");
        assertThat(row.get("username").asText()).isEqualTo("student");
        assertThat(row.get("nickname").asText()).isEqualTo("体验学生");
        assertThat(row.get("sessionTitle").asText()).isEqualTo("关于滑动窗口");
        assertThat(row.get("citationCount").asInt()).isEqualTo(2);
        assertThat(row.get("answerSummary").asText()).contains("流量控制");
        assertThat(row.get("askTime").asText()).isNotBlank();
    }

    @Test
    @DisplayName("GET /api/admin/audit/messages：关键字只匹配问题正文，用户名参数按人过滤")
    void auditShouldFilterByKeywordAndUsername() throws Exception {
        assertThat(okData(admin(get("/api/admin/audit/messages?pageNum=1&pageSize=10&keyword=滑动窗口")))
                .get("total").asLong()).isEqualTo(1L);
        // 回答里才有的词不该命中：列表以问题正文为过滤对象
        assertThat(okData(admin(get("/api/admin/audit/messages?pageNum=1&pageSize=10&keyword=缓冲区溢出")))
                .get("total").asLong()).isZero();
        assertThat(okData(admin(get("/api/admin/audit/messages?pageNum=1&pageSize=10&username=student")))
                .get("total").asLong()).isEqualTo(1L);
        // 查一个没有记录的用户直接返回空页
        assertThat(okData(admin(get("/api/admin/audit/messages?pageNum=1&pageSize=10&username=admin")))
                .get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("GET /api/admin/audit/messages/{id}：返回完整回答与解析后的引用来源")
    void auditDetailShouldReturnFullAnswerAndCitations() throws Exception {
        JsonNode data = okData(admin(get("/api/admin/audit/messages/1")));

        assertThat(data.get("answer").asText()).contains("滑动窗口用于流量控制");
        JsonNode citations = data.get("citations");
        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).get("title").asText()).isEqualTo("计算机网络.pdf");
        assertThat(citations.get(0).get("pageNumber").asInt()).isEqualTo(3);

        // 回答消息不是提问，按不存在处理
        assertThat(perform(admin(get("/api/admin/audit/messages/2"))).get("code").asInt()).isEqualTo(4040);
    }

    @Test
    @DisplayName("问答审计是跨用户入口：普通用户访问 -> 403")
    void normalUserCannotReadAudit() throws Exception {
        mockMvc.perform(withToken(get("/api/admin/audit/messages"), studentToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 引擎参数配置

    @Test
    @DisplayName("GET /api/admin/config：返回当前值、出厂默认、只读装配信息与参数说明")
    void configShouldExposeCurrentDefaultsAndRuntime() throws Exception {
        JsonNode data = okData(admin(get("/api/admin/config")));

        // test profile 出厂值：top-k=3、chunk=300/50、向量关闭
        assertThat(data.get("topK").asInt()).isEqualTo(3);
        assertThat(data.get("chunkMaxChars").asInt()).isEqualTo(300);
        assertThat(data.get("embeddingEnabled").asBoolean()).isFalse();
        assertThat(data.get("defaults").get("topK").asInt()).isEqualTo(3);
        assertThat(data.get("runtime").get("chatModel").asText()).isEqualTo("qwen-plus");
        // 密钥只暴露「是否已配置」，绝不把原文带出接口
        assertThat(data.get("runtime").has("apiKey")).isFalse();
        assertThat(data.get("runtime").get("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(data.get("hints").get("topK").asText()).contains("切片数量");
    }

    @Test
    @DisplayName("PUT /api/admin/config：部分更新只改提交的字段，其余保持原值")
    void configUpdateShouldBePartial() throws Exception {
        JsonNode updated = okData(admin(putJson("/api/admin/config",
                Map.of("topK", 7, "systemPrompt", "只依据资料回答，不要编造。"))));

        assertThat(updated.get("topK").asInt()).isEqualTo(7);
        assertThat(updated.get("systemPrompt").asText()).isEqualTo("只依据资料回答，不要编造。");
        // 未提交的字段原样保留
        assertThat(updated.get("chunkMaxChars").asInt()).isEqualTo(300);
        assertThat(updated.get("rrfK").asInt()).isPositive();

        // 改完能被检索实验室读到，说明确实写进了运行时单例
        JsonNode engine = okData(admin(postJson("/api/knowledge/retrieve-compare",
                Map.of("question", "滑动窗口")))).get("engine");
        assertThat(engine.get("rrfK").asInt()).isEqualTo(updated.get("rrfK").asInt());
    }

    @Test
    @DisplayName("POST /api/admin/config/reset：恢复默认把改动过的参数拉回出厂值")
    void configResetShouldRestoreDefaults() throws Exception {
        okData(admin(putJson("/api/admin/config", Map.of("topK", 9, "historyMessages", 2))));

        JsonNode restored = okData(admin(postJson("/api/admin/config/reset", Map.of())));
        assertThat(restored.get("topK").asInt()).isEqualTo(3);
        assertThat(restored.get("historyMessages").asInt()).isEqualTo(6);
        assertThat(restored.get("systemPrompt").asText()).isEmpty();
    }

    @Test
    @DisplayName("PUT /api/admin/config：重叠不小于切片上限 -> 4021，超半 -> 4022，提示词空白 -> 4023")
    void configUpdateShouldValidateCrossFieldRules() throws Exception {
        assertThat(perform(admin(putJson("/api/admin/config",
                Map.of("chunkMaxChars", 300, "chunkOverlapChars", 300)))).get("code").asInt()).isEqualTo(4021);
        assertThat(perform(admin(putJson("/api/admin/config",
                Map.of("chunkMaxChars", 300, "chunkOverlapChars", 200)))).get("code").asInt()).isEqualTo(4022);
        assertThat(perform(admin(putJson("/api/admin/config",
                Map.of("systemPrompt", "   ")))).get("code").asInt()).isEqualTo(4023);
        // 校验失败发生在写入之前，参数不会被改坏
        assertThat(okData(admin(get("/api/admin/config"))).get("chunkOverlapChars").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("PUT /api/admin/config：topK 超出 DTO 上限 -> 4001 参数校验失败")
    void configUpdateShouldRejectOutOfRange() throws Exception {
        assertThat(perform(admin(putJson("/api/admin/config", Map.of("topK", 999)))).get("code").asInt())
                .isEqualTo(4001);
    }

    @Test
    @DisplayName("GET /api/admin/config：普通用户访问 -> 403")
    void normalUserCannotReadConfig() throws Exception {
        mockMvc.perform(withToken(get("/api/admin/config"), studentToken)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 大盘第二排

    @Test
    @DisplayName("GET /api/dashboard/components：四个核心组件齐备，数据库真探活为在线")
    void componentsShouldReportFourItems() throws Exception {
        JsonNode data = okData(admin(get("/api/dashboard/components")));

        assertThat(data.get("checkedAt").asText()).isNotBlank();
        JsonNode components = data.get("components");
        assertThat(components).hasSize(4);
        assertThat(components.get(0).get("key").asText()).isEqualTo("mysql");
        assertThat(components.get(1).get("key").asText()).isEqualTo("llm");
        assertThat(components.get(2).get("key").asText()).isEqualTo("embedding");
        assertThat(components.get(3).get("key").asText()).isEqualTo("vectorStore");

        // H2 连接一定拿得到，且连接池指标随行给出
        JsonNode mysql = components.get(0);
        assertThat(mysql.get("state").asText()).isEqualTo("ONLINE");
        assertThat(mysql.get("stateCn").asText()).isEqualTo("在线");
        assertThat(field(mysql, "连接池")).contains("上限");
        assertThat(field(mysql, "数据库").toLowerCase()).contains("h2");

        // 测试库关闭向量化：属于「主动未启用」，不能报成离线
        assertThat(components.get(2).get("state").asText()).isEqualTo("DISABLED");
        assertThat(components.get(2).get("note").asText()).contains("纯词法");
        // 向量文件不落在 ./data（测试用 target 下的独立路径），条目数与启用切片数一并展示
        assertThat(field(components.get(3), "启用切片")).isEqualTo("2 条");
    }

    @Test
    @DisplayName("GET /api/dashboard/activity：最近文档带空间名与状态中文，最近提问带提问人")
    void activityShouldListRecentDocumentsAndQuestions() throws Exception {
        JsonNode data = okData(admin(get("/api/dashboard/activity")));

        JsonNode documents = data.get("recentDocuments");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("title").asText()).isEqualTo("计算机网络.pdf");
        assertThat(documents.get(0).get("spaceName").asText()).isEqualTo("默认知识库");
        assertThat(documents.get(0).get("statusCn").asText()).isEqualTo("已索引");

        JsonNode questions = data.get("recentQuestions");
        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).get("question").asText()).contains("滑动窗口");
        assertThat(questions.get(0).get("username").asText()).isEqualTo("student");
        assertThat(questions.get(0).get("citationCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("大盘新增的两个接口同样仅管理员：普通用户 -> 403")
    void normalUserCannotReadComponents() throws Exception {
        mockMvc.perform(withToken(get("/api/dashboard/components"), studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/dashboard/activity"), studentToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 辅助方法

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withToken(builder, adminToken);
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String login(String username, String password) throws Exception {
        return okData(postJson("/api/auth/login", Map.of("username", username, "password", password)))
                .get("token").asText();
    }

    private JsonNode byUsername(JsonNode records, String username) {
        for (JsonNode node : records) {
            if (username.equals(node.get("username").asText())) {
                return node;
            }
        }
        throw new AssertionError("列表里没有用户名 " + username + "：" + records);
    }

    /** 从组件明细里按 label 取值，缺行直接判失败，避免断言写得松 */
    private String field(JsonNode component, String label) {
        for (JsonNode node : component.get("fields")) {
            if (label.equals(node.get("label").asText())) {
                return node.get("value").asText();
            }
        }
        throw new AssertionError("组件 " + component.get("key").asText() + " 缺少明细项：" + label);
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
