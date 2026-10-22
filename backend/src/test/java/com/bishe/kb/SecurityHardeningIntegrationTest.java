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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全加固回归测试（第十二阶段 P0）。
 *
 * <p>覆盖三类「功能没坏、但可被滥用」的洞：
 * <ol>
 *   <li><b>预览接口的脚本执行面</b>：上传白名单不再收 html；同源文件流一律带
 *       {@code X-Content-Type-Options: nosniff}；历史遗留的 HTML 原件改按纯文本回传，
 *       杜绝「传一个带 &lt;script&gt; 的页面、骗同学打开就偷走他的 JWT」。</li>
 *   <li><b>预览的数据可见性</b>：RBAC 豁免只回答「普通用户能不能调这个接口」，
 *       还差「能调哪一篇」。未进语料的草稿（PENDING / FAILED）正文对普通用户不可检索，
 *       也不能被按自增 id 枚举下载。</li>
 *   <li><b>登录态无法吊销</b>：JWT 无状态，改密码、被重置、退出之后旧 token 原本还能用到过期；
 *       现在由 {@code sys_user.token_version}（V7）在拦截器侧失配即 401。</li>
 * </ol>
 *
 * <p>全程关掉测试放行（{@code auth.test-bypass=false}）走真实 JWT 语义。
 */
@SpringBootTest(properties = "auth.test-bypass=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用高位固定 ID，避开同 JVM 内其它测试类的自增 ID */
    private static final long DOC_INDEXED_HTML = 9101L;
    private static final long DOC_DRAFT_TXT = 9102L;

    private static final String HTML_WITH_SCRIPT =
            "<!doctype html><html><body><script>fetch('/api/users')</script>遗留网页正文</body></html>";

    /** 断言用的响应头名：Spring 各小版本里 HttpHeaders 不一定有对应常量，用字面量最稳 */
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private static byte[] pdfBytes;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${rag.upload-dir}")
    private String uploadDir;

    private String adminToken;
    private String studentToken;

    @BeforeAll
    static void generatePdf() throws IOException {
        // 与 DocumentUploadIntegrationTest 同一套做法：现场造一个 PDFBox 认得的真 PDF
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(18f);
                content.newLineAtOffset(50, 780);
                for (int i = 1; i <= 12; i++) {
                    content.showText("RAGHARDEN line " + i);
                    content.newLine();
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdfBytes = out.toByteArray();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from sys_user where id > 2");
        adminToken = login("admin", "admin123");
        studentToken = login("student", "123456");

        // 两行手工种好的文档，文件真实落盘：分别用于「HTML 只读源码」与「草稿不可越权预览」
        writeUploadFile("legacy-9101.html", HTML_WITH_SCRIPT);
        insertDocument(DOC_INDEXED_HTML, "遗留网页", "html", "legacy-9101.html", "INDEXED");
        writeUploadFile("draft-9102.txt", "尚未复核的草稿正文，含内部结论");
        insertDocument(DOC_DRAFT_TXT, "未索引草稿", "txt", "draft-9102.txt", "PENDING");
    }

    // ------------------------------------------------------------------ 上传白名单

    @Test
    @DisplayName("上传 .html 被白名单拒绝：业务码 4000，且不留下文档行")
    void uploadHtmlShouldBeRejected() throws Exception {
        int before = countDocuments();

        JsonNode body = rawPerform(withToken(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "带脚本的页面.html", "text/html",
                        "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))), adminToken));

        assertThat(body.get("code").asInt()).isEqualTo(4000);
        assertThat(body.get("message").asText()).contains("不支持的文件类型");
        assertThat(countDocuments()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 预览不给浏览器可执行内容

    @Test
    @DisplayName("历史 HTML 文档按 text/plain 预览：源码可见，但浏览器不会执行其中的脚本")
    void legacyHtmlPreviewShouldBePlainText() throws Exception {
        MvcResult result = mockMvc.perform(admin(get("/api/documents/{id}/preview", DOC_INDEXED_HTML)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).contains("text/plain");
        assertThat(result.getResponse().getHeader(NOSNIFF_HEADER)).isEqualTo("nosniff");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("遗留网页正文");
    }

    @Test
    @DisplayName("PDF 预览照旧原样回传字节并补上 nosniff：加固没有伤到溯源主链路")
    void pdfPreviewStillStreamsOriginalBytes() throws Exception {
        long id = uploadPdf();

        MvcResult result = mockMvc.perform(admin(get("/api/documents/{id}/preview", id)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).contains("application/pdf");
        assertThat(result.getResponse().getHeader(NOSNIFF_HEADER)).isEqualTo("nosniff");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(pdfBytes);
    }

    // ------------------------------------------------------------------ 预览的数据可见性

    @Test
    @DisplayName("普通用户预览未完成索引的草稿：业务码 4030，拿不到原文")
    void normalUserCannotPreviewUnindexedDocument() throws Exception {
        JsonNode body = rawPerform(student(get("/api/documents/{id}/preview", DOC_DRAFT_TXT)));

        assertThat(body.get("code").asInt()).isEqualTo(4030);
        assertThat(body.get("message").asText()).contains("尚未完成索引");
    }

    @Test
    @DisplayName("同一篇草稿管理员可以预览：收紧只针对普通用户，不影响后台核对")
    void adminCanPreviewUnindexedDocument() throws Exception {
        MvcResult result = mockMvc.perform(admin(get("/api/documents/{id}/preview", DOC_DRAFT_TXT)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).contains("text/plain");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("尚未复核的草稿正文");
    }

    @Test
    @DisplayName("普通用户预览「已索引」文档仍然放行：原文溯源抽屉不会被这次加固打断")
    void normalUserCanPreviewIndexedDocument() throws Exception {
        mockMvc.perform(student(get("/api/documents/{id}/preview", DOC_INDEXED_HTML)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 令牌吊销

    @Test
    @DisplayName("改密码后手里的旧 token 立刻 401：版本号失配，不用等到自然过期")
    void changePasswordShouldRevokeOldToken() throws Exception {
        String token = register("revoke_pwd_01", "old-pass-1");
        okData(withToken(get("/api/auth/me"), token));

        okData(withToken(putJson("/api/auth/password",
                Map.of("oldPassword", "old-pass-1", "newPassword", "new-pass-2")), token));

        mockMvc.perform(withToken(get("/api/auth/me"), token))
                .andExpect(status().isUnauthorized());
        // 新密码可以重新登录：被吊销的只是旧令牌，不是把账号改坏
        assertThat(login("revoke_pwd_01", "new-pass-2")).isNotBlank();
    }

    @Test
    @DisplayName("退出登录在服务端真正作废令牌：只删前端本地副本不算登出")
    void logoutShouldRevokeTokenServerSide() throws Exception {
        String token = register("revoke_logout_01", "pass-1234");
        okData(withToken(post("/api/auth/logout"), token));

        mockMvc.perform(withToken(get("/api/auth/me"), token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("吊销只影响本人：某个账号退出登录，不会让管理员的令牌失效")
    void revocationShouldNotAffectOtherAccounts() throws Exception {
        String peer = register("revoke_peer_01", "pass-1234");
        okData(withToken(post("/api/auth/logout"), peer));

        mockMvc.perform(withToken(get("/api/auth/me"), peer))
                .andExpect(status().isUnauthorized());
        okData(withToken(get("/api/auth/me"), adminToken));
    }

    @Test
    @DisplayName("管理员重置他人密码同样吊销其令牌：被重置账号手里的 token 立即 401")
    void adminResetPasswordShouldRevokeTargetToken() throws Exception {
        String victim = register("revoke_reset_01", "pass-1234");
        long userId = jdbcTemplate.queryForObject(
                "select id from sys_user where username = 'revoke_reset_01'", Long.class);

        assertThat(okData(admin(put("/api/users/" + userId + "/reset-password"))).asText()).isEqualTo("123456");

        mockMvc.perform(withToken(get("/api/auth/me"), victim))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 走真实上传链路造一篇已索引的 PDF，返回文档 ID */
    private long uploadPdf() throws Exception {
        JsonNode body = rawPerform(withToken(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "加固校验.pdf", "application/pdf", pdfBytes)), adminToken));

        assertThat(body.get("code").asInt()).isZero();
        return body.get("data").asLong();
    }

    private int countDocuments() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from kb_document", Integer.class);
        return count == null ? 0 : count;
    }

    private void insertDocument(long id, String title, String fileType, String fileName, String status) {
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, file_path, file_size, "
                        + "status, chunk_count, deleted) values (?, 1, ?, ?, ?, ?, ?, 0, 0)",
                id, title, fileType, fileName, (long) fileName.length(), status);
    }

    private void writeUploadFile(String fileName, String content) throws IOException {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    private String login(String username, String password) throws Exception {
        JsonNode data = okData(postJson("/api/auth/login", Map.of("username", username, "password", password)));
        return data.get("token").asText();
    }

    /** 注册即登录，返回 token */
    private String register(String username, String password) throws Exception {
        JsonNode data = okData(postJson("/api/auth/register",
                Map.of("username", username, "password", password, "nickname", "加固测试账号")));
        return data.get("token").asText();
    }

    /** 发请求、断言 HTTP 200 且业务码 0，返回 data 节点 */
    private JsonNode okData(MockHttpServletRequestBuilder builder) throws Exception {
        JsonNode body = rawPerform(builder);
        assertThat(body.get("code").asInt()).as("接口应返回业务码 0，message=" + body.get("message").asText())
                .isZero();
        return body.get("data");
    }

    /** 只发请求、不看业务码：用于断言 4000 / 4030 这类预期内的业务错误 */
    private JsonNode rawPerform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withToken(builder, adminToken);
    }

    private MockHttpServletRequestBuilder student(MockHttpServletRequestBuilder builder) {
        return withToken(builder, studentToken);
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder putJson(String url, Object body) throws Exception {
        return put(url).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
    }
}
