package com.bishe.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线解析主链路端到端测试（H2 内存库，不接触开发库 kb_rag）。
 *
 * <p>覆盖：上传落盘 -> PDFBox/Tika 抽取 -> 滑动窗口分块 -> kb_document/kb_chunk 落库 -> 分页查询 -> 删除清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = "RAGSMOKE";

    private static byte[] pdfBytes;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${rag.upload-dir}")
    private String uploadDir;

    @BeforeAll
    static void generatePdf() throws IOException {
        // 用 PDFBox 现场造一个真 PDF，验证 pdf 分支确实走 PDFBox 抽取
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                // 内置 Helvetica 只能编码 Latin-1，测试文本用英文，避免字体缺失导致的乱码
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(18f);
                content.newLineAtOffset(50, 780);
                for (int i = 1; i <= 30; i++) {
                    content.showText(MARKER + " line " + i + ": retrieval augmented generation smoke test paragraph.");
                    content.newLine();
                }
                content.endText();
            }
            Path tmp = Files.createTempFile("kb-smoke", ".pdf");
            document.save(tmp.toFile());
            pdfBytes = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("上传 txt：解析分块后落库，状态 INDEXED 且分段数与 kb_chunk 行数一致")
    void uploadTxtShouldParseChunkAndPersist() throws Exception {
        String text = buildChineseText(1200);
        MockMultipartFile file = new MockMultipartFile(
                "file", "测试文档.txt", "text/plain; charset=UTF-8", text.getBytes(StandardCharsets.UTF_8));

        JsonNode body = upload(file);

        assertThat(body.get("code").asInt()).isZero();
        long id = body.get("data").asLong();
        assertThat(id).isPositive();
        // 上传只负责落盘与入队，返回时任务可能刚排队、正在解析或已经完成
        assertThat(documentStatus(id)).isIn("PENDING", "PARSING", "INDEXED");
        awaitStatus(id, "INDEXED");

        // 逐列取值，避免 H2 与 MySQL 的列名大小写差异影响断言
        assertThat(jdbcTemplate.queryForObject("select title from kb_document where id = ?", String.class, id))
                .isEqualTo("测试文档");
        assertThat(jdbcTemplate.queryForObject("select file_type from kb_document where id = ?", String.class, id))
                .isEqualTo("txt");
        assertThat(jdbcTemplate.queryForObject("select status from kb_document where id = ?", String.class, id))
                .isEqualTo("INDEXED");
        int chunkCount = jdbcTemplate.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, id);
        assertThat(chunkCount).isGreaterThan(1);
        long fileSize = jdbcTemplate.queryForObject(
                "select file_size from kb_document where id = ?", Long.class, id);
        assertThat(fileSize).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);

        // 分段落库行数与主表冗余的 chunk_count 对得上
        Integer chunkRows = jdbcTemplate.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, id);
        assertThat(chunkRows).isEqualTo(chunkCount);

        // 落盘路径是相对 upload-dir 的，且文件真的存在
        String relativePath = jdbcTemplate.queryForObject(
                "select file_path from kb_document where id = ?", String.class, id);
        assertThat(relativePath).doesNotStartWith("\\").doesNotContain(":");
        Path stored = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relativePath);
        assertThat(Files.exists(stored)).isTrue();

        // 首段确实包含原文内容（抽取没有丢字）
        String firstChunk = jdbcTemplate.queryForObject(
                "select content from kb_chunk where document_id = ? and chunk_index = 0", String.class, id);
        assertThat(firstChunk).contains("第1段");
    }

    @Test
    @DisplayName("上传 pdf：走 PDFBox 抽取正文并完成分块")
    void uploadPdfShouldExtractViaPdfBox() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "smoke.pdf", "application/pdf", pdfBytes);

        long id = uploadAndAwait(file);

        assertThat(jdbcTemplate.queryForObject(
                "select status from kb_document where id = ?", String.class, id)).isEqualTo("INDEXED");
        Integer chunks = jdbcTemplate.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, id);
        assertThat(chunks).isGreaterThan(0);

        String all = jdbcTemplate.queryForObject(
                "select content from kb_chunk where document_id = ? and chunk_index = 0", String.class, id);
        assertThat(all).contains(MARKER);
    }

    @Test
    @DisplayName("上传多页 PDF：每个分段带 1 基页码，页码随分段序号非递减且覆盖各页")
    void multiPagePdfShouldTagPageNumbers() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cite.pdf", "application/pdf",
                buildMultiPagePdf(3));

        long id = uploadAndAwait(file);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select chunk_index, page_number, content from kb_chunk where document_id = ? order by chunk_index", id);
        assertThat(rows).isNotEmpty();

        // 所有分段都打了页码
        assertThat(rows).allSatisfy(r -> assertThat(r.get("page_number")).isNotNull());

        // 页码随 chunk_index 非递减（逐页切块的必然结果）
        List<Integer> pages = rows.stream().map(r -> (int) toLong(r.get("page_number"))).toList();
        for (int i = 1; i < pages.size(); i++) {
            assertThat(pages.get(i)).isGreaterThanOrEqualTo(pages.get(i - 1));
        }
        // 三页都被切到
        assertThat(pages).contains(1, 2, 3);

        // 第 2 页的分段正文确实只含第 2 页的标记，证明页码定位准确
        String page2 = rows.stream()
                .filter(r -> toLong(r.get("page_number")) == 2)
                .map(r -> (String) r.get("content"))
                .findFirst().orElseThrow();
        assertThat(page2).contains("PAGE2TEXT");
    }

    @Test
    @DisplayName("原文预览接口：200 原样回传 PDF 字节，Content-Type=application/pdf 且支持 Range")
    void previewShouldStreamPdfBytes() throws Exception {
        long id = uploadAndAwait(new MockMultipartFile("file", "preview.pdf", "application/pdf", pdfBytes));

        MvcResult result = mockMvc.perform(get("/api/documents/{id}/preview", id))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).contains("application/pdf");
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(pdfBytes);

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("原文预览接口 Range 请求：206 + Content-Range，仅返回所请求的字节区间")
    void previewShouldSupportRangeRequest() throws Exception {
        long id = uploadAndAwait(new MockMultipartFile("file", "range.pdf", "application/pdf", pdfBytes));

        MvcResult result = mockMvc.perform(get("/api/documents/{id}/preview", id)
                        .header(HttpHeaders.RANGE, "bytes=0-99"))
                .andExpect(status().isPartialContent()).andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_RANGE)).startsWith("bytes 0-99/");
        assertThat(result.getResponse().getContentAsByteArray()).hasSize(100);

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("预览不存在的文档：业务码 4040，不影响其它接口")
    void previewMissingDocumentShouldFail() throws Exception {
        JsonNode body = MAPPER.readTree(mockMvc.perform(get("/api/documents/{id}/preview", 987654L))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asInt()).isEqualTo(4040);
    }

    @Test
    @DisplayName("不支持的类型：返回业务错误码 4000，且不写库")
    void uploadUnsupportedTypeShouldFail() throws Exception {
        Integer before = jdbcTemplate.queryForObject("select count(*) from kb_document", Integer.class);

        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream",
                "MZ".getBytes(StandardCharsets.UTF_8));
        JsonNode body = upload(file);

        assertThat(body.get("code").asInt()).isEqualTo(4000);
        assertThat(body.get("message").asText()).contains("不支持的文件类型");
        assertThat(jdbcTemplate.queryForObject("select count(*) from kb_document", Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("空文件：返回 4000，不产生脏数据")
    void uploadEmptyFileShouldFail() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        JsonNode body = upload(file);

        assertThat(body.get("code").asInt()).isEqualTo(4000);
        assertThat(body.get("message").asText()).contains("上传文件为空");
    }

    @Test
    @DisplayName("分页查询与删除：删除后列表不再返回该文档，分段与本地文件一并清理")
    void pageAndDeleteShouldCleanUp() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "待删除文档.md", "text/markdown",
                buildChineseText(600).getBytes(StandardCharsets.UTF_8));
        long id = uploadAndAwait(file);

        MvcResult listResult = mockMvc.perform(get("/api/documents").param("pageNum", "1").param("pageSize", "50"))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = MAPPER.readTree(listResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.get("code").asInt()).isZero();
        boolean found = false;
        for (JsonNode record : list.get("data").get("records")) {
            if (record.get("id").asLong() == id) {
                found = true;
                assertThat(record.get("status").asText()).isEqualTo("INDEXED");
            }
        }
        assertThat(found).as("上传的文档应出现在分页列表中").isTrue();

        String relativePath = jdbcTemplate.queryForObject(
                "select file_path from kb_document where id = ?", String.class, id);
        Path stored = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relativePath);
        assertThat(Files.exists(stored)).isTrue();

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());

        // 逻辑删除：列表查不到，物理文件与分段都被清掉
        MvcResult afterDelete = mockMvc.perform(get("/api/documents").param("pageNum", "1").param("pageSize", "50"))
                .andExpect(status().isOk()).andReturn();
        JsonNode after = MAPPER.readTree(afterDelete.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode record : after.get("data").get("records")) {
            assertThat(record.get("id").asLong()).isNotEqualTo(id);
        }
        assertThat(Files.exists(stored)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("分段明细接口：按 chunkIndex 升序返回，条数与主表 chunk_count 一致")
    void listChunksShouldReturnOrderedSegments() throws Exception {
        long id = uploadAndAwait(new MockMultipartFile("file", "分段核对.md", "text/markdown",
                buildChineseText(700).getBytes(StandardCharsets.UTF_8)));

        MvcResult result = mockMvc.perform(get("/api/documents/{id}/chunks", id))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.get("code").asInt()).isZero();
        JsonNode chunks = body.get("data");
        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).get("chunkIndex").asInt()).isEqualTo(i);
        }
        // 中文原文没有被编码搞坏
        assertThat(chunks.get(0).get("content").asText()).contains("第1段");
        assertThat(chunks.size()).isEqualTo(jdbcTemplate.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, id));

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isOk());
    }

    private JsonNode upload(MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 上传并等待后台解析结束；异步链路下所有「已入库」断言都必须先等终态 */
    private long uploadAndAwait(MockMultipartFile file) throws Exception {
        JsonNode body = upload(file);
        assertThat(body.get("code").asInt()).isZero();
        long id = body.get("data").asLong();
        awaitStatus(id, "INDEXED");
        return id;
    }

    private String documentStatus(long id) {
        return jdbcTemplate.queryForObject("select status from kb_document where id = ?", String.class, id);
    }

    private void awaitStatus(long id, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = documentStatus(id);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(25L);
        }
        assertThat(status).as("文档 %s 应在 10 秒内进入 %s", id, expected).isEqualTo(expected);
    }

    /** 造一段有段落结构的中文文本，长度约 length 字符 */
    private String buildChineseText(int length) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        while (sb.length() < length) {
            sb.append("第").append(index).append("段：知识库问答系统需要先把文档切分成语义完整的片段，")
                    .append("再做向量化与检索，片段过长会稀释语义，过短会丢失上下文。\n\n");
            index++;
        }
        return sb.toString();
    }

    /**
     * 造一个多页 PDF，每页正文都以 {@code PAGE{p}TEXT} 开头。
     *
     * <p>每页文本量刻意超过 {@code chunkMaxChars}，保证一页至少切出 1 个分段，
     * 这样才能断言"分段页码随 chunk_index 非递减且每页都被覆盖"。
     */
    private byte[] buildMultiPagePdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int p = 1; p <= pageCount; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(16f);
                    content.newLineAtOffset(40, 780);
                    for (int i = 1; i <= 12; i++) {
                        content.showText("PAGE" + p + "TEXT line " + i
                                + ": retrieval augmented generation citation highlight demo.");
                        content.newLine();
                    }
                    content.endText();
                }
            }
            Path tmp = Files.createTempFile("kb-cite", ".pdf");
            try {
                document.save(tmp.toFile());
                return Files.readAllBytes(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** H2 / MySQL 对 INT 列返回的类型不一致（Integer / Long / BigDecimal），统一转 long 再断言 */
    private static long toLong(Object value) {
        assertThat(value).isNotNull();
        return ((Number) value).longValue();
    }
}
