package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.cache.CacheKeys;
import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.dto.RegisterRequest;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.service.SpaceAccessService;
import com.bishe.kb.service.SysUserService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户服务实现。
 *
 * <p>登录失败时统一返回「用户名或密码错误」，不区分是账号不存在还是密码不匹配，防止账号枚举。
 * 被禁用（status=0）的账号即使密码正确也拒绝登录。自助注册固定为 USER 角色，
 * 并自动获得默认知识库空间的成员资格（{@code SpaceAccessService#joinDefaultSpace}）——
 * 否则新账号一条授权都没有，问答会命中「可见空间为空 = 拒绝一切」，表现为问什么都没结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    /** 同一账号连续密码错误达此上限后，在窗口内直接拒绝登录（不再消耗 BCrypt 算力） */
    private static final int MAX_LOGIN_FAILURES = 10;
    /** 防爆破固定窗口：从首次失败起算 15 分钟，到点计数自动过期 */
    private static final Duration LOGIN_LOCK_WINDOW = Duration.ofMinutes(15);

    private final PasswordEncoder passwordEncoder;
    private final SpaceAccessService spaceAccessService;
    /** Redis 缓存可选注入：关闭时拿到 null，吊销只走数据库版本号自增（与升级前一致） */
    private final ObjectProvider<RedisCacheService> redisCacheProvider;

    /**
     * 登录失败计数的本地兜底（Redis 关闭或不可用时使用）。写后 15 分钟过期、容量上限防内存膨胀。
     * 带初始化器的 final 字段不会被 Lombok {@code @RequiredArgsConstructor} 纳入构造参数，
     * 因此装配签名与升级前完全一致，现有测试无需改动。
     */
    private final Cache<String, AtomicInteger> localLoginFailures = Caffeine.newBuilder()
            .expireAfterWrite(LOGIN_LOCK_WINDOW)
            .maximumSize(10_000)
            .build();

    @Override
    public SysUser findByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
    }

    @Override
    public SysUser authenticate(String username, String rawPassword) {
        String failureKey = StringUtils.lowerCase(StringUtils.trimToEmpty(username));
        // 先过防爆破闸门：连续失败达上限的账号在窗口内直接拒绝，连 BCrypt 都不再跑，
        // 既挡暴力穷举，也避免攻击者用「响应耗时差」枚举账号是否存在。
        if (loginFailureCount(failureKey) >= MAX_LOGIN_FAILURES) {
            throw new BizException(4290, "密码错误次数过多，账号已被临时锁定，请 15 分钟后再试");
        }
        SysUser user = findByUsername(username);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            recordLoginFailure(failureKey);
            throw new BizException(4001, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(4003, "账号已被禁用，请联系管理员");
        }
        // 登录成功清除计数：本人偶尔输错不该被之前的失败累计锁死
        clearLoginFailures(failureKey);
        return user;
    }

    /**
     * 读取连续失败计数：Redis 可用时以跨实例的全局计数为准，同时取本地兜底的较大值，
     * 保证 Redis 中途降级时本地已累计的失败不会凭空清零。两者都拿不到则视为 0。
     */
    private long loginFailureCount(String failureKey) {
        long redisCount = 0L;
        RedisCacheService redis = redisCacheProvider.getIfAvailable();
        if (redis != null) {
            String value = redis.get(CacheKeys.loginFailures(failureKey));
            if (value != null) {
                try {
                    redisCount = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // 计数被外部写脏：当作 0，不影响正常登录
                }
            }
        }
        AtomicInteger local = localLoginFailures.getIfPresent(failureKey);
        return Math.max(redisCount, local == null ? 0L : local.get());
    }

    /** 记一次失败：优先写 Redis（跨实例共享），Redis 关闭或降级时退回本地 Caffeine 计数 */
    private void recordLoginFailure(String failureKey) {
        RedisCacheService redis = redisCacheProvider.getIfAvailable();
        if (redis != null) {
            Long value = redis.increment(CacheKeys.loginFailures(failureKey), LOGIN_LOCK_WINDOW);
            if (value != null) {
                return;
            }
        }
        localLoginFailures.get(failureKey, key -> new AtomicInteger()).incrementAndGet();
    }

    /** 登录成功后清除计数：Redis 与本地兜底一起清，避免残留计数把本人下次登录误锁 */
    private void clearLoginFailures(String failureKey) {
        RedisCacheService redis = redisCacheProvider.getIfAvailable();
        if (redis != null) {
            redis.delete(CacheKeys.loginFailures(failureKey));
        }
        localLoginFailures.invalidate(failureKey);
    }

    /**
     * 注册与默认空间授权必须同进同退：成员行插入失败时账号不能留下，
     * 否则这个用户登录后「可见空间为空」，问什么都是空的，却没有任何失败提示。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUser register(RegisterRequest request) {
        String username = request.username().trim();
        if (findByUsername(username) != null) {
            throw new BizException(4002, "用户名已被占用");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.isBlank(request.nickname()) ? username : request.nickname().trim());
        user.setRole(SysUser.ROLE_USER);
        user.setStatus(SysUser.STATUS_ENABLED);
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(4002, "用户名已被占用（可能是历史已删除账号）");
        }
        // 新账号必须有一条成员行，否则「可见集合为空 = 拒绝一切」会把它关在库外：
        // 注册完问什么都没结果，看起来像检索坏了，其实是没人给他授权过
        spaceAccessService.joinDefaultSpace(user);
        log.info("新用户注册成功 id={} username={} role=USER", user.getId(), username);
        return user;
    }

    @Override
    public void invalidateTokens(Long userId) {
        if (userId == null) {
            return;
        }
        // 用 SQL 自增，不「读出来 +1 再写回」：两处并发时后者会覆盖前者，版本号就可能少加一次
        boolean applied = update(new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .setSql("token_version = token_version + 1"));
        // 主动淘汰令牌版本号缓存：下一次请求必然回源读到新版本号，旧 token 立即被拦截器短路拒绝。
        // 放在数据库自增之后，保证回源拿到的是递增后的值；Redis 不可用时 delete 内部静默降级，
        // 拦截器接受路径仍以数据库为准，不会因此放过已吊销的 token。
        RedisCacheService redis = redisCacheProvider.getIfAvailable();
        if (redis != null) {
            redis.delete(CacheKeys.tokenVersion(userId));
        }
        log.info("吊销既有登录令牌 userId={} 生效={}", userId, applied);
    }
}
