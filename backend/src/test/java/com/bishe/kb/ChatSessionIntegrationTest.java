package com.bishe.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话管理接口测试：列表排序、新建、删除级联、历史消息回读。
 *
 * <p>同样跑在 H2 内存库上，每个用例前重建种子数据，不接触开发库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatSessionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条真实的引用 JSON，用来验证落库/回读的结构一致性 */
    private static final String CITATION_JSON =
            "[{\"documentId\":1,\"title\":\"网络工程复习提纲\",\"chunkIndex\":0,\"snippet\":\"RAG 基本流程\",\"score\":null}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
    }

    @Test
    @DisplayName("GET /api/sessions：按最后活跃时间倒序，带上标题与时间戳")
    void listShouldOrderByUpdateTimeDesc() throws Exception {
        // 显式写 update_time，避免同一毫秒插入导致排序不稳定
        insertSession(1L, "最早聊的", "2026-01-01 10:00:00");
        insertSession(2L, "刚刚聊的", "2026-03-05 09:30:00");
        insertSession(3L, "中间那次", "2026-02-02 08:00:00");

        JsonNode data = okData(get("/api/sessions"));

        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(2L);
        assertThat(data.get(0).get("title").asText()).isEqualTo("刚刚聊的");
        assertThat(data.get(1).get("id").asLong()).isEqualTo(3L);
        assertThat(data.get(2).get("id").asLong()).isEqualTo(1L);
        assertThat(data.get(0).get("updateTime").asText()).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/sessions：不传 body 时标题默认「新对话」")
    void createWithoutBodyShouldUseDefaultTitle() throws Exception {
        JsonNode data = okData(post("/api/sessions"));

        assertThat(data.get("title").asText()).isEqualTo("新对话");
        assertThat(data.get("id").asLong()).isPositive();
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_session", Integer.class)).isOne();
    }

    @Test
    @DisplayName("POST /api/sessions：可以指定标题，空字符串仍然回落到默认标题")
    void createShouldAcceptCustomTitle() throws Exception {
        JsonNode named = okData(postJson("/api/sessions", Map.of("title", "期末复习")));
        assertThat(named.get("title").asText()).isEqualTo("期末复习");

        JsonNode blank = okData(postJson("/api/sessions", Map.of("title", "   ")));
        assertThat(blank.get("title").asText()).isEqualTo("新对话");
    }

    @Test
    @DisplayName("POST /api/sessions：标题超过 64 字被参数校验拦下，返回 4001")
    void createShouldRejectTooLongTitle() throws Exception {
        JsonNode body = MAPPER.readTree(mockMvc.perform(postJson("/api/sessions", Map.of("title", "标".repeat(65))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.get("code").asInt()).isEqualTo(4001);
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_session", Integer.class)).isZero();
    }

    @Test
    @DisplayName("DELETE /api/sessions/{id}：会话逻辑删除并从列表消失，名下消息被物理清理")
    void deleteShouldCascadeMessages() throws Exception {
        insertSession(1L, "要删掉的", "2026-03-05 09:30:00");
        insertMessage(1L, "user", "什么是知识库问答", null);
        insertMessage(1L, "assistant", "先检索再生成。", CITATION_JSON);
        insertSession(2L, "留下来的", "2026-03-01 09:30:00");
        insertMessage(2L, "user", "别删我", null);

        okData(delete("/api/sessions/1"));

        // 逻辑删除：行还在，但 deleted=1，接口层看不见了
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from chat_session where id = 1", Integer.class)).isOne();
        assertThat(okData(get("/api/sessions"))).hasSize(1);
        assertThat(okData(get("/api/sessions")).get(0).get("id").asLong()).isEqualTo(2L);
        // 消息是物理删除，否则会一直膨胀
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id = 1", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id = 2", Integer.class)).isOne();
    }

    @Test
    @DisplayName("GET /api/sessions/{id}/messages：按时间正序返回，citations 反序列化成结构化数组")
    void messagesShouldReturnParsedCitations() throws Exception {
        insertSession(1L, "历史会话", "2026-03-05 09:30:00");
        insertMessage(1L, "user", "什么是知识库问答", null);
        insertMessage(1L, "assistant", "先把文档切分成片段，再检索生成。", CITATION_JSON);
        insertMessage(1L, "user", "那第二步呢", null);

        JsonNode data = okData(get("/api/sessions/1/messages"));

        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("role").asText()).isEqualTo("user");
        // user 消息没有引用，返回空数组而不是 null，前端不用做判空
        assertThat(data.get(0).get("citations")).isEmpty();
        JsonNode assistant = data.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").asText()).contains("切分成片段");
        assertThat(assistant.get("citations")).hasSize(1);
        assertThat(assistant.get("citations").get(0).get("title").asText()).isEqualTo("网络工程复习提纲");
        assertThat(assistant.get("citations").get(0).get("chunkIndex").asInt()).isZero();
        assertThat(data.get(2).get("content").asText()).isEqualTo("那第二步呢");
    }

    @Test
    @DisplayName("访问不存在或已删除的会话：返回 4040，不抛 500")
    void unknownSessionShouldReturn4040() throws Exception {
        insertSession(1L, "已删除", "2026-03-05 09:30:00");
        jdbcTemplate.update("update chat_session set deleted = 1 where id = 1");

        JsonNode messages = MAPPER.readTree(mockMvc.perform(get("/api/sessions/1/messages"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(messages.get("code").asInt()).isEqualTo(4040);
        assertThat(messages.get("message").asText()).contains("会话不存在");

        JsonNode removed = MAPPER.readTree(mockMvc.perform(delete("/api/sessions/9999"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(removed.get("code").asInt()).isEqualTo(4040);
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 发请求、断言 code=0，返回 data 节点 */
    private JsonNode okData(RequestBuilder builder) throws Exception {
        JsonNode root = MAPPER.readTree(mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("接口返回码：%s", root).isZero();
        return root.get("data");
    }

    /** POST + JSON body（直接用 post(url, map) 会被当成 URI 变量重载，必须显式塞 body） */
    private RequestBuilder postJson(String url, Object body) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
    }

    private void insertSession(long id, String title, String updateTime) {
        // user_id=1 对应测试放行注入的管理员身份（auth.test-bypass）；会话按用户隔离后需归属一致才可见
        jdbcTemplate.update("insert into chat_session (id, user_id, title, create_time, update_time, deleted) "
                + "values (?, 1, ?, ?, ?, 0)", id, title, updateTime, updateTime);
    }

    private void insertMessage(long sessionId, String role, String content, String citations) {
        jdbcTemplate.update("insert into chat_message (session_id, role, content, citations) values (?, ?, ?, ?)",
                sessionId, role, content, citations);
    }
}
