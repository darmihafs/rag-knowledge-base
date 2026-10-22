package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
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
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分段人工干预测试：分页查看、编辑正文、启用/禁用，并验证「被禁用的分段在检索时不再召回」。
 *
 * <p>召回验证走词法路（{@code embedding-enabled=false}）：无需真实密钥，
 * 用 {@code LIKE '%量子退相干%'} 精确命中目标分段；禁用后词法路按 {@code enabled=1} 过滤，命中消失。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkInterventionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ASK_URL = "/api/knowledge/ask";
    private static final long DOC_ID = 1L;
    private static final long CHUNK_ID = 100L;
    private static final String TARGET_CONTENT = "量子退相干是量子计算中保持叠加态的主要障碍。";
    private static final String TARGET_QUESTION = "量子退相干";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, 1, '量子计算讲义', 'txt', 'INDEXED', 1, 0)", DOC_ID);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (?, ?, 0, ?, ?, 1)", CHUNK_ID, DOC_ID, TARGET_CONTENT, TARGET_CONTENT.length());

        props.setApiKey("test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);
        Mockito.when(chatModel.chat(Mockito.<List<dev.langchain4j.data.message.ChatMessage>>any()))
                .thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("模型答案"))
                        .build());
    }

    @AfterEach
    void restore() {
        props.setEmbeddingEnabled(false);
        props.setMockEnabled(false);
        props.setApiKey("");
    }

    @Test
    @DisplayName("GET /api/documents/{id}/chunks/page：分页返回分段，含 enabled 字段，按 chunkIndex 升序")
    void pageChunksShouldReturnEnabledField() throws Exception {
        JsonNode page = okData(get("/api/documents/{id}/chunks/page", DOC_ID)
                .param("pageNum", "1").param("pageSize", "10"));
        assertThat(page.get("total").asLong()).isEqualTo(1L);
        JsonNode first = page.get("records").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(CHUNK_ID);
        assertThat(first.get("chunkIndex").asInt()).isZero();
        assertThat(first.get("enabled").asInt()).isEqualTo(1);
        assertThat(first.get("content").asText()).contains("量子退相干");
    }

    @Test
    @DisplayName("PUT /api/chunks/{id}：编辑正文后落库，char_count 同步刷新")
    void updateContentShouldPersist() throws Exception {
        String newContent = "人工纠偏后的分段正文：退相干由环境耦合引起。";
        okData(putJson("/api/chunks/" + CHUNK_ID, Map.of("content", newContent)));

        assertThat(jdbcTemplate.queryForObject(
                "select content from kb_chunk where id = ?", String.class, CHUNK_ID)).isEqualTo(newContent);
        assertThat(jdbcTemplate.queryForObject(
                "select char_count from kb_chunk where id = ?", Integer.class, CHUNK_ID))
                .isEqualTo(newContent.length());
    }

    @Test
    @DisplayName("PUT /api/chunks/{id}：空正文被参数校验拦下，返回 4001")
    void updateContentShouldRejectBlank() throws Exception {
        JsonNode body = MAPPER.readTree(mockMvc.perform(putJson("/api/chunks/" + CHUNK_ID, Map.of("content", "  ")))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asInt()).isEqualTo(4001);
    }

    @Test
    @DisplayName("PUT /api/chunks/{id}/toggle-status：启用<->禁用来回翻转，返回切换后的状态")
    void toggleStatusShouldFlip() throws Exception {
        assertThat(okData(put("/api/chunks/{id}/toggle-status", CHUNK_ID)).asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from kb_chunk where id = ?", Integer.class, CHUNK_ID)).isZero();

        assertThat(okData(put("/api/chunks/{id}/toggle-status", CHUNK_ID)).asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select enabled from kb_chunk where id = ?", Integer.class, CHUNK_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("禁用分段后检索不再召回：启用时命中目标文档，禁用后 citations 为空")
    void disabledChunkShouldNotBeRecalled() throws Exception {
        // 启用状态：词法路 LIKE 命中，引用非空
        JsonNode enabledData = okData(postJson(ASK_URL, Map.of("question", TARGET_QUESTION)));
        assertThat(enabledData.get("citations")).isNotEmpty();
        assertThat(enabledData.get("citations").get(0).get("title").asText()).isEqualTo("量子计算讲义");

        // 人工禁用该分段
        okData(put("/api/chunks/{id}/toggle-status", CHUNK_ID));

        // 再次提问：词法路按 enabled=1 过滤，召回为空，回答走无命中提示
        JsonNode disabledData = okData(postJson(ASK_URL, Map.of("question", TARGET_QUESTION)));
        assertThat(disabledData.get("citations")).isEmpty();
        assertThat(disabledData.get("answer").asText()).contains("没有检索到");
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode okData(RequestBuilder builder) throws Exception {
        JsonNode root = MAPPER.readTree(mockMvc.perform(builder)
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("接口返回码：%s", root).isZero();
        return root.get("data");
    }

    private RequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }

    private RequestBuilder putJson(String url, Object body) throws Exception {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }
}
