package com.bishe.kb;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.common.BizException;
import com.bishe.kb.config.KbCacheProperties;
import com.bishe.kb.mapper.KbSpaceMapper;
import com.bishe.kb.mapper.KbSpaceMemberMapper;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.entity.KbSpaceMember;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.impl.SpaceAccessServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 可见空间集合 Redis 缓存的单元测试：用 Mockito 打桩 {@link RedisCacheService} 与三张表的 Mapper，
 * 不起 Spring、不连 Redis、不建 H2。
 *
 * <p>验证三件事：读路径命中 {@code auth:spaces:{userId}} 后不再查成员表（空集合也缓存）；
 * 授权 / 撤销 / 加入默认空间都会淘汰受影响用户的缓存（越权短路即时生效）；
 * Redis 关闭或缓存损坏时回源查库，行为与升级前一致。
 */
class SpaceAccessCacheTest {

    private static final long USER_ID = 7L;
    private static final String SPACES_KEY = "auth:spaces:7";

    private KbSpaceMemberMapper memberMapper;
    private KbSpaceMapper spaceMapper;
    private SysUserMapper userMapper;
    private RedisCacheService redis;
    private ObjectProvider<RedisCacheService> redisProvider;
    private SpaceAccessServiceImpl service;

    /**
     * 纯单测不起 Spring，MyBatis-Plus 的 Lambda 列名缓存不会自动初始化，
     * 手动注册实体元数据，否则 LambdaQueryWrapper.select(...) 会抛
     * 「can not find lambda cache for this entity」。
     */
    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KbSpaceMember.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, KbSpace.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserContext.clear();
        memberMapper = mock(KbSpaceMemberMapper.class);
        spaceMapper = mock(KbSpaceMapper.class);
        userMapper = mock(SysUserMapper.class);
        redis = mock(RedisCacheService.class);
        redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        service = new SpaceAccessServiceImpl(memberMapper, spaceMapper, userMapper,
                redisProvider, new KbCacheProperties());
        UserContext.set(new LoginUser(USER_ID, "student", "学生", "USER", 0));
    }

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("缓存命中：直接返回可见集合，不再查成员表")
    void cacheHitSkipsMemberTable() {
        when(redis.get(SPACES_KEY)).thenReturn("1,2");

        assertThat(service.currentVisibleSpaceIds()).containsExactly(1L, 2L);
        verify(memberMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("缓存命中空串 = 已缓存的「无任何授权」：返回空列表且越权访问被 4030 短路")
    void cachedEmptySetMeansNoAuthorization() {
        when(redis.get(SPACES_KEY)).thenReturn("");

        assertThat(service.currentVisibleSpaceIds()).isEmpty();
        verify(memberMapper, never()).selectList(any());
        assertThatThrownBy(() -> service.requireReadable(1L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(4030));
    }

    @Test
    @DisplayName("缓存未命中：查成员表（去重）并回填逗号分隔 ID 串，TTL 2 小时")
    void cacheMissReadsDbAndWritesBack() {
        when(redis.get(SPACES_KEY)).thenReturn(null);
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L), member(2L)));

        assertThat(service.currentVisibleSpaceIds()).containsExactly(1L, 2L);
        verify(redis).set(eq(SPACES_KEY), eq("1,2"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("缓存值损坏：当作未命中回源查库重建，不因格式问题放行或拒绝")
    void corruptCacheRebuildsFromDb() {
        when(redis.get(SPACES_KEY)).thenReturn("1,abc");
        when(memberMapper.selectList(any())).thenReturn(List.of(member(3L)));

        assertThat(service.currentVisibleSpaceIds()).containsExactly(3L);
        verify(redis).set(eq(SPACES_KEY), eq("3"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("管理员绕过成员表也绕过缓存：返回 null（不限制）")
    void adminBypassesCacheAndMemberTable() {
        UserContext.set(new LoginUser(1L, "admin", "管理员", "ADMIN", 0));

        assertThat(service.currentVisibleSpaceIds()).isNull();
        verifyNoInteractions(redis);
        verify(memberMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("授权后淘汰每个受影响用户的可见集合缓存（事务提交后删除）")
    void grantEvictsEveryAffectedUser() {
        when(spaceMapper.selectById(5L)).thenReturn(space(5L));
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(memberMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.grant(5L, List.of(7L, 8L))).isEqualTo(2);
        verify(redis).deleteAfterCommit(SPACES_KEY);
        verify(redis).deleteAfterCommit("auth:spaces:8");
    }

    @Test
    @DisplayName("撤销后立刻淘汰缓存；撤销创建人被 4031 拒绝且不淘汰")
    void revokeEvictsAndProtectsCreator() {
        when(spaceMapper.selectById(5L)).thenReturn(space(5L));
        when(memberMapper.delete(any())).thenReturn(1);

        assertThat(service.revoke(5L, List.of(USER_ID))).isEqualTo(1);
        verify(redis).deleteAfterCommit(SPACES_KEY);

        assertThatThrownBy(() -> service.revoke(5L, List.of(1L)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(4031));
        verify(redis, never()).deleteAfterCommit("auth:spaces:1");
    }

    @Test
    @DisplayName("Redis 关闭（Bean 缺失）：每次查库，与升级前行为一致")
    void noRedisReadsDbEveryTime() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L)));

        assertThat(service.currentVisibleSpaceIds()).containsExactly(1L);
        assertThat(service.currentVisibleSpaceIds()).containsExactly(1L);
        verify(memberMapper, times(2)).selectList(any());
        verifyNoInteractions(redis);
    }

    // ------------------------------------------------------------------ 工具

    private static KbSpaceMember member(long spaceId) {
        KbSpaceMember member = new KbSpaceMember();
        member.setSpaceId(spaceId);
        member.setUserId(USER_ID);
        return member;
    }

    /** 创建人固定为 admin(id=1)，与 V8 种子数据口径一致 */
    private static KbSpace space(long id) {
        KbSpace space = new KbSpace();
        space.setId(id);
        space.setName("测试空间");
        space.setCreatedBy(1L);
        return space;
    }
}