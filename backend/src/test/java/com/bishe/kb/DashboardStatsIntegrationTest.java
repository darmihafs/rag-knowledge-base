package com.bishe.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首页数据大盘统计测试：核心指标计数、今日提问数、近 7 日走势零填充、格式分布聚合。
 *
 * <p>跑在 H2 内存库，{@code @BeforeEach} 重建确定性种子数据，逐字段核对，不接触开发库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardStatsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        // 全清后重建确定性数据，保证计数字段可精确断言
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");

        // 2 个空间
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (1, '默认知识库', '系统默认', 0, 0, 0)");
        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (2, '网络工程库', '专业课', 0, 0, 0)");

        // 3 篇文档：pdf x2 + md x1，均归默认空间
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (1, 1, 'doc1', 'pdf', 'INDEXED', 2, 0)");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (2, 1, 'doc2', 'pdf', 'INDEXED', 2, 0)");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (3, 1, 'doc3', 'md', 'INDEXED', 1, 0)");
        // 一篇逻辑删除的文档，不应计入
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (4, 1, 'deleted-doc', 'docx', 'INDEXED', 9, 1)");

        // 5 个分段
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                    + "values (?, 1, ?, ?, 10, 1)", (long) i, i - 1, "分段内容 " + i);
        }

        // 今日 2 条提问 + 1 条回答（回答不计入提问数），create_time 走默认当前时间
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (1, 'user', '问题一')");
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (1, 'user', '问题二')");
        jdbcTemplate.update("insert into chat_message (session_id, role, content) values (1, 'assistant', '回答')");
    }

    @Test
    @DisplayName("GET /api/dashboard/stats：核心指标 + 走势 + 格式分布全部正确")
    void statsShouldAggregateAllMetrics() throws Exception {
        JsonNode data = MAPPER.readTree(mockMvc.perform(get("/api/dashboard/stats"))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");

        // 空间 2 个；文档只算未删除的 3 篇；分段 5 条
        assertThat(data.get("spaceCount").asLong()).isEqualTo(2L);
        assertThat(data.get("documentCount").asLong()).isEqualTo(3L);
        assertThat(data.get("chunkCount").asLong()).isEqualTo(5L);
        // 今日提问只数 user 消息，assistant 不算
        assertThat(data.get("todayQuestions").asLong()).isEqualTo(2L);

        // 近 7 日走势：7 个点，最后一天是今天且计数为 2，其余为 0
        JsonNode trend = data.get("trend");
        assertThat(trend).hasSize(7);
        String today = LocalDate.now().format(DATE_FORMAT);
        JsonNode last = trend.get(6);
        assertThat(last.get("date").asText()).isEqualTo(today);
        assertThat(last.get("count").asLong()).isEqualTo(2L);
        for (int i = 0; i < 6; i++) {
            assertThat(trend.get(i).get("count").asLong()).isZero();
        }

        // 格式分布：pdf=2、md=1，按数量降序，逻辑删除的 docx 不计入
        JsonNode formats = data.get("formats");
        assertThat(formats).hasSize(2);
        assertThat(formats.get(0).get("type").asText()).isEqualTo("pdf");
        assertThat(formats.get(0).get("count").asLong()).isEqualTo(2L);
        assertThat(formats.get(1).get("type").asText()).isEqualTo("md");
        assertThat(formats.get(1).get("count").asLong()).isEqualTo(1L);
    }
}
