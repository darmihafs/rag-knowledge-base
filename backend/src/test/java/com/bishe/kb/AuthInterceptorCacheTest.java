package com.bishe.kb;

import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.config.AuthProperties;
import com.bishe.kb.config.KbCacheProperties;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.security.AuthInterceptor;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.SysUserService;
import com.bishe.kb.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 令牌版本号 Redis 缓存的单元测试：用 Mockito 打桩 {@link RedisCacheService}，不起 Spring、不连 Redis。
 *
 * <p>核心安全语义：缓存只做「快速拒绝」短路（token 版本 &lt; 缓存版本 ⇒ 必已吊销），
 * <b>接受路径永远回表复核</b>——启用状态 / 角色 / 昵称刻意不缓存，禁用与提权才能即时生效。
 * 缓存陈旧（比 token 版本还小）或损坏时不得误拒新 token，一律回源查库。
 */
class AuthInterceptorCacheTest {

    private static final String TOKEN = "test-token";
    private static final String VERSION_KEY = "auth:token_ver:1";

    private JwtUtil jwtUtil;
    private SysUserService userService;
    private RedisCacheService redis;
    private ObjectProvider<RedisCacheService> redisProvider;
    private ObjectMapper objectMapper;
    private AuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserContext.clear();
        jwtUtil = mock(JwtUtil.class);
        userService = mock(SysUserService.class);
        redis = mock(RedisCacheService.class);
        redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        objectMapper = new ObjectMapper();
        interceptor = new AuthInterceptor(jwtUtil, userService, new AuthProperties(),
                objectMapper, redisProvider, new KbCacheProperties());
        request = new MockHttpServletRequest("GET", "/api/knowledge/ask");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("缓存版本比 token 新：直接 4012 短路拒绝，不再查库（毫秒级吊销）")
    void revokedTokenShortCircuitsByCache() throws Exception {
        tokenWithVersion(1);
        when(redis.get(VERSION_KEY)).thenReturn("2");

        assertThat(preHandle()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(bodyCode()).isEqualTo(4012);
        verify(userService, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("缓存陈旧（比 token 版本小）：不误拒新 token，回表复核后放行并回填缓存")
    void staleCacheDoesNotFalselyRejectNewToken() throws Exception {
        tokenWithVersion(3);
        when(redis.get(VERSION_KEY)).thenReturn("2");
        when(userService.findByUsername("student")).thenReturn(dbUser(3));

        assertThat(preHandle()).isTrue();
        assertThat(UserContext.get().id()).isEqualTo(1L);
        verify(userService).findByUsername("student");
        verify(redis).set(eq(VERSION_KEY), eq("3"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("缓存未命中：以数据库为准放行，并把最新版本号回填缓存（TTL 2 小时）")
    void cacheMissAcceptsViaDbAndBackfills() throws Exception {
        tokenWithVersion(2);
        when(redis.get(VERSION_KEY)).thenReturn(null);
        when(userService.findByUsername("student")).thenReturn(dbUser(2));

        assertThat(preHandle()).isTrue();
        verify(redis).set(eq(VERSION_KEY), eq("2"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("缓存与 token 一致但库里版本已 +1：仍按库拒绝，且把新版本回填缓存")
    void dbVersionMismatchStillRejectedAndCacheRefreshed() throws Exception {
        tokenWithVersion(1);
        when(redis.get(VERSION_KEY)).thenReturn("1");
        when(userService.findByUsername("student")).thenReturn(dbUser(2));

        assertThat(preHandle()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(bodyCode()).isEqualTo(4012);
        // 即便本次被拒，也要把库里的新版本写回缓存，后续旧 token 才能从缓存短路
        verify(redis).set(eq(VERSION_KEY), eq("2"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("缓存值损坏（非数字）：当作未命中回源查库，不因缓存格式问题误拒")
    void corruptCacheValueFallsBackToDb() throws Exception {
        tokenWithVersion(2);
        when(redis.get(VERSION_KEY)).thenReturn("not-a-number");
        when(userService.findByUsername("student")).thenReturn(dbUser(2));

        assertThat(preHandle()).isTrue();
    }

    @Test
    @DisplayName("账号被禁用：即使版本缓存完全匹配也必须查库拒绝（启用状态不缓存）")
    void disabledAccountRejectedEvenWithMatchingCache() throws Exception {
        tokenWithVersion(2);
        when(redis.get(VERSION_KEY)).thenReturn("2");
        SysUser disabled = dbUser(2);
        disabled.setStatus(SysUser.STATUS_DISABLED);
        when(userService.findByUsername("student")).thenReturn(disabled);

        assertThat(preHandle()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(bodyCode()).isEqualTo(4010);
        verify(userService).findByUsername("student");
    }

    @Test
    @DisplayName("Redis 关闭（Bean 缺失）：完全回退到每次查库，与升级前行为一致")
    void noRedisDegradesToDbOnly() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        tokenWithVersion(2);
        when(userService.findByUsername("student")).thenReturn(dbUser(2));

        assertThat(preHandle()).isTrue();
        verifyNoInteractions(redis);
    }

    // ------------------------------------------------------------------ 工具

    private boolean preHandle() throws Exception {
        return interceptor.preHandle(request, response, new Object());
    }

    private void tokenWithVersion(int version) {
        when(jwtUtil.parse(TOKEN)).thenReturn(new LoginUser(1L, "student", "学生", "USER", version));
    }

    private static SysUser dbUser(int tokenVersion) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("student");
        user.setNickname("学生");
        user.setRole(SysUser.ROLE_USER);
        user.setStatus(SysUser.STATUS_ENABLED);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private int bodyCode() throws Exception {
        return objectMapper.readTree(response.getContentAsString()).get("code").asInt();
    }
}