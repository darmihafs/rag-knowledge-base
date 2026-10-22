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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多知识库空间管理测试：新建 / 编辑 / 分页 / 删除，文档归属空间与计数重算，删除保护。
 *
 * <p>跑在 H2 内存库；默认空间 id=1 由 schema-h2.sql 在建库时 seed，本类不清理它，只增删自建空间。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaceManagementIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // 只清理非默认空间与其名下文档/分段，保留 id=1 默认空间供其它测试类使用
        jdbcTemplate.update("delete from kb_chunk where document_id in (select id from kb_document where space_id <> 1)");
        jdbcTemplate.update("delete from kb_document where space_id <> 1");
        jdbcTemplate.update("delete from kb_space where id <> 1");
    }

    @Test
    @DisplayName("POST /api/spaces：新建空间后可在 all 列表查到，默认计数为 0")
    void createSpaceShouldPersist() throws Exception {
        Long id = okData(postJson("/api/spaces", Map.of("name", "网络工程库", "description", "期末复习资料"))).asLong();
        assertThat(id).isPositive();

        JsonNode all = okData(get("/api/spaces/all"));
        JsonNode created = findById(all, id);
        assertThat(created).isNotNull();
        assertThat(created.get("name").asText()).isEqualTo("网络工程库");
        assertThat(created.get("description").asText()).isEqualTo("期末复习资料");
        assertThat(created.get("docCount").asInt()).isZero();
        assertThat(created.get("chunkCount").asInt()).isZero();
    }

    @Test
    @DisplayName("POST /api/spaces：名称为空被参数校验拦下，返回 4001")
    void createSpaceShouldRejectBlankName() throws Exception {
        JsonNode body = MAPPER.readTree(mockMvc.perform(postJson("/api/spaces", Map.of("name", "  ")))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asInt()).isEqualTo(4001);
    }

    @Test
    @DisplayName("PUT /api/spaces/{id}：编辑名称与描述生效")
    void updateSpaceShouldApply() throws Exception {
        long id = okData(postJson("/api/spaces", Map.of("name", "旧名字"))).asLong();

        okData(putJson("/api/spaces/" + id, Map.of("name", "新名字", "description", "改过的描述")));

        JsonNode space = findById(okData(get("/api/spaces/all")), id);
        assertThat(space.get("name").asText()).isEqualTo("新名字");
        assertThat(space.get("description").asText()).isEqualTo("改过的描述");
    }

    @Test
    @DisplayName("上传文档并指定 spaceId：文档归属该空间，recount 后 doc_count/chunk_count 同步刷新")
    void uploadShouldAttributeToSpaceAndRecount() throws Exception {
        long spaceId = okData(postJson("/api/spaces", Map.of("name", "上传目标库"))).asLong();

        MockMultipartFile file = new MockMultipartFile("file", "空间文档.md", "text/markdown",
                buildText(700).getBytes(StandardCharsets.UTF_8));
        long docId = okData(multipart("/api/documents/upload").file(file).param("spaceId", String.valueOf(spaceId)))
                .asLong();
        // 后台解析完成后 chunk_count 与空间计数才稳定，先等终态再比较
        awaitIndexed(docId);

        // 文档确实落在指定空间
        assertThat(jdbcTemplate.queryForObject(
                "select space_id from kb_document where id = ?", Long.class, docId)).isEqualTo(spaceId);

        // 空间计数被重算：文档数=1，分段数=该文档 chunk_count
        JsonNode space = findById(okData(get("/api/spaces/all")), spaceId);
        int chunkCount = jdbcTemplate.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, docId);
        assertThat(space.get("docCount").asInt()).isEqualTo(1);
        assertThat(space.get("chunkCount").asInt()).isEqualTo(chunkCount);

        // 按 spaceId 过滤文档列表能查到，换个空间查不到
        assertThat(countRecords(okData(get("/api/documents")
                .param("pageNum", "1").param("pageSize", "50").param("spaceId", String.valueOf(spaceId))))).isEqualTo(1);
        assertThat(countRecords(okData(get("/api/documents")
                .param("pageNum", "1").param("pageSize", "50").param("spaceId", "999999")))).isZero();

        // 删除文档后计数回落到 0
        okData(delete("/api/documents/{id}", docId));
        JsonNode afterDelete = findById(okData(get("/api/spaces/all")), spaceId);
        assertThat(afterDelete.get("docCount").asInt()).isZero();
        assertThat(afterDelete.get("chunkCount").asInt()).isZero();
    }

    @Test
    @DisplayName("DELETE /api/spaces/{id}：空空间可删；仍有文档的空间被拒绝；默认空间不可删")
    void deleteSpaceGuards() throws Exception {
        long emptyId = okData(postJson("/api/spaces", Map.of("name", "空库"))).asLong();
        okData(delete("/api/spaces/{id}", emptyId));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from kb_space where id = ? and deleted = 0", Integer.class, emptyId)).isZero();

        // 有文档的空间：拒绝删除，返回业务错误码
        long busyId = okData(postJson("/api/spaces", Map.of("name", "占用库"))).asLong();
        MockMultipartFile file = new MockMultipartFile("file", "占位.md", "text/markdown",
                buildText(400).getBytes(StandardCharsets.UTF_8));
        okData(multipart("/api/documents/upload").file(file).param("spaceId", String.valueOf(busyId)));
        JsonNode busyResp = MAPPER.readTree(mockMvc.perform(delete("/api/spaces/{id}", busyId))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(busyResp.get("code").asInt()).isNotZero();
        assertThat(busyResp.get("message").asText()).contains("仍有");

        // 默认空间 id=1 永不可删
        JsonNode defaultResp = MAPPER.readTree(mockMvc.perform(delete("/api/spaces/1"))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(defaultResp.get("code").asInt()).isNotZero();
        assertThat(defaultResp.get("message").asText()).contains("默认知识库");
    }

    @Test
    @DisplayName("GET /api/spaces：分页返回，records/total 结构正确")
    void pageSpacesShouldReturnPaged() throws Exception {
        okData(postJson("/api/spaces", Map.of("name", "分页库A")));
        okData(postJson("/api/spaces", Map.of("name", "分页库B")));

        JsonNode page = okData(get("/api/spaces").param("pageNum", "1").param("pageSize", "10"));
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(3L); // 含默认空间
        assertThat(page.get("records").isArray()).isTrue();
        assertThat(page.get("records").size()).isGreaterThanOrEqualTo(3);
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

    private JsonNode findById(JsonNode array, long id) {
        for (JsonNode node : array) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    private int countRecords(JsonNode page) {
        return page.get("records").size();
    }

    private void awaitIndexed(long documentId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbcTemplate.queryForObject(
                    "select status from kb_document where id = ?", String.class, documentId);
            if ("INDEXED".equals(status)) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(status).as("文档 %s 应在 10 秒内完成索引", documentId).isEqualTo("INDEXED");
    }

    private String buildText(int length) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        while (sb.length() < length) {
            sb.append("第").append(index).append("段：多知识库协同管理需要把文档归属到具体空间下，")
                    .append("并在增删后重算空间的冗余统计，保证大盘数据与明细一致。\n\n");
            index++;
        }
        return sb.toString();
    }
}
