package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.service.impl.VectorIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 混合双路召回测试：重点验证「字面无匹配、但语义相近」的同义词提问能被<b>向量路</b>成功召回。
 *
 * <p>用 Mockito 顶掉 {@link EmbeddingModel}：给目标分段和同义问题返回同一个向量（余弦相似度 1.0），
 * 给无关分段返回正交向量（相似度 0）。这样无需真实密钥即可确定性地验证语义召回是否生效。
 *
 * <p>对照实验：同一问题在 {@code embedding-enabled=false} 时，词法路因无 2-gram 重叠而召回为空，
 * 证明「命中确实来自向量路」而非碰巧的关键词匹配。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HybridRetrievalIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ASK_URL = "/api/knowledge/ask";

    /** 目标文档：讲流量控制，但通篇不出现同义问题里的任何 2-gram */
    private static final String TARGET_DOC = "网络协议精讲";
    /** 干扰文档：与问题完全无关 */
    private static final String OTHER_DOC = "数据库索引原理";
    /** 同义问题：语义上问的是「流量控制」，字面上和 TARGET 分段没有任何 2-gram 重叠 */
    private static final String SYNONYM_QUESTION = "如何防止发数据太快对方来不及处理";

    /** 目标向量与查询向量一致 -> 余弦 1.0；干扰向量正交 -> 余弦 0 */
    private static final float[] TARGET_VECTOR = {1f, 0f, 0f, 0f};
    private static final float[] OTHER_VECTOR = {0f, 1f, 0f, 0f};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

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

    @MockitoBean
    private StreamingChatModel streamingChatModel;

    @BeforeEach
    void seed() {
        // 清空向量库与业务表，保证每个用例从干净状态开始（向量库是进程内单例，必须显式清）
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");

        jdbcTemplate.update("insert into kb_document (id, title, file_type, status, chunk_count, deleted) "
                + "values (1, ?, 'txt', 'INDEXED', 1, 0)", TARGET_DOC);
        jdbcTemplate.update("insert into kb_document (id, title, file_type, status, chunk_count, deleted) "
                + "values (2, ?, 'txt', 'INDEXED', 1, 0)", OTHER_DOC);
        // 目标分段：滑动窗口/流量控制，措辞刻意避开问题里的字面词
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count) "
                + "values (100, 1, 0, ?, 30)", "滑动窗口机制通过限制未确认帧的数量来协调收发双方的速率。");
        // 干扰分段：数据库索引，与问题毫无关系
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count) "
                + "values (101, 2, 0, ?, 20)", "B+ 树索引适合范围查询与排序。");

        props.setApiKey("test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(true);

        // embedAll：按分段正文决定向量——含「滑动窗口」的目标分段给 TARGET_VECTOR，其余给正交向量
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = segments.stream()
                    .map(s -> s.text().contains("滑动窗口")
                            ? Embedding.from(TARGET_VECTOR)
                            : Embedding.from(OTHER_VECTOR))
                    .toList();
            return Response.from(embeddings);
        });
        // embed(String)：查询向量。同义问题给 TARGET_VECTOR，使其与目标分段余弦相似度 1.0
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenReturn(Response.from(Embedding.from(TARGET_VECTOR)));

        // 生成模型：召回结果已用引用断言验证，这里只需返回固定答案让同步接口跑通
        Mockito.when(chatModel.chat(Mockito.<List<dev.langchain4j.data.message.ChatMessage>>any()))
                .thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("这是模型基于召回片段生成的答案"))
                        .build());
    }

    @AfterEach
    void restore() {
        // 复位全局配置，避免污染其它测试类（props 是单例 Bean）
        props.setEmbeddingEnabled(false);
        props.setMockEnabled(false);
        props.setApiKey("test-key-not-real");
        embeddingStore.removeAll();
    }

    @Test
    @DisplayName("同义词提问（字面无匹配）能被向量路召回：引用命中语义相关的目标文档")
    void synonymQueryShouldBeRecalledByVectorPath() throws Exception {
        // 先按上传链路把分段向量化写入向量库
        int indexed = vectorIndexService.indexChunks(chunkMapper.selectList(null));
        assertThat(indexed).isEqualTo(2);

        MvcResult result = postJson(ASK_URL, SYNONYM_QUESTION);
        var data = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");

        var citations = data.get("citations");
        assertThat(citations.isArray()).isTrue();
        assertThat(citations).isNotEmpty();
        // RRF 融合后，余弦相似度 1.0 的目标分段排在首位
        assertThat(citations.get(0).get("title").asText()).isEqualTo(TARGET_DOC);
        assertThat(data.get("answer").asText()).isEqualTo("这是模型基于召回片段生成的答案");
    }

    @Test
    @DisplayName("对照：关闭向量化后，同一个同义问题词法路召回为空 —— 证明命中确实来自向量路")
    void sameQueryWithoutEmbeddingFindsNothing() throws Exception {
        // 不写入向量库，且关闭向量检索：只剩词法路，而问题与目标分段无 2-gram 重叠
        props.setEmbeddingEnabled(false);

        MvcResult result = postJson(ASK_URL, SYNONYM_QUESTION);
        var data = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");

        assertThat(data.get("citations")).isEmpty();
        assertThat(data.get("answer").asText()).contains("没有检索到");
    }

    private MvcResult postJson(String url, String question) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of("question", question))))
                .andExpect(status().isOk())
                .andReturn();
    }
}
