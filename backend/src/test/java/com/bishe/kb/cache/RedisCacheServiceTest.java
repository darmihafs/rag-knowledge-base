package com.bishe.kb.cache;

import com.bishe.kb.config.KbCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 缓存 / 锁统一入口的单元测试：用 Mockito 打桩 {@link StringRedisTemplate}，
 * 不起 Spring、不连真实 Redis。重点验证「降级安全契约」——任何异常都返回降级结果而非外抛，
 * 以及分布式锁的三态语义与 Lua 安全释放。
 */
class RedisCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        KbCacheProperties props = new KbCacheProperties();
        props.setUnavailableBackoffMillis(60_000);
        service = new RedisCacheService(redis, props);
    }

    @Test
    @DisplayName("读命中返回值；写按 TTL 落库；删调用 Redis delete")
    void happyPath() {
        when(valueOps.get("k")).thenReturn("v");
        assertThat(service.get("k")).isEqualTo("v");

        service.set("k", "v", Duration.ofHours(2));
        verify(valueOps).set("k", "v", Duration.ofHours(2));

        service.delete("k");
        verify(redis).delete("k");
    }

    @Test
    @DisplayName("读异常降级返回 null，且背避窗口内不再重复连接 Redis")
    void getDegradesAndBacksOff() {
        when(valueOps.get("k")).thenThrow(new RuntimeException("connection refused"));
        assertThat(service.get("k")).isNull();
        // 第二次读处于背避窗口内：直接降级，不再触达 Redis
        assertThat(service.get("k")).isNull();
        verify(valueOps, times(1)).get("k");
    }

    @Test
    @DisplayName("写 / 删异常只降级，绝不把异常抛回主链路")
    void writeAndDeleteDegradeQuietly() {
        doThrow(new RuntimeException("down")).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        doThrow(new RuntimeException("down")).when(redis).delete(anyString());
        // 不抛异常即通过
        service.set("k", "v", Duration.ofHours(1));
        service.delete("k");
    }

    @Test
    @DisplayName("加锁三态：取得 / 被占用 / Redis 不可用")
    void tryLockTriState() {
        Duration ttl = Duration.ofSeconds(90);
        when(valueOps.setIfAbsent("lock", "owner", ttl)).thenReturn(true);
        assertThat(service.tryLock("lock", "owner", ttl)).isEqualTo(RedisCacheService.LockResult.ACQUIRED);

        when(valueOps.setIfAbsent("lock", "owner", ttl)).thenReturn(false);
        assertThat(service.tryLock("lock", "owner", ttl)).isEqualTo(RedisCacheService.LockResult.HELD);

        when(valueOps.setIfAbsent("lock", "owner", ttl)).thenThrow(new RuntimeException("down"));
        assertThat(service.tryLock("lock", "owner", ttl)).isEqualTo(RedisCacheService.LockResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("Lua 释放：owner 匹配返回 1 视为释放成功，不匹配返回 0 视为失败")
    @SuppressWarnings("unchecked")
    void unlockChecksOwner() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("owner"))).thenReturn(1L);
        assertThat(service.unlock("lock", "owner")).isTrue();

        when(redis.execute(any(RedisScript.class), anyList(), eq("owner"))).thenReturn(0L);
        assertThat(service.unlock("lock", "owner")).isFalse();
    }

    @Test
    @DisplayName("释放异常降级返回 false，不外抛")
    @SuppressWarnings("unchecked")
    void unlockDegrades() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("owner")))
                .thenThrow(new RuntimeException("down"));
        assertThat(service.unlock("lock", "owner")).isFalse();
    }

    @Test
    @DisplayName("自增计数：首次创建时设置过期，后续自增不重复设置；异常降级返回 null")
    void incrementSetsTtlOnFirstAndDegrades() {
        when(valueOps.increment("cnt")).thenReturn(1L);
        assertThat(service.increment("cnt", Duration.ofMinutes(15))).isEqualTo(1L);
        verify(redis).expire("cnt", Duration.ofMinutes(15));

        when(valueOps.increment("cnt")).thenReturn(2L);
        assertThat(service.increment("cnt", Duration.ofMinutes(15))).isEqualTo(2L);
        // 仅首次（返回 1）设置过期，第二次不再重复 expire
        verify(redis, times(1)).expire("cnt", Duration.ofMinutes(15));

        when(valueOps.increment("down")).thenThrow(new RuntimeException("down"));
        assertThat(service.increment("down", Duration.ofMinutes(15))).isNull();
    }

    @Test
    @DisplayName("背避窗口过后自动恢复尝试连接（注入可控时钟，确定性、不依赖真实时间）")
    void recoversAfterBackoff() {
        // 退避被 Math.max(1000, ...) 钳到最小 1000ms：旧写法用真实时间轮询、上限又≈1000ms，
        // 慢速 CI runner 上「恢复时刻≈轮询截止」边界赛跑会偶发读到 null。改注入可控时钟手动拨过窗口。
        AtomicLong clock = new AtomicLong(1_000_000L);
        KbCacheProperties props = new KbCacheProperties();
        props.setUnavailableBackoffMillis(1); // 生效值被钳到 1000ms
        RedisCacheService shortBackoff = new RedisCacheService(redis, props, clock::get);
        when(valueOps.get("k")).thenThrow(new RuntimeException("down")).thenReturn("recovered");

        // 首次读触发降级：背避截止 = clock + 1000
        assertThat(shortBackoff.get("k")).isNull();
        // 窗口内再读仍降级，且不再触达 Redis（只消费了第一次 thenThrow）
        assertThat(shortBackoff.get("k")).isNull();
        verify(valueOps, times(1)).get("k");

        // 把时钟拨过背避窗口（+1000 再 +1）：下一次读应真正触达 Redis 并拿到恢复值
        clock.addAndGet(1_001L);
        assertThat(shortBackoff.get("k")).isEqualTo("recovered");
        verify(valueOps, times(2)).get("k");
    }
}