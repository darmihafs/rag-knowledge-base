package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.security.UserContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 鉴权、空间授权、MyBatis/H2 与工具执行，仅替换外部模型。
 * 两个空间共享检索词，私有标题/正文哨兵同时检查浏览器响应和发给模型的观察。
 */
@SpringBootTest(properties = {
        "auth.test-bypass=false",
        "spring.datasource.url=jdbc:h2:mem:kb_agent;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgentIntegrationTest {
    private static final String ENDPOINT = "/api/agent/ask";
    private static final String STREAM_ENDPOINT = "/api/agent/ask/stream";
    private static final long SPACE_PUBLIC = 1L;
    private static final long SPACE_PRIVATE = 2L;
    private static final long DOC_PUBLIC = 11L;
    private static final long DOC_PRIVATE = 12L;
    private static final String QUESTION = "滑动窗口";
    private static final String PUBLIC_TITLE = "操作系统基线.pdf";
    private static final String PUBLIC_CONTENT = "滑动窗口 public-only-body：以加权公平队列平衡各进程令牌数。";
    private static final String PRIVATE_TITLE = "private-only-title.pdf";
    private static final String PRIVATE_CONTENT = "滑动窗口 private-only-body：不得向其它空间泄露的内部协议。";
    private static final String GROUNDED_ANSWER = "操作系统基线.pdf / 第1段：用加权公平队列平衡进程令牌数。";
    private static final String HALLUCINATION = "没有资料也宣称私有协议的窗口恒定为九十九。";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private RagProperties props;

    @MockitoBean
    private ChatModel chatModel;
    @MockitoBean
    private EmbeddingModel embeddingModel;
    // 本入口不使用流式模型，也替换它以保证上下文不会创建真实供应商客户端。
    @MockitoBean
    private StreamingChatModel streamingChatModel;

    private long studentId;
    private String originalApiKey;
    private boolean originalMockEnabled;
    private boolean originalEmbeddingEnabled;

    @BeforeEach
    void seedIsolatedH2() {
        UserContext.clear();
        originalApiKey = props.getApiKey();
        originalMockEnabled = props.isMockEnabled();
        originalEmbeddingEnabled = props.isEmbeddingEnabled();
        props.setApiKey("agent-test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);

        // 写种子之前验证连接，不能因配置回退而清理开发库。
        String url = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:h2:mem:kb_agent");
        jdbc.update("delete from kb_chunk");
        jdbc.update("delete from kb_document");
        jdbc.update("delete from kb_space_member");
        jdbc.update("delete from kb_space");
        studentId = jdbc.queryForObject("select id from sys_user where username = 'student'", Long.class);

        jdbc.update("insert into kb_space (id, name, created_by, doc_count, chunk_count, deleted) "
                + "values (?, ?, 1, 1, 1, 0)", SPACE_PUBLIC, "公开测试空间");
        jdbc.update("insert into kb_space (id, name, created_by, doc_count, chunk_count, deleted) "
                + "values (?, ?, 1, 1, 1, 0)", SPACE_PRIVATE, "私有测试空间");
        jdbc.update("insert into kb_space_member (space_id, user_id) values (?, 1)", SPACE_PUBLIC);
        jdbc.update("insert into kb_space_member (space_id, user_id) values (?, 1)", SPACE_PRIVATE);
        jdbc.update("insert into kb_space_member (space_id, user_id) values (?, ?)", SPACE_PUBLIC, studentId);
        jdbc.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, ?, ?, 'pdf', 'INDEXED', 1, 0)", DOC_PUBLIC, SPACE_PUBLIC, PUBLIC_TITLE);
        jdbc.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, ?, ?, 'pdf', 'INDEXED', 1, 0)", DOC_PRIVATE, SPACE_PRIVATE, PRIVATE_TITLE);
        insertChunk(1101L, DOC_PUBLIC, 0, 7, PUBLIC_CONTENT, 1);
        insertChunk(1201L, DOC_PRIVATE, 0, 11, PRIVATE_CONTENT, 1);
    }

    @AfterEach
    void restorePropertiesAndContext() {
        try {
            verifyNoInteractions(embeddingModel, streamingChatModel);
        } finally {
            props.setApiKey(originalApiKey);
            props.setMockEnabled(originalMockEnabled);
            props.setEmbeddingEnabled(originalEmbeddingEnabled);
            UserContext.clear();
        }
    }

    @Test
    void anonymousRequestIs401BeforeAnyModelCall() throws Exception {
        JsonNode root = json(mockMvc.perform(postJson(ENDPOINT, Map.of("question", QUESTION), null))
                .andExpect(status().isUnauthorized()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(root.path("code").asInt()).isEqualTo(4010);
        assertThat(root.path("data").isNull()).isTrue();
        verifyNoInteractions(chatModel);
    }

    @ParameterizedTest(name = "请求校验：{0}")
    @MethodSource("invalidRequests")
    void invalidRequestNeverReachesModel(String description, Map<String, ?> payload, String field)
            throws Exception {
        JsonNode root = perform(ENDPOINT, payload, studentToken());

        assertThat(root.path("code").asInt()).isEqualTo(4001);
        assertThat(root.path("message").asText()).contains(field);
        assertThat(root.path("data").isNull()).isTrue();
        verifyNoInteractions(chatModel);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("空问题", Map.of("question", ""), "question"),
                Arguments.of("纯空白", Map.of("question", " \t "), "question"),
                Arguments.of("缺少问题", Map.of("maxSteps", 2), "question"),
                Arguments.of("1001字", Map.of("question", "问".repeat(1001)), "question"),
                Arguments.of("轮数超过5", Map.of("question", QUESTION, "maxSteps", 6), "maxSteps"),
                Arguments.of("轮数为0", Map.of("question", QUESTION, "maxSteps", 0), "maxSteps"),
                Arguments.of("负空间ID", Map.of("question", QUESTION, "spaceId", -1), "spaceId"),
                Arguments.of("零空间ID", Map.of("question", QUESTION, "spaceId", 0), "spaceId"),
                Arguments.of("非法任务ID", Map.of("question", QUESTION, "taskId", "bad id!"), "taskId")
        );
    }

    @Test
    void foreignSpaceIs4030BeforeAnyModelCall() throws Exception {
        JsonNode root = perform(ENDPOINT, Map.of("question", QUESTION, "spaceId", SPACE_PRIVATE), studentToken());

        assertThat(root.path("code").asInt()).isEqualTo(4030);
        assertThat(root.path("data").isNull()).isTrue();
        assertThat(root.toString()).doesNotContain(PRIVATE_TITLE, PRIVATE_CONTENT);
        verifyNoInteractions(chatModel);
    }

    @Test
    void nativeToolLoopListsReadsAndAnswersWithMatchingCallIdsAndThreeSchemas() throws Exception {
        List<ChatRequest> requests = stubModel(request -> {
            List<ToolExecutionResultMessage> results = toolResults(request);
            return switch (results.size()) {
                case 0 -> toolReply(call("catalog-1", "list_documents", "{}"));
                case 1 -> {
                    assertThat(results.getFirst().id()).isEqualTo("catalog-1");
                    JsonNode documents = json(results.getFirst().text()).path("documents");
                    assertThat(documents).hasSize(1);
                    // 下一次调用的 documentId 真正来自上轮目录观察，而非绕过目录直接拼答案。
                    long id = documents.get(0).path("documentId").asLong();
                    yield toolReply(call("read-2", "read_chunks", readArgs(id)));
                }
                case 2 -> {
                    assertThat(results.getLast().id()).isEqualTo("read-2");
                    assertThat(json(results.getLast().text()).path("chunks").get(0).path("content").asText())
                            .isEqualTo(PUBLIC_CONTENT);
                    yield textReply(GROUNDED_ANSWER);
                }
                default -> throw new AssertionError("不应出现额外模型轮次");
            };
        });

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "COMPLETED", 3, 2);
        assertThat(data.path("answer").asText()).isEqualTo(GROUNDED_ANSWER);
        assertThat(data.path("mock").asBoolean()).isFalse();
        assertThat(data.path("elapsedMillis").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(requests).hasSize(3);
        assertThat(requests.getFirst().messages()).hasSize(2);
        assertThat(requests.getFirst().messages().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(requests.getFirst().messages().getLast()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) requests.getFirst().messages().getLast()).singleText()).isEqualTo(QUESTION);
        requests.forEach(this::assertThreeSchemas);
        assertToolResultsMatch(requests.get(1), data, "catalog-1");
        assertToolResultsMatch(requests.get(2), data, "catalog-1", "read-2");
        assertThat(observation(data, 0).path("documents")).hasSize(1);
        assertThat(observation(data, 0).path("documents").get(0).path("title").asText()).isEqualTo(PUBLIC_TITLE);
        assertPublicChunks(observation(data, 1));
        assertPublicCitation(data);
        assertNoPrivateContent(data, requests);
    }

    @Test
    void streamEmitsMetaStepAndDoneInOrder() throws Exception {
        stubModel(toolReply(call("stream-read", "read_chunks", readArgs(DOC_PUBLIC))), textReply(GROUNDED_ANSWER));

        MvcResult result = mockMvc.perform(postJson(STREAM_ENDPOINT, Map.of("question", QUESTION), studentToken()))
                .andExpect(status().isOk()).andReturn();
        String body = awaitAgentEvent(result, "done");

        assertThat(body).contains("event:meta", "event:step", "event:done");
        assertThat(body.indexOf("event:meta")).isLessThan(body.indexOf("event:step"));
        assertThat(body.indexOf("event:step")).isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("\"taskId\"", "\"timeoutMillis\":90000");
        assertThat(body).contains("\"tool\":\"read_chunks\"", "\"step\":1");
        JsonNode done = agentDone(body);
        assertThat(done.path("stopReason").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("answer").asText()).isEqualTo(GROUNDED_ANSWER);
        assertThat(done.path("steps")).hasSize(1);
        assertThat(done.path("citations")).hasSize(1);
        assertThat(done.path("steps").get(0).path("observation").asText()).contains(PUBLIC_CONTENT);
        assertNoPrivateContent(done, List.of());
    }

    @Test
    void cancelEndpointStopsRunningStreamAndReturnsCancelledResult() throws Exception {
        String taskId = "cancel-stream-0001";
        CountDownLatch enteredSecondRound = new CountDownLatch(1);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            if (toolResults(request).isEmpty()) {
                return toolReply(call("cancel-read", "read_chunks", readArgs(DOC_PUBLIC)));
            }
            enteredSecondRound.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试阻塞被取消中断");
            }
            return textReply(GROUNDED_ANSWER);
        }).when(chatModel).chat(any(ChatRequest.class));

        String token = studentToken();
        MvcResult result = mockMvc.perform(postJson(STREAM_ENDPOINT,
                        Map.of("question", QUESTION, "taskId", taskId), token))
                .andExpect(status().isOk()).andReturn();
        assertThat(awaitAgentEvent(result, "step")).contains("event:step");
        assertThat(enteredSecondRound.await(5, TimeUnit.SECONDS)).isTrue();

        JsonNode cancel = perform("/api/agent/tasks/" + taskId + "/cancel", Map.of(), token);
        assertThat(cancel.path("code").asInt()).isZero();
        assertThat(cancel.path("data").asBoolean()).isTrue();

        JsonNode done = agentDone(awaitAgentEvent(result, "done"));
        assertThat(done.path("stopReason").asText()).isEqualTo("CANCELLED");
        assertThat(done.path("answer").asText()).contains("任务已被取消");
        assertThat(done.path("steps")).hasSize(1);
        assertThat(done.path("citations")).hasSize(1);
        assertNoPrivateContent(done, List.of());
    }

    @Test
    void foreignOrFinishedTaskCannotBeCancelled() throws Exception {
        String taskId = "cancel-ownership-01";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return textReply(GROUNDED_ANSWER);
        }).when(chatModel).chat(any(ChatRequest.class));

        String token = studentToken();
        MvcResult result = mockMvc.perform(postJson(STREAM_ENDPOINT,
                        Map.of("question", QUESTION, "taskId", taskId), token))
                .andExpect(status().isOk()).andReturn();
        assertThat(awaitAgentEvent(result, "meta")).contains("event:meta");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 管理员不能借用取消接口终止别人的任务，返回 false 且不泄露任务是否存在。
        JsonNode foreign = perform("/api/agent/tasks/" + taskId + "/cancel", Map.of(), login("admin", "admin123"));
        assertThat(foreign.path("code").asInt()).isZero();
        assertThat(foreign.path("data").asBoolean()).isFalse();

        release.countDown();
        JsonNode done = agentDone(awaitAgentEvent(result, "done"));
        assertThat(done.path("stopReason").asText()).isNotEqualTo("CANCELLED");

        JsonNode finished = perform("/api/agent/tasks/" + taskId + "/cancel", Map.of(), token);
        assertThat(finished.path("code").asInt()).isZero();
        assertThat(finished.path("data").asBoolean()).isFalse();
    }

    @Test
    void searchKnowledgeOnlyReturnsVisibleEvidence() throws Exception {
        List<ChatRequest> requests = stubModel(
                toolReply(call("search-1", "search_knowledge", searchArgs())), textReply(GROUNDED_ANSWER));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "COMPLETED", 2, 1);
        assertPublicChunks(observation(data, 0));
        assertPublicCitation(data);
        assertToolResultsMatch(requests.get(1), data, "search-1");
        assertNoPrivateContent(data, requests);
    }

    @Test
    void modelDeclaringRetrievedChunksIrrelevantIsReportedAsNoEvidence() throws Exception {
        List<ChatRequest> requests = stubModel(
                toolReply(call("irrelevant-1", "search_knowledge", searchArgs())),
                textReply("知识库中未检索到相关内容。未取得可用的知识库证据，无法给出有依据的答案。请补充资料或调整问题。"));

        JsonNode data = ask(Map.of("question", "量子纠缠是什么？"), studentToken());

        assertOutcome(data, "NO_EVIDENCE", 2, 1);
        assertThat(data.path("citations")).isEmpty();
        assertThat(data.path("answer").asText()).contains("未取得可用的知识库证据");
        assertThat(data.path("steps")).hasSize(1);
        assertThat(observation(data, 0).path("chunks")).isNotEmpty();
        assertNoPrivateContent(data, requests);
    }

    @Test
    void guessedForeignDocumentIdProducesSafeToolErrorAndNoEvidence() throws Exception {
        List<ChatRequest> requests = stubModel(
                toolReply(call("guess-1", "read_chunks", readArgs(DOC_PRIVATE))), textReply(HALLUCINATION));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "NO_EVIDENCE", 2, 1);
        assertToolError(data, 0, "文档不可读取");
        assertNoEvidence(data);
        assertToolResultsMatch(requests.get(1), data, "guess-1");
        assertNoPrivateContent(data, requests);
    }

    @Test
    void adminSelectingOneSpaceCannotReadListOrSearchTheOtherSpace() throws Exception {
        List<ChatRequest> requests = stubModel(toolReply(
                call("admin-read", "read_chunks", readArgs(DOC_PRIVATE)),
                call("admin-list", "list_documents", "{}"),
                call("admin-search", "search_knowledge", searchArgs())), textReply(GROUNDED_ANSWER));

        JsonNode data = ask(Map.of("question", QUESTION, "spaceId", SPACE_PUBLIC), login("admin", "admin123"));

        assertOutcome(data, "COMPLETED", 2, 3);
        assertToolError(data, 0, "文档不可读取");
        JsonNode documents = observation(data, 1).path("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).path("documentId").asLong()).isEqualTo(DOC_PUBLIC);
        assertPublicChunks(observation(data, 2));
        assertPublicCitation(data);
        assertToolResultsMatch(requests.get(1), data, "admin-read", "admin-list", "admin-search");
        assertNoPrivateContent(data, requests);
    }

    @ParameterizedTest(name = "零成员：{0}")
    @ValueSource(strings = {"list_documents", "search_knowledge"})
    void zeroMembershipMeansEmptyResultsAndRefusesHallucination(String tool) throws Exception {
        String token = studentToken();
        jdbc.update("delete from kb_space_member where user_id = ?", studentId);
        List<ChatRequest> requests = stubModel(
                toolReply(call("empty-scope", tool, tool.equals("list_documents") ? "{}" : searchArgs())),
                textReply(HALLUCINATION));

        JsonNode data = ask(Map.of("question", QUESTION), token);

        assertOutcome(data, "NO_EVIDENCE", 2, 1);
        assertThat(data.path("steps").get(0).path("success").asBoolean()).isTrue();
        String resultKey = tool.equals("list_documents") ? "documents" : "chunks";
        assertThat(observation(data, 0).path(resultKey).isArray()).isTrue();
        assertThat(observation(data, 0).path(resultKey)).isEmpty();
        assertNoEvidence(data);
        assertToolResultsMatch(requests.get(1), data, "empty-scope");
        assertNoPrivateContent(data, requests);
        assertThat(data.toString()).doesNotContain(PUBLIC_TITLE, PUBLIC_CONTENT);
        assertThat(requests.toString()).doesNotContain(PUBLIC_TITLE, PUBLIC_CONTENT);
    }

    @ParameterizedTest(name = "不可用文档：{0}")
    @ValueSource(strings = {"PENDING", "FAILED", "DELETED"})
    void unindexedOrDeletedDocumentsAreExcludedFromAllTools(String state) throws Exception {
        if (state.equals("DELETED")) {
            jdbc.update("update kb_document set deleted = 1 where id = ?", DOC_PUBLIC);
        } else {
            jdbc.update("update kb_document set status = ? where id = ?", state, DOC_PUBLIC);
        }
        List<ChatRequest> requests = stubModel(toolReply(
                call("unavailable-list", "list_documents", "{}"),
                call("unavailable-search", "search_knowledge", searchArgs()),
                call("unavailable-read", "read_chunks", readArgs(DOC_PUBLIC))), textReply(HALLUCINATION));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "NO_EVIDENCE", 2, 3);
        assertThat(observation(data, 0).path("documents")).isEmpty();
        assertThat(observation(data, 1).path("chunks")).isEmpty();
        assertThat(data.path("steps").get(0).path("success").asBoolean()).isTrue();
        assertThat(data.path("steps").get(1).path("success").asBoolean()).isTrue();
        assertToolError(data, 2, "文档不可读取");
        assertNoEvidence(data);
        assertToolResultsMatch(requests.get(1), data, "unavailable-list", "unavailable-search", "unavailable-read");
        assertNoPrivateContent(data, requests);
        assertThat(data.toString()).doesNotContain(PUBLIC_TITLE, PUBLIC_CONTENT);
        assertThat(requests.toString()).doesNotContain(PUBLIC_TITLE, PUBLIC_CONTENT);
    }

    @Test
    void disabledChunksNeverAppearInReadsSearchOrCitations() throws Exception {
        String disabledContent = "滑动窗口 disabled-only-body：已撤下的错误资料。";
        insertChunk(1102L, DOC_PUBLIC, 1, 8, disabledContent, 0);
        List<ChatRequest> requests = stubModel(toolReply(
                call("enabled-read", "read_chunks", readArgs(DOC_PUBLIC)),
                call("enabled-search", "search_knowledge", searchArgs()),
                call("disabled-read", "read_chunks", "{\"documentId\":11,\"startIndex\":1}")),
                textReply(GROUNDED_ANSWER));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "COMPLETED", 2, 3);
        assertPublicChunks(observation(data, 0));
        assertPublicChunks(observation(data, 1));
        assertThat(observation(data, 2).path("chunks")).isEmpty();
        assertThat(data.path("steps")).allSatisfy(step -> assertThat(step.path("success").asBoolean()).isTrue());
        assertPublicCitation(data);
        assertToolResultsMatch(requests.get(1), data, "enabled-read", "enabled-search", "disabled-read");
        assertThat(data.toString()).doesNotContain(disabledContent);
        assertThat(requests.toString()).doesNotContain(disabledContent);
        assertNoPrivateContent(data, requests);
    }

    @ParameterizedTest(name = "工具错误后可纠正：{0}")
    @MethodSource("invalidToolCalls")
    void invalidToolArgumentsCannotWidenScopeAndCanBeCorrected(
            String description, String tool, String arguments, String error) throws Exception {
        List<ChatRequest> requests = stubModel(
                toolReply(call("bad-call", tool, arguments)),
                toolReply(call("fixed-call", "read_chunks", readArgs(DOC_PUBLIC))),
                textReply(GROUNDED_ANSWER));

        JsonNode data = ask(Map.of("question", QUESTION, "maxSteps", 3), studentToken());

        assertOutcome(data, "COMPLETED", 3, 2);
        assertToolError(data, 0, error);
        assertThat(data.path("steps").get(0).path("arguments").asText().length()).isLessThanOrEqualTo(2000);
        assertThat(data.path("steps").get(1).path("success").asBoolean()).isTrue();
        assertPublicChunks(observation(data, 1));
        assertPublicCitation(data);
        assertToolResultsMatch(requests.get(1), data, "bad-call");
        assertToolResultsMatch(requests.get(2), data, "bad-call", "fixed-call");
        assertNoPrivateContent(data, requests);
    }

    static Stream<Arguments> invalidToolCalls() {
        return Stream.of(
                Arguments.of("畸形JSON", "read_chunks", "{", "合法 JSON 对象"),
                Arguments.of("尾随JSON", "list_documents", "{} {}", "合法 JSON 对象"),
                Arguments.of("非对象JSON", "list_documents", "[]", "JSON 对象"),
                Arguments.of("超长args", "list_documents", "{\"keyword\":\"" + "x".repeat(2001) + "\"}", "2000"),
                Arguments.of("目录注入spaceId", "list_documents", "{\"spaceId\":2}", "不支持的参数"),
                Arguments.of("检索注入spaceId", "search_knowledge", "{\"query\":\"滑动窗口\",\"spaceId\":2}", "不支持的参数"),
                Arguments.of("读取注入spaceId", "read_chunks", "{\"documentId\":12,\"spaceId\":2}", "不支持的参数"),
                Arguments.of("documentId数值字符串", "read_chunks", "{\"documentId\":\"12\"}", "整数参数非法"),
                Arguments.of("limit数值字符串", "search_knowledge", "{\"query\":\"滑动窗口\",\"limit\":\"5\"}", "整数参数非法"),
                Arguments.of("未知工具", "delete_documents", "{\"documentId\":12}", "未知工具")
        );
    }

    @ParameterizedTest(name = "持续调用在第{0}轮停止")
    @ValueSource(ints = {1, 3, 5})
    void endlessToolCallsStopAtRequestedStepBudget(int maxSteps) throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        stubModel(request -> toolReply(call("loop-" + sequence.incrementAndGet(), "list_documents", "{}")));

        JsonNode data = ask(Map.of("question", QUESTION, "maxSteps", maxSteps), studentToken());

        assertOutcome(data, "STEP_LIMIT", maxSteps, maxSteps);
        assertThat(sequence.get()).isEqualTo(maxSteps);
        assertThat(data.path("steps")).allSatisfy(step -> assertThat(step.path("success").asBoolean()).isTrue());
        assertThat(data.path("answer").asText()).contains("模型轮数上限");
    }

    @Test
    void nineCallsInOneResponseExecuteOnlyEightAndStopAtToolLimit() throws Exception {
        List<ToolExecutionRequest> calls = new ArrayList<>(IntStream.rangeClosed(1, 8)
                .mapToObj(i -> call("batch-" + i, "list_documents", "{}")).toList());
        // 第九次若被错误执行就会产生 citation，因此不能只断言显示了八条日志。
        calls.add(call("batch-9", "read_chunks", readArgs(DOC_PUBLIC)));
        stubModel(toolReply(calls.toArray(ToolExecutionRequest[]::new)));

        JsonNode data = ask(Map.of("question", QUESTION, "maxSteps", 5), studentToken());

        assertOutcome(data, "TOOL_LIMIT", 1, 8);
        assertThat(data.path("steps")).allSatisfy(step -> {
            assertThat(step.path("tool").asText()).isEqualTo("list_documents");
            assertThat(step.path("success").asBoolean()).isTrue();
        });
        assertThat(data.path("citations")).isEmpty();
        assertThat(data.path("answer").asText()).contains("8 次工具调用上限");
        assertThat(data.toString()).doesNotContain(PUBLIC_CONTENT);
    }

    @Test
    void repeatedReadsDeduplicateCitationsByDocumentAndChunkIndex() throws Exception {
        List<ChatRequest> requests = stubModel(
                toolReply(call("read-first", "read_chunks", readArgs(DOC_PUBLIC))),
                toolReply(call("read-again", "read_chunks", readArgs(DOC_PUBLIC))), textReply(GROUNDED_ANSWER));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "COMPLETED", 3, 2);
        assertPublicChunks(observation(data, 0));
        assertPublicChunks(observation(data, 1));
        assertThat(observation(data, 0)).isEqualTo(observation(data, 1));
        assertPublicCitation(data);
        assertToolResultsMatch(requests.get(2), data, "read-first", "read-again");
    }

    @Test
    void modelExceptionIsSanitizedWithoutDiscardingSuccessfulToolRecords() throws Exception {
        String diagnostic = "provider-internal-detail https://provider.invalid/private token=not-a-real-secret";
        List<ChatRequest> requests = stubModel(request -> {
            if (toolResults(request).isEmpty()) {
                return toolReply(call("before-error", "read_chunks", readArgs(DOC_PUBLIC)));
            }
            throw new IllegalStateException(diagnostic);
        });

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "MODEL_ERROR", 2, 1);
        assertThat(data.path("answer").asText()).contains("模型调用失败");
        assertThat(data.toString()).doesNotContain(diagnostic, "provider-internal-detail", "token=", "IllegalStateException");
        assertPublicChunks(observation(data, 0));
        assertPublicCitation(data);
        assertNoPrivateContent(data, requests);
    }

    @Test
    void explicitMockModeUsesRealFixedSearchWithoutAnyModelCallsOrKey() throws Exception {
        props.setMockEnabled(true);
        props.setApiKey("");
        // 即使全局启用了 embedding，演示流程也必须只走词法；AfterEach 断言模型零交互。
        props.setEmbeddingEnabled(true);

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "COMPLETED", 0, 1);
        assertThat(data.path("mock").asBoolean()).isTrue();
        assertThat(data.path("answer").asText()).contains("演示模式", "固定词法检索流程", "未调用对话或向量模型");
        assertThat(data.path("steps").get(0).path("tool").asText()).isEqualTo("search_knowledge");
        assertThat(json(data.path("steps").get(0).path("arguments").asText()).path("query").asText()).isEqualTo(QUESTION);
        assertPublicChunks(observation(data, 0));
        assertPublicCitation(data);
        assertNoPrivateContent(data, List.of());
        verifyNoInteractions(chatModel);
    }

    @Test
    void directModelAnswerWithoutToolEvidenceIsReplacedWithRefusal() throws Exception {
        stubModel(textReply(HALLUCINATION));

        JsonNode data = ask(Map.of("question", QUESTION), studentToken());

        assertOutcome(data, "NO_EVIDENCE", 1, 0);
        assertNoEvidence(data);
    }

    @ParameterizedTest(name = "返回前撤销权限，显式选择空间={0}")
    @ValueSource(booleans = {false, true})
    void revokedMembershipInsideModelCallbackDiscardsAlreadyReadObservations(boolean selectedSpace)
            throws Exception {
        String token = studentToken();
        AtomicInteger callbacks = new AtomicInteger();
        List<ChatRequest> requests = stubModel(request -> {
            if (callbacks.incrementAndGet() == 1) {
                return toolReply(call("before-revoke", "read_chunks", readArgs(DOC_PUBLIC)));
            }
            List<ToolExecutionResultMessage> results = toolResults(request);
            assertThat(results).hasSize(1);
            assertThat(json(results.getFirst().text()).path("chunks").get(0).path("content").asText())
                    .isEqualTo(PUBLIC_CONTENT);
            assertThat(jdbc.update("delete from kb_space_member where user_id = ? and space_id = ?",
                    studentId, SPACE_PUBLIC)).isOne();
            return textReply(PUBLIC_CONTENT);
        });
        Map<String, ?> payload = selectedSpace
                ? Map.of("question", QUESTION, "spaceId", SPACE_PUBLIC)
                : Map.of("question", QUESTION);

        JsonNode root = perform(ENDPOINT, payload, token);

        assertThat(root.path("code").asInt()).isEqualTo(4030);
        assertThat(root.path("data").isNull()).isTrue();
        assertThat(root.toString()).doesNotContain(PUBLIC_TITLE, PUBLIC_CONTENT, PRIVATE_TITLE, PRIVATE_CONTENT);
        assertThat(callbacks.get()).isEqualTo(2);
        assertThat(requests).hasSize(2);
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
        verifyNoMoreInteractions(chatModel);
    }

    // --------------------------------------------------------------- HTTP / 模型 / 断言辅助

    private void insertChunk(long id, long documentId, int index, int page, String content, int enabled) {
        jdbc.update("insert into kb_chunk (id, document_id, chunk_index, page_number, content, char_count, enabled) "
                + "values (?, ?, ?, ?, ?, ?, ?)", id, documentId, index, page, content, content.length(), enabled);
    }

    private String studentToken() throws Exception {
        return login("student", "123456");
    }

    private String login(String username, String password) throws Exception {
        JsonNode root = perform("/api/auth/login", Map.of("username", username, "password", password), null);
        assertThat(root.path("code").asInt()).as("登录响应：%s", root).isZero();
        assertThat(root.path("data").path("token").asText()).isNotBlank();
        return root.path("data").path("token").asText();
    }

    private JsonNode ask(Map<String, ?> payload, String token) throws Exception {
        JsonNode root = perform(ENDPOINT, payload, token);
        assertThat(root.path("code").asInt()).as("智能体响应：%s", root).isZero();
        assertThat(root.path("data").isObject()).isTrue();
        return root.path("data");
    }

    private JsonNode perform(String endpoint, Map<String, ?> payload, String token) throws Exception {
        return json(mockMvc.perform(postJson(endpoint, payload, token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder postJson(String endpoint, Map<String, ?> payload, String token)
            throws JsonProcessingException {
        MockHttpServletRequestBuilder request = post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload));
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new AssertionError("响应/工具观察不是合法JSON", e);
        }
    }

    /** SSE 异步推送：轮询响应缓冲，直到目标事件出现或超时。 */
    private String awaitAgentEvent(MvcResult result, String event) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000L;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains("event:" + event)) {
                return body;
            }
            Thread.sleep(30);
        }
        return body;
    }

    /** 从 SSE 响应里取出 done 事件携带的完整结果 JSON。 */
    private JsonNode agentDone(String body) {
        for (String frame : body.split("\n\n")) {
            if (!frame.contains("event:done")) {
                continue;
            }
            for (String line : frame.split("\n")) {
                if (line.startsWith("data:")) {
                    return json(line.substring("data:".length()));
                }
            }
        }
        throw new AssertionError("没有找到 done 事件：" + body);
    }

    private JsonNode observation(JsonNode data, int step) {
        return json(data.path("steps").get(step).path("observation").asText());
    }

    private static String readArgs(long documentId) {
        return "{\"documentId\":" + documentId + "}";
    }

    private static String searchArgs() {
        return "{\"query\":\"滑动窗口\",\"limit\":5}";
    }

    private static ToolExecutionRequest call(String id, String tool, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(arguments).build();
    }

    private static ChatResponse toolReply(ToolExecutionRequest... calls) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(calls))).build();
    }

    private static ChatResponse textReply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private List<ChatRequest> stubModel(ChatResponse... responses) {
        AtomicInteger cursor = new AtomicInteger();
        return stubModel(request -> {
            int index = cursor.getAndIncrement();
            assertThat(index).as("不应发起脚本之外的模型调用").isLessThan(responses.length);
            return responses[index];
        });
    }

    private List<ChatRequest> stubModel(Function<ChatRequest, ChatResponse> responder) {
        List<ChatRequest> requests = new ArrayList<>();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            requests.add(request);
            return responder.apply(request);
        }).when(chatModel).chat(any(ChatRequest.class));
        return requests;
    }

    private static List<ToolExecutionResultMessage> toolResults(ChatRequest request) {
        return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList();
    }

    private void assertOutcome(JsonNode data, String stopReason, int modelCalls, int steps) {
        assertThat(data.path("stopReason").asText()).isEqualTo(stopReason);
        assertThat(data.path("modelCalls").asInt()).isEqualTo(modelCalls);
        assertThat(data.path("steps")).hasSize(steps);
        for (int i = 0; i < steps; i++) {
            assertThat(data.path("steps").get(i).path("step").asInt()).isEqualTo(i + 1);
        }
        verify(chatModel, times(modelCalls)).chat(any(ChatRequest.class));
        verifyNoMoreInteractions(chatModel);
    }

    private void assertThreeSchemas(ChatRequest request) {
        assertThat(request.maxOutputTokens()).isEqualTo(1800);
        assertThat(request.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("search_knowledge", "list_documents", "read_chunks");
        for (ToolSpecification spec : request.toolSpecifications()) {
            assertThat(spec.parameters().properties()).doesNotContainKey("spaceId");
            switch (spec.name()) {
                case "search_knowledge" -> {
                    assertThat(spec.parameters().properties()).containsOnlyKeys("query", "limit");
                    assertThat(spec.parameters().properties().get("query")).isInstanceOf(JsonStringSchema.class);
                    assertThat(spec.parameters().required()).containsExactly("query");
                }
                case "list_documents" -> {
                    assertThat(spec.parameters().properties()).containsOnlyKeys("keyword", "limit");
                    assertThat(spec.parameters().properties().get("keyword")).isInstanceOf(JsonStringSchema.class);
                    assertThat(spec.parameters().required()).isNullOrEmpty();
                }
                case "read_chunks" -> {
                    assertThat(spec.parameters().properties()).containsOnlyKeys("documentId", "startIndex", "limit");
                    assertThat(spec.parameters().properties().get("documentId")).isInstanceOf(JsonIntegerSchema.class);
                    assertThat(spec.parameters().required()).containsExactly("documentId");
                }
                default -> throw new AssertionError("工具白名单之外的schema");
            }
            assertThat(spec.parameters().properties().get("limit")).isInstanceOf(JsonIntegerSchema.class);
        }
    }

    private void assertToolResultsMatch(ChatRequest request, JsonNode data, String... ids) {
        List<ToolExecutionResultMessage> results = toolResults(request);
        assertThat(results).extracting(ToolExecutionResultMessage::id).containsExactly(ids);
        List<ToolExecutionRequest> calls = request.messages().stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).flatMap(message -> message.toolExecutionRequests().stream()).toList();
        assertThat(calls).extracting(ToolExecutionRequest::id).containsExactly(ids);
        for (int i = 0; i < results.size(); i++) {
            ToolExecutionResultMessage result = results.get(i);
            assertThat(result.toolName()).isEqualTo(calls.get(i).name());
            assertThat(result.toolName()).isEqualTo(data.path("steps").get(i).path("tool").asText());
            assertThat(result.text()).isEqualTo(data.path("steps").get(i).path("observation").asText());
            assertThat(request.messages().indexOf(result)).isGreaterThan(1);
        }
    }

    private void assertPublicChunks(JsonNode observation) {
        JsonNode chunks = observation.path("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).path("documentId").asLong()).isEqualTo(DOC_PUBLIC);
        assertThat(chunks.get(0).path("title").asText()).isEqualTo(PUBLIC_TITLE);
        assertThat(chunks.get(0).path("chunkIndex").asInt()).isZero();
        assertThat(chunks.get(0).path("pageNumber").asInt()).isEqualTo(7);
        assertThat(chunks.get(0).path("content").asText()).isEqualTo(PUBLIC_CONTENT);
    }

    private void assertPublicCitation(JsonNode data) {
        assertThat(data.path("citations")).hasSize(1);
        JsonNode citation = data.path("citations").get(0);
        assertThat(citation.path("documentId").asLong()).isEqualTo(DOC_PUBLIC);
        assertThat(citation.path("title").asText()).isEqualTo(PUBLIC_TITLE);
        assertThat(citation.path("chunkIndex").asInt()).isZero();
        assertThat(citation.path("pageNumber").asInt()).isEqualTo(7);
        assertThat(citation.path("snippet").asText()).isEqualTo(PUBLIC_CONTENT);
    }

    private void assertToolError(JsonNode data, int step, String error) {
        assertThat(data.path("steps").get(step).path("success").asBoolean()).isFalse();
        JsonNode observation = observation(data, step);
        assertThat(observation.size()).isOne();
        assertThat(observation.path("error").asText()).contains(error);
    }

    private void assertNoEvidence(JsonNode data) {
        assertThat(data.path("citations")).isEmpty();
        assertThat(data.path("answer").asText()).contains("未取得可用的知识库证据").doesNotContain(HALLUCINATION);
    }

    private void assertNoPrivateContent(JsonNode data, List<ChatRequest> requests) {
        assertThat(data.toString()).doesNotContain(PRIVATE_TITLE, PRIVATE_CONTENT);
        assertThat(requests.toString()).doesNotContain(PRIVATE_TITLE, PRIVATE_CONTENT);
    }
}
