package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.SpaceAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 空间授权（多租户边界）集成测试。
 *
 * <p>覆盖批次四要解决的越权面：V8 之前 {@code spaceId} 只是管理员手选的过滤参数，
 * 任何登录用户在问答里传别人的空间 ID 就能召回那个空间的切片，答案引用还会直接带出原文。
 * 现在空间是权限边界，判定发生在<b>召回之前</b>并下推进 SQL，所以这里每个用例都在问同一件事：
 * 「越权的那条切片有没有进到候选池」。
 *
 * <p>本类跑在 {@code auth.test-bypass=false} 的真实鉴权语义下（独立 Spring 上下文），
 * 用 V4 种子账号 admin(ADMIN) / student(USER) 登录拿 JWT；数据全部落在 H2 内存库，
 * 每个用例重建自己的种子，不接触开发库 {@code kb_rag}。
 *
 * <p>语料刻意构造成「两个空间的切片都含同一个词」：这样「没收敛」一定会表现为引用来自
 * 另一个空间，而不是靠命中数多少来猜。
 */
@SpringBootTest(properties = "auth.test-bypass=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaceAccessIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认知识库：V9 回填后所有在册账号都能读，用例里也让 student 有成员行 */
    private static final long SPACE_PUBLIC = 1L;
    /** 另一个账号的私有空间：student 没有成员行，越权就是靠它验证 */
    private static final long SPACE_PRIVATE = 2L;

    private static final long DOC_PUBLIC = 11L;
    private static final long DOC_PRIVATE = 12L;
    private static final long CHUNK_PUBLIC = 1101L;
    private static final long CHUNK_PRIVATE = 1201L;

    private static final String QUESTION = "滑动窗口是怎么工作的";
    private static final String STREAM_URL = "/api/knowledge/ask/stream";
    private static final long STUDENT_SESSION = 9100L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @Autowired
    private SpaceAccessService spaceAccessService;

    @Autowired
    private RetrievalService retrievalService;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private StreamingChatModel streamingChatModel;

    /** student 的账号 ID，种子来自 V4，随成员关系一起决定它能读哪些空间 */
    private long studentId;

    @BeforeEach
    void seed() {
        // MockMvc 走异步(SSE)时不会执行 afterCompletion，测试线程上的 ThreadLocal 会带着上一个用例的
        // 身份残留；本类既有 HTTP 用例又有直调服务的用例，必须每个用例先把上下文清干净再种数据。
        UserContext.clear();

        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space_member");
        jdbcTemplate.update("delete from kb_space");
        // 用例里自助注册的账号用完就清掉，只留 V4 种子的 admin(1) / student(2)
        jdbcTemplate.update("delete from sys_user where id > 2");

        studentId = jdbcTemplate.queryForObject(
                "select id from sys_user where username = 'student'", Long.class);

        jdbcTemplate.update("insert into kb_space (id, name, description, created_by, doc_count, chunk_count, deleted) "
                + "values (?, '默认知识库', '公共库', 1, 1, 1, 0)", SPACE_PUBLIC);
        jdbcTemplate.update("insert into kb_space (id, name, description, created_by, doc_count, chunk_count, deleted) "
                + "values (?, '私有协议库', '别人建的库', 1, 1, 1, 0)", SPACE_PRIVATE);

        // 成员关系：两个空间的创建人都是 admin，student 只有公共库
        jdbcTemplate.update("insert into kb_space_member (space_id, user_id) values (?, 1)", SPACE_PUBLIC);
        jdbcTemplate.update("insert into kb_space_member (space_id, user_id) values (?, 1)", SPACE_PRIVATE);
        jdbcTemplate.update("insert into kb_space_member (space_id, user_id) values (?, ?)", SPACE_PUBLIC, studentId);

        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, ?, '操作系统基线.pdf', 'pdf', 'INDEXED', 1, 0)", DOC_PUBLIC, SPACE_PUBLIC);
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, ?, '计算机网络.pdf', 'pdf', 'INDEXED', 1, 0)", DOC_PRIVATE, SPACE_PRIVATE);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (?, ?, 0, '进程调度里也用滑动窗口式的加权公平队列来平衡各进程的令牌数', 26, 1)",
                CHUNK_PUBLIC, DOC_PUBLIC);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (?, ?, 0, 'TCP 使用滑动窗口进行流量控制，窗口大小由接收方通告', 24, 1)",
                CHUNK_PRIVATE, DOC_PRIVATE);

        // student 名下放一个会话，流式用例要用它（会话按用户隔离，归属不符会被 requireSession 拒掉）
        jdbcTemplate.update("insert into chat_session (id, user_id, title, deleted) values (?, ?, '新对话', 0)",
                STUDENT_SESSION, studentId);

        props.setApiKey("test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);

        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{1f, 0f, 0f, 0f})));
        Mockito.when(chatModel.chat(Mockito.<List<ChatMessage>>any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("基于召回片段生成的答案")).build());
        Mockito.doAnswer(invocation -> {
            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("流式答案");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ignored")).build());
            return null;
        }).when(streamingChatModel).chat(Mockito.anyList(), Mockito.any());
    }

    @AfterEach
    void restore() {
        props.setEmbeddingEnabled(false);
        props.setMockEnabled(false);
        UserContext.clear();
    }

    // ------------------------------------------------------------------ 越权拦截

    @Test
    @DisplayName("普通用户传别人的 spaceId 问答：4030 拒绝，不返回任何切片")
    void askWithForeignSpaceIsRejected() throws Exception {
        JsonNode body = perform(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_PRIVATE), studentToken()));

        assertThat(body.get("code").asInt()).isEqualTo(4030);
        assertThat(body.get("message").asText()).contains("无权访问该知识库空间");
        assertThat(body.path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("流式问答越权：推 error 事件且不落库——权限判在写用户消息之前")
    void streamWithForeignSpaceEmitsErrorBeforePersisting() throws Exception {
        MvcResult result = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken())
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", QUESTION, "sessionId", STUDENT_SESSION, "spaceId", SPACE_PRIVATE))))
                .andExpect(status().isOk())
                .andReturn();
        String body = awaitTerminalEvent(result, "error");

        assertThat(body).contains("event:error").contains("4030");
        assertThat(countMessages(STUDENT_SESSION, "user")).isZero();
    }

    @Test
    @DisplayName("有权限的空间照常问答：引用只来自被授权的那个空间")
    void askWithGrantedSpaceSucceeds() throws Exception {
        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_PUBLIC), studentToken()));

        assertThat(data.get("citations")).isNotEmpty();
        for (JsonNode citation : data.get("citations")) {
            assertThat(citation.get("documentId").asLong()).isEqualTo(DOC_PUBLIC);
        }
    }

    // ------------------------------------------------------------------ 不指定空间时的收敛

    @Test
    @DisplayName("普通用户不指定空间：只在自己的可见空间集合内召回，越权切片进不了候选池")
    void unscopedAskIsLimitedToVisibleSpaces() throws Exception {
        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5), studentToken()));

        List<Long> documentIds = java.util.stream.StreamSupport
                .stream(data.get("citations").spliterator(), false)
                .map(node -> node.get("documentId").asLong())
                .toList();
        assertThat(documentIds).containsExactly(DOC_PUBLIC);
    }

    @Test
    @DisplayName("管理员不指定空间：不受成员限制，两个空间的切片都能召回")
    void adminIsNotLimitedByMembership() throws Exception {
        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5), login("admin", "admin123")));

        List<Long> documentIds = java.util.stream.StreamSupport
                .stream(data.get("citations").spliterator(), false)
                .map(node -> node.get("documentId").asLong())
                .toList();
        assertThat(documentIds).contains(DOC_PUBLIC, DOC_PRIVATE);
    }

    @Test
    @DisplayName("没有任何成员关系的账号：召回为空，宁可答不出也不能泄露别人的语料")
    void accountWithoutMembershipRecallsNothing() throws Exception {
        String token = register("no_scope_user", "secret123");
        // 注册会自动把账号放进默认空间（见 joinDefaultSpace），所以这里先收回，
        // 才能验到真正要测的那条下限：空可见集合必须短路成空结果，而不是退化成全库
        revokeAllMemberships("no_scope_user");

        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5), token));

        assertThat(data.get("citations")).isEmpty();
        assertThat(data.get("answer").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ 可见空间列表

    @Test
    @DisplayName("GET /api/spaces/visible：登录级接口，普通用户能调且只回自己有权限的空间")
    void visibleSpacesAreRoleAware() throws Exception {
        JsonNode student = okData(getWithToken("/api/spaces/visible", studentToken()));
        assertThat(spaceIds(student)).containsExactly(SPACE_PUBLIC);

        JsonNode admin = okData(getWithToken("/api/spaces/visible", login("admin", "admin123")));
        assertThat(spaceIds(admin)).containsExactlyInAnyOrder(SPACE_PUBLIC, SPACE_PRIVATE);
    }

    @Test
    @DisplayName("普通用户没有成员关系时 visible 返回空列表，而不是全部空间")
    void visibleSpacesIsEmptyWhenNoMembership() throws Exception {
        String token = register("lonely_user", "secret123");
        revokeAllMemberships("lonely_user");

        JsonNode data = okData(getWithToken("/api/spaces/visible", token));

        assertThat(data).isEmpty();
    }

    @Test
    @DisplayName("自助注册的账号立刻是默认空间成员：不必等管理员授权就能问出结果")
    void newlyRegisteredUserCanAskImmediately() throws Exception {
        String token = register("fresh_user", "secret123");

        assertThat(spaceIds(okData(getWithToken("/api/spaces/visible", token)))).containsExactly(SPACE_PUBLIC);

        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5), token));
        assertThat(data.get("citations")).isNotEmpty();
        for (JsonNode citation : data.get("citations")) {
            assertThat(citation.get("documentId").asLong()).isEqualTo(DOC_PUBLIC);
        }
    }

    @Test
    @DisplayName("管理员建号：USER 带默认空间授权，ADMIN 不插成员行（它本来就绕过成员表）")
    void adminCreatedUserJoinsDefaultSpaceButAdminDoesNot() throws Exception {
        String admin = login("admin", "admin123");

        long userId = okData(postJson("/api/users",
                Map.of("username", "made_by_admin", "password", "secret123", "role", "USER"), admin))
                .get("id").asLong();
        long adminId = okData(postJson("/api/users",
                Map.of("username", "made_admin", "password", "secret123", "role", "ADMIN"), admin))
                .get("id").asLong();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from kb_space_member where user_id = ? and space_id = ?", Integer.class,
                userId, SPACE_PUBLIC)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from kb_space_member where user_id = ?", Integer.class, adminId)).isZero();
    }

    @Test
    @DisplayName("放开 /visible 不等于放开空间管理：普通用户访问 /spaces/all 仍是 403")
    void otherSpaceEndpointsStayAdminOnly() throws Exception {
        mockMvc.perform(getWithToken("/api/spaces/all", studentToken()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 授权 / 撤销

    @Test
    @DisplayName("管理员授权后普通用户立刻能检索该空间，撤销后立刻恢复 4030：授权即时生效不留缓存")
    void grantThenRevokeTakesEffectImmediately() throws Exception {
        String admin = login("admin", "admin123");

        assertThat(okData(postJson("/api/spaces/" + SPACE_PRIVATE + "/members",
                Map.of("userIds", List.of(studentId)), admin)).asInt()).isEqualTo(1);
        // 重复授权幂等：后端跳过已有成员，返回本次真正新增的人数 0
        assertThat(okData(postJson("/api/spaces/" + SPACE_PRIVATE + "/members",
                Map.of("userIds", List.of(studentId)), admin)).asInt()).isZero();

        JsonNode data = okData(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_PRIVATE), studentToken()));
        for (JsonNode citation : data.get("citations")) {
            assertThat(citation.get("documentId").asLong()).isEqualTo(DOC_PRIVATE);
        }

        assertThat(okData(deleteWithToken("/api/spaces/" + SPACE_PRIVATE + "/members/" + studentId, admin)).asInt())
                .isEqualTo(1);
        assertThat(perform(postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_PRIVATE), studentToken()))
                .get("code").asInt()).isEqualTo(4030);
    }

    @Test
    @DisplayName("撤销空间创建人的授权被拒绝（4031），否则空间会没人能管")
    void creatorMembershipCannotBeRevoked() throws Exception {
        String admin = login("admin", "admin123");

        JsonNode body = perform(deleteWithToken("/api/spaces/" + SPACE_PRIVATE + "/members/1", admin));

        assertThat(body.get("code").asInt()).isEqualTo(4031);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from kb_space_member where space_id = ? and user_id = 1", Integer.class,
                SPACE_PRIVATE)).isOne();
    }

    @Test
    @DisplayName("成员列表带账号信息，供管理员核对授权给了谁")
    void membersListShowsAccountInfo() throws Exception {
        String admin = login("admin", "admin123");

        JsonNode data = okData(getWithToken("/api/spaces/" + SPACE_PUBLIC + "/members", admin));

        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("username").asText()).isEqualTo("admin");
        assertThat(data.get(0).get("grantTime").asText()).isNotBlank();
    }

    @Test
    @DisplayName("授权不存在的账号被拒绝，避免插出没法定位的成员行")
    void grantUnknownUserIsRejected() throws Exception {
        String admin = login("admin", "admin123");

        JsonNode body = perform(postJson("/api/spaces/" + SPACE_PRIVATE + "/members",
                Map.of("userIds", List.of(999999L)), admin));

        assertThat(body.get("code").asInt()).isEqualTo(4040);
    }

    // ------------------------------------------------------------------ 文档预览的空间边界

    @Test
    @DisplayName("普通用户预览别人空间的文档：4030，即使它已经 INDEXED")
    void previewOutsideVisibleSpaceIsRejected() throws Exception {
        JsonNode body = perform(getWithToken("/api/documents/" + DOC_PRIVATE + "/preview", studentToken()));

        assertThat(body.get("code").asInt()).isEqualTo(4030);
    }

    @Test
    @DisplayName("同空间内的预览请求不被空间层拦截：报的是业务层的文件问题而不是 4030")
    void previewInsideVisibleSpacePassesSpaceCheck() throws Exception {
        // 种子里没写 file_path，越过空间校验后停在「没有可预览的原始文件」
        JsonNode body = perform(getWithToken("/api/documents/" + DOC_PUBLIC + "/preview", studentToken()));

        assertThat(body.get("code").asInt()).isNotEqualTo(4030);
    }

    // ------------------------------------------------------------------ 服务层口径

    @Test
    @DisplayName("空可见集合直接返回空结果，不会退化成全库召回")
    void denyAllScopeReturnsEmptyRecall() {
        List<KbChunk> hits = retrievalService.retrieve(QUESTION, 5, RetrievalService.Route.LEXICAL,
                com.bishe.kb.service.RetrievalScope.visible(List.of()));

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("多空间收敛：可见集合里两个空间都召回，集合外的切片一条都不出")
    void visibleSetRecallsAllItsSpaces() {
        List<KbChunk> both = retrievalService.retrieve(QUESTION, 5, RetrievalService.Route.HYBRID,
                com.bishe.kb.service.RetrievalScope.visible(List.of(SPACE_PUBLIC, SPACE_PRIVATE)));
        assertThat(both).extracting(KbChunk::getDocumentId).containsExactlyInAnyOrder(DOC_PUBLIC, DOC_PRIVATE);

        // 直接调服务时没有登录上下文（内部跑批场景），管理员语义 = 不限制
        List<KbChunk> unrestricted = retrievalService.retrieve(QUESTION, 5, RetrievalService.Route.HYBRID);
        assertThat(unrestricted).extracting(KbChunk::getDocumentId).containsExactlyInAnyOrder(DOC_PUBLIC, DOC_PRIVATE);

        assertThat(spaceAccessService.currentVisibleSpaceIds()).isNull();
    }

    // ------------------------------------------------------------------ 辅助方法

    private String studentToken() throws Exception {
        return login("student", "123456");
    }

    private String login(String username, String password) throws Exception {
        return okData(postJson("/api/auth/login", Map.of("username", username, "password", password), null))
                .get("token").asText();
    }

    private String register(String username, String password) throws Exception {
        return okData(postJson("/api/auth/register", Map.of("username", username, "password", password), null))
                .get("token").asText();
    }

    /**
     * 收回某账号的全部空间授权。
     *
     * <p>注册与管理员建号都会自动带一条默认空间成员行，所以「零成员关系」这种状态
     * 只能显式造出来——它正是必须守住的那条下限：空可见集合等于查不到东西，绝不能等于全库。
     */
    private void revokeAllMemberships(String username) {
        Long userId = jdbcTemplate.queryForObject(
                "select id from sys_user where username = ?", Long.class, username);
        jdbcTemplate.update("delete from kb_space_member where user_id = ?", userId);
    }

    private List<Long> spaceIds(JsonNode spaces) {
        return java.util.stream.StreamSupport.stream(spaces.spliterator(), false)
                .map(node -> node.get("id").asLong())
                .toList();
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return token == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder postJson(String url, Map<String, ?> payload, String token)
            throws Exception {
        return withToken(post(url).contentType(MediaType.APPLICATION_JSON), token)
                .content(MAPPER.writeValueAsString(payload));
    }

    /**
     * 带 token 的 GET / DELETE。
     *
     * <p>方法名不叫 {@code get}/{@code delete}：类里只要声明了同名成员方法，就会盖掉
     * {@code MockMvcRequestBuilders} 的静态导入（不看参数个数），单参调用会编译成找不到方法。
     */
    private MockHttpServletRequestBuilder getWithToken(String url, String token) {
        return withToken(MockMvcRequestBuilders.get(url), token);
    }

    private MockHttpServletRequestBuilder deleteWithToken(String url, String token) {
        return withToken(MockMvcRequestBuilders.delete(url), token);
    }

    private JsonNode okData(RequestBuilder builder) throws Exception {
        JsonNode root = MAPPER.readTree(mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("接口返回码：%s", root).isZero();
        return root.get("data");
    }

    private JsonNode perform(RequestBuilder builder) throws Exception {
        return MAPPER.readTree(mockMvc.perform(builder)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int countMessages(long sessionId, String role) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id = ? and role = ?",
                Integer.class, sessionId, role);
        return count == null ? 0 : count;
    }

    /** SSE 异步推送：轮询已写出的响应体，直到出现终止事件 */
    private String awaitTerminalEvent(MvcResult result, String terminalEvent) throws Exception {
        long deadline = System.currentTimeMillis() + 8000L;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains("event:" + terminalEvent)) {
                return body;
            }
            Thread.sleep(50);
        }
        return body;
    }
}
