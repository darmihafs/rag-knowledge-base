package com.bishe.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录防爆破护栏回归：同一账号连续密码错误达 10 次后，在 15 分钟窗口内拒绝登录并返回 4290；
 * 登录成功则清除计数，本人偶尔输错不会被累计锁死。
 *
 * <p>测试 profile 固定 {@code kb.cache.enabled=false}，不装配 {@code RedisCacheService}，
 * 因此本类验证的正是「Redis 关闭 / 不可用时退回本地 Caffeine 计数」这条降级路径——
 * 无需真实 Redis 即可全绿。独立 H2 库与上下文，失败计数不与其它测试类相互污染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kb_login_guard;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginBruteForceGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("连续 10 次密码错误：前 10 次 4001，第 11 次起窗口内拒绝登录返回 4290（正确密码也拒）")
    void tenConsecutiveFailuresThenLockout() throws Exception {
        register("guard_lock_01", "correct-pass-1");
        for (int i = 0; i < 10; i++) {
            assertThat(loginCode("guard_lock_01", "wrong-" + i))
                    .as("第 %d 次错误密码应仍是 4001", i + 1).isEqualTo(4001);
        }
        // 计数已达上限：即便这次密码正确，也在窗口内被锁定拒绝
        assertThat(loginCode("guard_lock_01", "correct-pass-1")).isEqualTo(4290);
        assertThat(loginCode("guard_lock_01", "wrong-again")).isEqualTo(4290);
    }

    @Test
    @DisplayName("登录成功清除失败计数：9 次错误后正确登录成功，再错 1 次只应是 4001 而非 4290")
    void successfulLoginClearsFailureCounter() throws Exception {
        register("guard_clear_01", "correct-pass-2");
        for (int i = 0; i < 9; i++) {
            assertThat(loginCode("guard_clear_01", "wrong-" + i)).isEqualTo(4001);
        }
        // 第 10 次用正确密码登录：此时计数 9 < 10 未触发锁定，登录成功并清除计数
        assertThat(loginCode("guard_clear_01", "correct-pass-2")).isZero();
        // 清除后再错 1 次只应是 4001（计数从 0 重新累计），证明成功登录把之前的 9 次清零了
        assertThat(loginCode("guard_clear_01", "wrong-after-clear")).isEqualTo(4001);
        assertThat(loginCode("guard_clear_01", "correct-pass-2")).isZero();
    }

    @Test
    @DisplayName("防爆破按账号隔离：一个账号被锁定不影响另一个账号正常登录")
    void lockoutIsPerAccount() throws Exception {
        register("guard_iso_a", "correct-pass-a");
        register("guard_iso_b", "correct-pass-b");
        for (int i = 0; i < 10; i++) {
            loginCode("guard_iso_a", "wrong-" + i);
        }
        assertThat(loginCode("guard_iso_a", "correct-pass-a")).isEqualTo(4290);
        // 另一个账号从未失败，正常登录不受影响
        assertThat(loginCode("guard_iso_b", "correct-pass-b")).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    private void register(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("username", username, "password", password, "nickname", "防爆破测试"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).path("code").asInt()).as("注册响应：%s", body).isZero();
    }

    /** 只取业务码：登录成功为 0，密码错误 4001，触发防爆破锁定 4290 */
    private int loginCode(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).path("code").asInt();
    }
}