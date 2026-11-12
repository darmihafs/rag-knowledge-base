package com.bishe.kb.cache;

import com.bishe.kb.config.KbCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * Redis 缓存 / 分布式锁的统一入口，把「连得上就用、连不上就降级」收口在一个地方。
 *
 * <p>装配条件 {@code kb.cache.enabled=true}（缺省即 true）。测试 profile 关闭后本 Bean 不存在，
 * 调用方通过 {@code ObjectProvider#getIfAvailable()} 拿到 null，各自走本地降级：
 * Query 向量只用 Caffeine 一级缓存、鉴权与空间集合每次查库、Agent 并发只用进程内闸门。
 * 因此现有单测与 H2 集成测试都不需要真实 Redis。
 *
 * <p><b>降级安全契约</b>：任何 Redis 操作抛出运行时异常（连接拒绝、命令超时、序列化失败）都只记一条
 * warn 并返回「降级结果」——读返回 null、写 / 删静默跳过、加锁返回 {@link LockResult#UNAVAILABLE}，
 * 绝不把异常抛回问答主链路或鉴权链路。配合 {@link KbCacheProperties#getUnavailableBackoffMillis()}
 * 的背避窗口，Redis 持续不可用时不会让每个请求都卡在 Lettuce 命令超时上。
 *
 * <p>分布式锁用 {@code SET key owner NX EX ttl} 原子加锁，释放走 Lua 脚本「先比对 owner 再删」，
 * 保证只释放自己持有的锁：任务 A 超时后锁已过期并被任务 B 重新取得时，A 的迟到释放不会误删 B 的锁。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kb.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheService {

    /** 加锁结果三态：拿到 / 被别人持有 / Redis 不可用（调用方据此决定拒绝还是退回本地闸门） */
    public enum LockResult {
        /** 成功取得锁 */
        ACQUIRED,
        /** 锁已被其他持有者占用，应拒绝本次请求 */
        HELD,
        /** Redis 不可用，调用方应退回本地降级路径而不是拒绝 */
        UNAVAILABLE
    }

    /** 释放锁的 Lua 脚本：owner 匹配才删，返回 1 表示真正释放了自己的锁 */
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;
    private final long backoffMillis;
    private final DefaultRedisScript<Long> releaseScript;

    /** 背避截止时间戳：在此之前所有操作直接降级，不再尝试连接 */
    private volatile long retryAfterEpochMillis = 0L;

    public RedisCacheService(StringRedisTemplate redis, KbCacheProperties props) {
        this.redis = redis;
        this.backoffMillis = Math.max(1_000L, props.getUnavailableBackoffMillis());
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    }

    /** 读字符串值；未命中或 Redis 不可用返回 null（调用方据此回源） */
    public String get(String key) {
        if (!available()) {
            return null;
        }
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            markUnavailable(e);
            return null;
        }
    }

    /** 写字符串值并设置过期；Redis 不可用时静默跳过（缓存写失败不影响主流程） */
    public void set(String key, String value, Duration ttl) {
        if (!available()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            markUnavailable(e);
        }
    }

    /** 删除单个 key（主动淘汰）；Redis 不可用时静默跳过 */
    public void delete(String key) {
        if (!available()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            markUnavailable(e);
        }
    }

    /**
     * 事务感知的主动淘汰：事务活跃时延迟到 {@code afterCommit} 再删，避免
     * 「删缓存 → 并发请求回源读到未提交的旧数据 → 事务提交」把陈旧值又写回缓存；
     * 无事务时立即删。授权 / 成员变更这类「必须立刻生效」的淘汰都走这里。
     */
    public void deleteAfterCommit(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(key);
                }
            });
        } else {
            delete(key);
        }
    }

    /**
     * 原子加锁：{@code SET key owner NX EX ttl}。
     *
     * @return {@link LockResult#ACQUIRED} 取得；{@link LockResult#HELD} 被占用应拒绝；
     *         {@link LockResult#UNAVAILABLE} Redis 不可用应退回本地闸门
     */
    public LockResult tryLock(String key, String owner, Duration ttl) {
        if (!available()) {
            return LockResult.UNAVAILABLE;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, owner, ttl);
            return Boolean.TRUE.equals(ok) ? LockResult.ACQUIRED : LockResult.HELD;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return LockResult.UNAVAILABLE;
        }
    }

    /**
     * 安全释放锁：Lua 脚本比对 owner 后才删，绝不会误删他人重新取得的锁。
     *
     * @return true 表示真正释放了自己持有的锁；false 表示锁已过期 / 易主或 Redis 不可用
     */
    public boolean unlock(String key, String owner) {
        if (!available()) {
            return false;
        }
        try {
            Long released = redis.execute(releaseScript, List.of(key), owner);
            return released != null && released == 1L;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    /**
     * 原子自增计数，并在首次创建时设置过期（{@code INCR} + 仅当返回 1 时 {@code EXPIRE}），
     * 用于登录防爆破这类「固定窗口计数」。窗口从首次失败起算，不随后续失败滑动。
     *
     * @return 自增后的计数；Redis 不可用时返回 null，调用方据此退回本地计数兜底
     */
    public Long increment(String key, Duration ttl) {
        if (!available()) {
            return null;
        }
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, ttl);
            }
            return value;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return null;
        }
    }

    private boolean available() {
        return System.currentTimeMillis() >= retryAfterEpochMillis;
    }

    private void markUnavailable(RuntimeException e) {
        retryAfterEpochMillis = System.currentTimeMillis() + backoffMillis;
        log.warn("Redis 不可用，{}ms 内降级为本地路径: {}", backoffMillis, e.getMessage());
    }
}