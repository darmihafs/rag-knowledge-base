package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式问答链路测试：用 Mockito 顶掉真实大模型，验证 SSE 事件序列、多轮上下文与消息落库。
 *
 * <p>不依赖任何 API Key，也不接触开发库（H2 内存库 + 每次重建种子数据）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagStreamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOC_TITLE = "网络工程复习提纲";
    private static final long SESSION_ID = 1L;
    private static final String STREAM_URL = "/api/knowledge/ask/stream";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @MockitoBean
    private StreamingChatModel streamingChatModel;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("insert into kb_document (id, title, file_type, status, chunk_count, deleted) "
                + "values (1, ?, 'txt', 'INDEXED', 2, 0)", DOC_TITLE);
        jdbcTemplate.update("insert into kb_chunk (document_id, chunk_index, content, char_count) values (1, 0, ?, 40)",
                "知识库问答系统先把文档切分成片段，再做检索与生成，这就是 RAG 的基本流程。");
        jdbcTemplate.update("insert into kb_chunk (document_id, chunk_index, content, char_count) values (1, 1, ?, 30)",
                "TCP 三次握手与四次挥手是网络工程考试的常考内容。");
        // user_id=1 对应测试放行注入的管理员身份（见 application-test.yml 的 auth.test-bypass），
        // 会话按用户隔离后，归属必须与当前登录用户一致才能被 requireSession 放行
        jdbcTemplate.update("insert into chat_session (id, user_id, title, deleted) values (?, 1, '新对话', 0)", SESSION_ID);

        props.setApiKey("test-key-not-real");
        props.setMockEnabled(false);

        // 流式模型：吐两个增量后正常结束
        Mockito.doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("知识库");
            handler.onPartialResponse("会先检索片段再生成答案。");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("ignored")).build());
            return null;
        }).when(streamingChatModel).chat(Mockito.anyList(), Mockito.any(StreamingChatResponseHandler.class));

        // 同步模型：直接返回固定答案
        Mockito.when(chatModel.chat(Mockito.<List<ChatMessage>>any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("这是同步返回的答案")).build());
    }

    @AfterEach
    void restore() {
        props.setMockEnabled(false);
        props.setApiKey("test-key-not-real");
        props.setHistoryMessages(6);
    }

    @Test
    @DisplayName("流式问答：按 meta -> delta -> citations -> done 顺序推送，引用带文档名与片段序号")
    void streamShouldEmitFullEventSequence() throws Exception {
        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", 3));
        String body = awaitTerminalEvent(result, "done");

        assertThat(body).contains("event:meta");
        assertThat(body).contains("event:delta");
        // 两个增量都被推给前端
        assertThat(joinDeltas(body)).isEqualTo("知识库会先检索片段再生成答案。");
        // 引用来源在回答之后推送，带文档名与片段序号
        assertThat(body).contains("event:citations");
        assertThat(body).contains(DOC_TITLE);
        assertThat(body).contains("chunkIndex");
        assertThat(body).contains("event:done");
        assertThat(body).contains("elapsedMillis");
        // done 带回落库后的消息主键：落库已改为同步，前端拿到它才能给这条回答点赞/点踩
        Long answerId = jdbcTemplate.queryForObject(
                "select id from chat_message where session_id = ? and role = 'assistant' order by id desc limit 1",
                Long.class, SESSION_ID);
        assertThat(body).contains("\"messageId\":" + answerId);
        // meta 里报告了召回条数与会话
        assertThat(body).contains("\"retrieved\":1");
        assertThat(body).contains("\"sessionId\":1");
    }

    @Test
    @DisplayName("一轮问答后：user 与 assistant 消息都落库，assistant 带 citations JSON")
    void streamShouldPersistBothSidesOfTheTurn() throws Exception {
        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", 3));
        awaitTerminalEvent(result, "done");

        Map<String, String> user = awaitMessage(SESSION_ID, "user");
        assertThat(user.get("content")).isEqualTo("什么是知识库问答");
        assertThat(user.get("citations")).isNull();

        // assistant 是异步落库的，轮询等它出现
        Map<String, String> assistant = awaitMessage(SESSION_ID, "assistant");
        assertThat(assistant.get("content")).isEqualTo("知识库会先检索片段再生成答案。");
        assertThat(assistant.get("citations")).contains(DOC_TITLE).contains("chunkIndex");

        // 引用能被反序列化回结构化数据，前端才能复用同一套渲染
        var citations = MAPPER.readTree(assistant.get("citations"));
        assertThat(citations.isArray()).isTrue();
        assertThat(citations.get(0).get("title").asText()).isEqualTo(DOC_TITLE);
    }

    @Test
    @DisplayName("首轮提问自动把「新对话」改成问题前 15 字，并刷新会话活跃时间")
    void firstQuestionShouldRenameSession() throws Exception {
        String longQuestion = "请详细说明知识库问答系统是如何完成检索与生成这两个步骤的";
        MvcResult result = postJson(STREAM_URL, ask(longQuestion, 3));
        awaitTerminalEvent(result, "done");
        awaitMessage(SESSION_ID, "assistant");

        String title = jdbcTemplate.queryForObject(
                "select title from chat_session where id = ?", String.class, SESSION_ID);
        assertThat(title).isEqualTo(longQuestion.substring(0, 15) + "…");
        assertThat(jdbcTemplate.queryForObject(
                "select update_time from chat_session where id = ?", java.sql.Timestamp.class, SESSION_ID))
                .isNotNull();
    }

    @Test
    @DisplayName("多轮上下文：最近的历史问答按时间正序进入 Prompt，当前问题排在最后")
    @SuppressWarnings("unchecked")
    void historyShouldBeFedToTheModelInOrder() throws Exception {
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (?, 'user', ?)",
                SESSION_ID, "什么是知识库问答");
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (?, 'assistant', ?)",
                SESSION_ID, "它先检索片段再生成答案。");

        // 追问里带「知识库」，既像真实的多轮指代，又能被关键词召回命中（否则会走无召回分支不调模型）
        String followUp = "那知识库问答的第二步是什么";
        MvcResult result = postJson(STREAM_URL, ask(followUp, 3));
        awaitTerminalEvent(result, "done");

        // meta 要告诉前端本轮带了几条历史
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"history\":2");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(streamingChatModel).chat(captor.capture(), Mockito.any(StreamingChatResponseHandler.class));
        List<ChatMessage> sent = captor.getValue();

        // [0]=系统提示(含资料) [1]=历史user [2]=历史assistant [3]=当前问题
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) sent.get(0)).text()).contains(DOC_TITLE);
        assertThat(((UserMessage) sent.get(1)).singleText()).isEqualTo("什么是知识库问答");
        assertThat(((AiMessage) sent.get(2)).text()).isEqualTo("它先检索片段再生成答案。");
        assertThat(((UserMessage) sent.get(3)).singleText()).isEqualTo(followUp);
    }

    @Test
    @DisplayName("历史条数受 rag.history-messages 限制：只带最近 N 条")
    void historyShouldRespectTheConfiguredLimit() throws Exception {
        props.setHistoryMessages(2);
        for (int i = 1; i <= 4; i++) {
            jdbcTemplate.update("insert into chat_message (session_id, role, content) values (?, 'user', ?)",
                    SESSION_ID, "历史问题" + i);
        }

        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", 3));
        awaitTerminalEvent(result, "done");

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"history\":2");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select role, content from chat_message where session_id = ? order by id", SESSION_ID);
        // 4 条历史 + 本轮 user + 本轮 assistant
        assertThat(rows).hasSize(6);
    }

    @Test
    @DisplayName("召回为空：不报错，推送提示语与空引用后正常结束，并且照样落库")
    void streamWithoutHitShouldFinishGracefully() throws Exception {
        MvcResult result = postJson(STREAM_URL, ask("量子纠缠的数学推导", null));
        String body = awaitTerminalEvent(result, "done");

        assertThat(body).contains("\"retrieved\":0");
        // 逐字推送会把句子拆成多个 delta，断言前先拼回完整回答
        assertThat(joinDeltas(body)).contains("没有检索到");
        assertThat(body).contains("event:citations");
        assertThat(body).contains("event:done");
        assertThat(body).doesNotContain("event:error");

        Map<String, String> assistant = awaitMessage(SESSION_ID, "assistant");
        assertThat(assistant.get("content")).contains("没有检索到");
        assertThat(assistant.get("citations")).isNull();
    }

    @Test
    @DisplayName("追问但本轮无召回：有历史时仍调用模型基于上下文作答，而不是直接回「没有检索到」")
    void followUpWithoutHitShouldUseHistory() throws Exception {
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (?, 'user', ?)",
                SESSION_ID, "什么是知识库问答");
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (?, 'assistant', ?)",
                SESSION_ID, "它先检索片段再生成答案。");

        // 这个追问不含任何能命中语料的关键词，retrieved 会是 0，但有历史 -> 应走模型分支
        MvcResult result = postJson(STREAM_URL, ask("那它具体是怎么做的呢", 3));
        String body = awaitTerminalEvent(result, "done");

        assertThat(body).contains("\"retrieved\":0");
        assertThat(body).contains("\"history\":2");
        // 拿到的是 mock 模型的增量，而不是固定的无召回提示
        assertThat(joinDeltas(body)).isEqualTo("知识库会先检索片段再生成答案。");
        assertThat(joinDeltas(body)).doesNotContain("没有检索到");
        Mockito.verify(streamingChatModel).chat(Mockito.anyList(), Mockito.any(StreamingChatResponseHandler.class));

        // 追问分支同样落库，本轮没有新召回所以引用为空
        Map<String, String> persisted = awaitMessage(SESSION_ID, "assistant");
        assertThat(persisted.get("content")).isEqualTo("知识库会先检索片段再生成答案。");
        assertThat(persisted.get("citations")).isNull();
    }

    @Test
    @DisplayName("会话不存在：以 error 事件结束并给出 4040，不产生任何消息")
    void streamWithUnknownSessionShouldEmitError() throws Exception {
        MvcResult result = postJson(STREAM_URL, askWithSession("什么是知识库问答", 9999L, null));
        String body = awaitTerminalEvent(result, "error");

        assertThat(body).contains("event:error");
        assertThat(body).contains("4040");
        assertThat(body).contains("会话不存在");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message", Integer.class)).isZero();
    }

    @Test
    @DisplayName("缺少 sessionId：参数校验直接拦下，返回 4001")
    void streamWithoutSessionIdShouldBeRejected() throws Exception {
        MvcResult result = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("question", "什么是知识库问答"))))
                .andExpect(status().isOk())
                .andReturn();

        var body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asInt()).isEqualTo(4001);
        assertThat(body.get("message").asText()).contains("sessionId");
    }

    @Test
    @DisplayName("未配置密钥：推送 error 事件并给出可操作的提示")
    void streamWithoutApiKeyShouldEmitError() throws Exception {
        props.setApiKey("");

        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", null));
        String body = awaitTerminalEvent(result, "error");

        assertThat(body).contains("event:error");
        assertThat(body).contains("5001");
        assertThat(body).contains("RAG_API_KEY");
    }

    @Test
    @DisplayName("演示模式：不调模型也能逐字推送，并标注 mock=true")
    void mockModeShouldStreamWithoutModel() throws Exception {
        props.setApiKey("");
        props.setMockEnabled(true);

        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", null));
        String body = awaitTerminalEvent(result, "done");

        assertThat(body).contains("\"mock\":true");
        assertThat(joinDeltas(body)).contains("演示模式");
        assertThat(body).contains(DOC_TITLE);
        assertThat(body).contains("event:done");
        // 演示模式不应该触碰模型
        Mockito.verify(streamingChatModel, Mockito.never())
                .chat(Mockito.anyList(), Mockito.any(StreamingChatResponseHandler.class));
        // 但演示模式同样要落库，刷新页面才不会丢
        assertThat(awaitMessage(SESSION_ID, "assistant").get("content")).contains("演示模式");
    }

    @Test
    @DisplayName("模型调用失败：转成 error 事件，且不把半截回答写进历史")
    void streamShouldEmitErrorWhenModelFails() throws Exception {
        Mockito.doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("模型已经吐了一半");
            handler.onError(new IllegalStateException("401 Unauthorized: incorrect api key"));
            return null;
        }).when(streamingChatModel).chat(Mockito.anyList(), Mockito.any(StreamingChatResponseHandler.class));

        MvcResult result = postJson(STREAM_URL, ask("什么是知识库问答", null));
        String body = awaitTerminalEvent(result, "error");

        assertThat(body).contains("event:error");
        assertThat(body).contains("5002");
        assertThat(body).contains("incorrect api key");
        // user 消息保留，assistant 不落库
        assertThat(awaitMessage(SESSION_ID, "user").get("content")).isEqualTo("什么是知识库问答");
        assertThat(countMessages(SESSION_ID, "assistant")).isZero();
    }

    @Test
    @DisplayName("同步问答接口仍可用：返回答案与引用，不要求 sessionId")
    void syncAskShouldStillWork() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("question", "什么是知识库问答"))))
                .andExpect(status().isOk())
                .andReturn();

        var body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("answer").asText()).isEqualTo("这是同步返回的答案");
        assertThat(body.get("data").get("citations").get(0).get("title").asText()).isEqualTo(DOC_TITLE);
        // 同步接口是无状态的，不该往会话里写东西
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    private Map<String, Object> ask(String question, Integer topK) {
        return askWithSession(question, SESSION_ID, topK);
    }

    private Map<String, Object> askWithSession(String question, Long sessionId, Integer topK) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("question", question);
        payload.put("sessionId", sessionId);
        if (topK != null) {
            payload.put("topK", topK);
        }
        return payload;
    }

    private MvcResult postJson(String url, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 把 SSE 里的 delta 事件按顺序拼回完整回答文本 */
    private String joinDeltas(String body) {
        StringBuilder text = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.startsWith("data:") || !line.contains("\"text\"")) {
                continue;
            }
            try {
                text.append(MAPPER.readTree(line.substring("data:".length())).get("text").asText());
            } catch (Exception ignored) {
                // 非 JSON 的 data 行直接跳过
            }
        }
        return text.toString();
    }

    /** SSE 是异步推送，轮询读取 MockHttpServletResponse 里已写出的内容，直到出现终止事件 */
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

    private int countMessages(long sessionId, String role) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id = ? and role = ?",
                Integer.class, sessionId, role);
        return count == null ? 0 : count;
    }

    /**
     * assistant 消息是异步落库的，轮询等它出现（最多 5 秒）。
     *
     * <p>返回 HashMap 而不是 Map.of：user 消息的 citations 在库里就是 NULL，
     * Map.of 不接受 null 值，会被迫用哨兵字符串，断言反而变绕。
     */
    private Map<String, String> awaitMessage(long sessionId, String role) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select content, citations from chat_message where session_id = ? and role = ? order by id",
                    sessionId, role);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(rows.size() - 1);
                Map<String, String> message = new HashMap<>();
                message.put("content", String.valueOf(row.get("content")));
                Object citations = row.get("citations");
                message.put("citations", citations == null ? null : String.valueOf(citations));
                return message;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 " + role + " 消息落库超时，sessionId=" + sessionId);
    }
}
