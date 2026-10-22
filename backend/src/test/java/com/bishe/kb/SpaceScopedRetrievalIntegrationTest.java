package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.RetrievalService.Route;
import com.bishe.kb.service.impl.VectorIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库空间收敛检索的集成测试：{@code spaceId} 必须真正下推到三路召回，
 * 而不是「先全库召回再在内存里挑」。
 *
 * <p>种子的两条切片<b>分属两个空间、但都含「滑动窗口」</b>——这是最能暴露假过滤的构造：
 * 如果只是在召回之后按空间筛，topK 会被别的空间占满，收敛空间时命中数莫名变少；
 * 而下推到 SQL 的过滤永远只在目标空间内部截断。
 *
 * <p>语义路由 {@link MockitoBean} 顶掉向量模型（所有切片给同一个向量，余弦相似度都是 1.0），
 * 这样两条切片都会先进向量库、再被空间过滤掉一条，能确切证明「回表那层也在收敛」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaceScopedRetrievalIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 空间 1「默认知识库」里的文档，切片与空间 2 共用「滑动窗口」这个词 */
    private static final long SPACE_DEFAULT = 1L;
    /** 空间 2「协议库」，测试里作为收敛目标 */
    private static final long SPACE_NETWORK = 2L;

    private static final long CHUNK_OS = 101L;
    private static final long CHUNK_NET = 102L;

    private static final String QUESTION = "滑动窗口是怎么工作的";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void seed() {
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");

        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (?, '默认知识库', '系统默认', 1, 1, 0)", SPACE_DEFAULT);
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (?, '协议库', '网络协议相关', 1, 1, 0)", SPACE_NETWORK);
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (1, ?, '操作系统基线.pdf', 'pdf', 'INDEXED', 1, 0)", SPACE_DEFAULT);
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (2, ?, '计算机网络.pdf', 'pdf', 'INDEXED', 1, 0)", SPACE_NETWORK);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (?, 1, 0, '进程调度里也用滑动窗口式的加权公平队列来平衡各进程的令牌数', 26, 1)", CHUNK_OS);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (?, 2, 0, 'TCP 使用滑动窗口进行流量控制，窗口大小由接收方通告', 24, 1)", CHUNK_NET);

        props.setApiKey("test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);

        // 所有切片与问题都给同一个向量：余弦相似度都是 1.0，两条都会被向量库端召回
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{1f, 0f, 0f, 0f})));
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream()
                    .map(s -> Embedding.from(new float[]{1f, 0f, 0f, 0f}))
                    .toList());
        });
        Mockito.when(chatModel.chat(Mockito.<List<dev.langchain4j.data.message.ChatMessage>>any()))
                .thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("基于召回片段生成的答案"))
                        .build());
    }

    @AfterEach
    void restore() {
        props.setEmbeddingEnabled(false);
        props.setMockEnabled(false);
        embeddingStore.removeAll();
    }

    @Test
    @DisplayName("不传 spaceId 时维持旧行为：两个空间的切片都能被词法路召回")
    void unscopedLexicalHitsBothSpaces() {
        List<KbChunk> hits = retrievalService.retrieve(QUESTION, 5, Route.LEXICAL);

        assertThat(ids(hits)).containsExactlyInAnyOrder(CHUNK_OS, CHUNK_NET);
    }

    @Test
    @DisplayName("传 spaceId 后词法路只召回该空间的切片：topK 截断发生在空间之内")
    void scopedLexicalOnlyHitsTargetSpace() {
        List<KbChunk> hits = retrievalService.retrieve(QUESTION, 5, Route.LEXICAL, SPACE_NETWORK);

        assertThat(ids(hits)).containsExactly(CHUNK_NET);
        assertThat(hits).allSatisfy(chunk -> assertThat(chunk.getDocumentId()).isEqualTo(2L));
    }

    @Test
    @DisplayName("语义路同样收敛：两条切片在向量库里相似度都是 1.0，但只有目标空间的那条能出来")
    void scopedSemanticRouteIsAlsoFiltered() {
        props.setEmbeddingEnabled(true);
        assertThat(vectorIndexService.indexChunks(chunkMapper.selectList(null))).isEqualTo(2);

        assertThat(ids(retrievalService.retrieve(QUESTION, 5, Route.SEMANTIC)))
                .containsExactlyInAnyOrder(CHUNK_OS, CHUNK_NET);
        assertThat(ids(retrievalService.retrieve(QUESTION, 5, Route.SEMANTIC, SPACE_NETWORK)))
                .containsExactly(CHUNK_NET);
        // 混合路走的是同一条回表过滤，融合前的候选也已经按空间放大后收敛
        assertThat(ids(retrievalService.retrieve(QUESTION, 5, Route.HYBRID, SPACE_NETWORK)))
                .containsExactly(CHUNK_NET);
    }

    @Test
    @DisplayName("非法空间入参不打断问答：不存在的空间返回空，0/负数按全库处理")
    void invalidSpaceIsHandledWithoutBreakingTheAsk() {
        assertThat(retrievalService.retrieve(QUESTION, 5, Route.HYBRID, 99L)).isEmpty();
        assertThat(ids(retrievalService.retrieve(QUESTION, 5, Route.LEXICAL, 0L)))
                .containsExactlyInAnyOrder(CHUNK_OS, CHUNK_NET);
        assertThat(ids(retrievalService.retrieve(QUESTION, 5, Route.LEXICAL, -1L)))
                .containsExactlyInAnyOrder(CHUNK_OS, CHUNK_NET);
    }

    @Test
    @DisplayName("POST /api/knowledge/retrieve-compare：带 spaceId 时三路命中全部属于该空间，引擎块写清检索范围")
    void compareApiScopesAllRoutesAndReportsIt() throws Exception {
        JsonNode data = postJson("/api/knowledge/retrieve-compare",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_NETWORK));

        assertThat(data.get("spaceId").asLong()).isEqualTo(SPACE_NETWORK);
        assertThat(data.get("engine").get("scopeStrategy").asText())
                .contains("协议库").contains("#" + SPACE_NETWORK);
        for (JsonNode route : data.get("routes")) {
            for (JsonNode item : route.get("items")) {
                assertThat(item.get("documentId").asLong()).isEqualTo(2L);
                assertThat(item.get("documentTitle").asText()).isEqualTo("计算机网络.pdf");
            }
        }
        // 不传 spaceId 的对照：范围说明必须写明全库，且能把另一个空间的切片也召回
        JsonNode global = postJson("/api/knowledge/retrieve-compare", Map.of("question", QUESTION, "topK", 5));
        assertThat(global.get("spaceId").isNull()).isTrue();
        assertThat(global.get("engine").get("scopeStrategy").asText()).contains("全库");
        assertThat(chunkIdsOf(global.get("routes").get(0).get("items"))).contains(CHUNK_OS);
    }

    @Test
    @DisplayName("POST /api/knowledge/ask：spaceId 让引用只可能来自目标空间")
    void askApiCitationsStayInsideTheSpace() throws Exception {
        JsonNode data = postJson("/api/knowledge/ask",
                Map.of("question", QUESTION, "topK", 5, "spaceId", SPACE_NETWORK));

        JsonNode citations = data.get("citations");
        assertThat(citations).isNotEmpty();
        for (JsonNode citation : citations) {
            assertThat(citation.get("documentId").asLong()).isEqualTo(2L);
            assertThat(citation.get("title").asText()).isEqualTo("计算机网络.pdf");
        }
    }

    private List<Long> ids(List<KbChunk> chunks) {
        return chunks.stream().map(KbChunk::getId).toList();
    }

    private List<Long> chunkIdsOf(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(node -> node.get("chunkId").asLong())
                .toList();
    }

    private JsonNode postJson(String url, Map<String, ?> payload) throws Exception {
        String body = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(body);
        assertThat(root.get("code").asInt()).isZero();
        return root.get("data");
    }
}
