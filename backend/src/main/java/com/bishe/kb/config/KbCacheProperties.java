package com.bishe.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分布式缓存与锁的可调参数，配置前缀 {@code kb.cache}。
 *
 * <p>这一层把「单机 JVM 本地状态」升级为「支持多实例部署的 Redis 组件」，但所有能力都带优雅降级：
 * {@link #enabled} 关闭（或 Redis 不可达）时，Query 向量退回 Caffeine 本地一级缓存、
 * 鉴权与空间集合退回每次查库、Agent 单用户并发退回进程内 {@code Set} 闸门——
 * 现有单测与 H2 集成测试因此无需启动真实 Redis 即可全绿。
 *
 * <p>Query 向量缓存的容量与过期另有 RAG 侧口径（{@code rag.embedding-query-cache-size} /
 * {@code rag.embedding-query-cache-expire-minutes} / {@code rag.embedding-l2-ttl-hours}），
 * 因为它们与向量模型强相关；这里只放跨模块通用的鉴权 / 锁 / 降级参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kb.cache")
public class KbCacheProperties {

    /**
     * Redis 能力总开关。{@code false} 时不装配 {@code RedisCacheService}，
     * 所有调用方经 {@code ObjectProvider} 拿到 null 并走本地降级路径。
     * 测试 profile 固定关闭；生产默认打开。
     */
    private boolean enabled = true;

    /** 鉴权缓存（token_version 与可见空间集合）的存活时间，默认 2 小时 */
    private long authTtlHours = 2;

    /**
     * Agent 单用户分布式锁的过期时间，默认 120 秒。
     * 刻意大于 {@code rag.agent-timeout-millis}（默认 90 秒）的任务硬预算并留出 30 秒余量：
     * 锁必须比任务活得久，否则任务还在跑、锁却先过期，别的实例会趁虚取得同一账号的锁，
     * 单用户防重入形同虚设。持有者崩溃 / 释放失败时，锁到点自动过期，不会把账号永久锁死。
     */
    private long agentLockTtlSeconds = 120;

    /**
     * 探测到 Redis 不可用后的降级背避窗口（毫秒）。窗口内的操作直接返回降级结果，
     * 不再发起注定失败的连接，避免每个请求都卡在 Lettuce 命令超时上。窗口过后自动重试。
     */
    private long unavailableBackoffMillis = 10_000;
}