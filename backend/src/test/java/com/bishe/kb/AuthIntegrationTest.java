package com.bishe.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
 * 认证 / RBAC / 会话隔离集成测试。
 *
 * <p>本类<b>关闭</b>测试放行（{@code auth.test-bypass=false}），以真实鉴权语义验证：
 * <ul>
 *   <li>登录成功返回 JWT + 用户信息；密码错误返回业务码 4001；</li>
 *   <li>无 token / 非法 token 访问受保护接口 -> HTTP 401；</li>
 *   <li>普通用户访问管理类接口（大盘 / 空间 / 文档 / 切片）-> HTTP 403；</li>
 *   <li>普通用户可正常访问智能问答与会话接口；</li>
 *   <li>会话按用户隔离：A 建的会话 B 看不到，也无法读取 / 删除。</li>
 * </ul>
 *
 * <p>其余旧集成测试仍依赖 {@code auth.test-bypass=true}（见 application-test.yml），
 * 用无 token 的管理员身份跑通既有链路，二者互不干扰。
 */
@SpringBootTest(properties = "auth.test-bypass=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个用例前清掉会话/消息与注册产生的临时用户，只保留 V4 种子的 admin(1)/student(2) */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from chat_message");
        jdbcTemplate.update("delete from chat_session");
        jdbcTemplate.update("delete from sys_user where id > 2");
    }

    // ------------------------------------------------------------------ 登录 / 注册 / me

    @Test
    @DisplayName("POST /api/auth/login：管理员登录成功，返回 token 与 role=ADMIN")
    void adminLoginShouldSucceed() throws Exception {
        JsonNode data = okData(postJson("/api/auth/login", Map.of("username", "admin", "password", "admin123")));

        assertThat(data.get("token").asText()).isNotBlank();
        assertThat(data.get("user").get("username").asText()).isEqualTo("admin");
        assertThat(data.get("user").get("role").asText()).isEqualTo("ADMIN");
        // 密码绝不能回传
        assertThat(data.get("user").has("password")).isFalse();
    }

    @Test
    @DisplayName("POST /api/auth/login：密码错误返回业务码 4001，不下发 token")
    void loginWithWrongPasswordShouldFail() throws Exception {
        JsonNode body = perform(postJson("/api/auth/login", Map.of("username", "admin", "password", "wrong-pass")));

        assertThat(body.get("code").asInt()).isEqualTo(4001);
        assertThat(body.get("message").asText()).contains("用户名或密码错误");
    }

    @Test
    @DisplayName("POST /api/auth/register：自助注册默认 USER，注册即登录返回 token")
    void registerShouldCreateNormalUser() throws Exception {
        JsonNode data = okData(postJson("/api/auth/register",
                Map.of("username", "newbie_01", "password", "secret123", "nickname", "新同学")));

        assertThat(data.get("token").asText()).isNotBlank();
        assertThat(data.get("user").get("role").asText()).isEqualTo("USER");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = 'newbie_01'", Integer.class)).isOne();
    }

    @Test
    @DisplayName("POST /api/auth/register：用户名重复返回业务码 4002")
    void registerDuplicateUsernameShouldFail() throws Exception {
        okData(postJson("/api/auth/register", Map.of("username", "dup_user", "password", "secret123")));

        JsonNode body = perform(postJson("/api/auth/register", Map.of("username", "dup_user", "password", "secret456")));
        assertThat(body.get("code").asInt()).isEqualTo(4002);
    }

    @Test
    @DisplayName("GET /api/auth/me：带合法 token 回显当前用户")
    void meShouldReturnCurrentUser() throws Exception {
        String token = login("student", "123456");

        JsonNode data = okData(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(data.get("username").asText()).isEqualTo("student");
        assertThat(data.get("role").asText()).isEqualTo("USER");
    }

    // ------------------------------------------------------------------ 401 未认证

    @Test
    @DisplayName("无 token 访问受保护接口：HTTP 401")
    void missingTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("非法 token 访问受保护接口：HTTP 401")
    void invalidTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("白名单接口无需登录：/api/health 匿名可访问")
    void healthShouldBePublic() throws Exception {
        okData(get("/api/health"));
    }

    // ------------------------------------------------------------------ 403 RBAC

    @Test
    @DisplayName("普通用户访问数据大屏：HTTP 403")
    void normalUserShouldNotAccessDashboard() throws Exception {
        String token = login("student", "123456");

        mockMvc.perform(get("/api/dashboard/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("普通用户访问知识库空间管理：HTTP 403")
    void normalUserShouldNotAccessSpaces() throws Exception {
        String token = login("student", "123456");

        mockMvc.perform(get("/api/spaces/all").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员访问数据大屏：放行，返回统计数据")
    void adminShouldAccessDashboard() throws Exception {
        String token = login("admin", "admin123");

        JsonNode data = okData(get("/api/dashboard/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        assertThat(data.has("spaceCount")).isTrue();
        assertThat(data.has("documentCount")).isTrue();
    }

    @Test
    @DisplayName("普通用户可正常访问会话接口（非管理类，放行）")
    void normalUserShouldAccessOwnSessions() throws Exception {
        String token = login("student", "123456");

        JsonNode data = okData(get("/api/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        assertThat(data.isArray()).isTrue();
    }

    // ------------------------------------------------------------------ 预览接口的 RBAC 豁免

    @Test
    @DisplayName("普通用户可调用文档原文预览：不被 RBAC 拦成 403（放行到业务层）")
    void normalUserCanPreviewDocument() throws Exception {
        String token = login("student", "123456");

        // 用一个不存在的文档 ID：若被 RBAC 拦截会得到 403 + code 4030；
        // 被豁免后应放行到控制器 -> 业务层报「文档不存在」code 4040，HTTP 200。
        JsonNode body = perform(get("/api/documents/999999/preview")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(body.get("code").asInt())
                .as("普通用户访问预览应放行到业务层(4040)，而非被 RBAC 拦成 4030")
                .isEqualTo(4040);
    }

    @Test
    @DisplayName("预览豁免不影响其余文档接口：普通用户访问文档列表仍 403")
    void normalUserStillForbiddenOnOtherDocumentEndpoints() throws Exception {
        String token = login("student", "123456");

        mockMvc.perform(get("/api/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("预览接口仍要求登录：无 token -> 401")
    void previewStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/documents/1/preview"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 会话隔离

    @Test
    @DisplayName("会话隔离：A 建的会话，B 列表里看不到，也读不到 / 删不掉")
    void sessionsShouldBeIsolatedPerUser() throws Exception {
        // 注册两个普通用户并各自登录
        String tokenA = register("stu_a", "secret123");
        String tokenB = register("stu_b", "secret123");

        // A 新建一个会话
        JsonNode sessionA = okData(post("/api/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"A 的私密会话\"}"));
        long sid = sessionA.get("id").asLong();

        // A 能看到自己的会话
        JsonNode listA = okData(get("/api/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA));
        assertThat(listA).hasSize(1);
        assertThat(listA.get(0).get("id").asLong()).isEqualTo(sid);

        // B 列表为空
        JsonNode listB = okData(get("/api/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB));
        assertThat(listB).isEmpty();

        // B 读取 A 的会话历史 -> 4040（按不存在处理，不泄露）
        JsonNode messages = perform(get("/api/sessions/" + sid + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB));
        assertThat(messages.get("code").asInt()).isEqualTo(4040);

        // B 删除 A 的会话 -> 4040，且会话未被删
        JsonNode del = perform(delete("/api/sessions/" + sid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB));
        assertThat(del.get("code").asInt()).isEqualTo(4040);
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from chat_session where id = ?", Integer.class, sid)).isZero();

        // A 自己可以删除
        okData(delete("/api/sessions/" + sid).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA));
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from chat_session where id = ?", Integer.class, sid)).isOne();
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 登录并返回 token */
    private String login(String username, String password) throws Exception {
        JsonNode data = okData(postJson("/api/auth/login", Map.of("username", username, "password", password)));
        return data.get("token").asText();
    }

    /** 注册并返回 token（注册即登录） */
    private String register(String username, String password) throws Exception {
        JsonNode data = okData(postJson("/api/auth/register", Map.of("username", username, "password", password)));
        return data.get("token").asText();
    }

    /** 发请求、断言 HTTP 200 且 code=0，返回 data 节点 */
    private JsonNode okData(RequestBuilder builder) throws Exception {
        JsonNode root = MAPPER.readTree(mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("接口返回码：%s", root).isZero();
        return root.get("data");
    }

    /** 发请求，返回完整响应体（用于断言业务码） */
    private JsonNode perform(RequestBuilder builder) throws Exception {
        return MAPPER.readTree(mockMvc.perform(builder)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private RequestBuilder postJson(String url, Object body) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body));
    }
}
