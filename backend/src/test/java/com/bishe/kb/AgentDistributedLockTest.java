package com.bishe.kb;

import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.common.BizException;
import com.bishe.kb.config.KbCacheProperties;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.dto.AgentAskRequest;
import com.bishe.kb.model.vo.AgentAskResponse;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.SpaceAccessService;
import com.bishe.kb.service.impl.AgentKnowledgeTools;
import com.bishe.kb.service.impl.AgentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 单用户分布式锁的单元测试：用 Mockito 打桩 {@link RedisCacheService}，不起 Spring、不连 Redis。
 * 验证三态语义落到并发闸门上的行为——被占用（HELD）拒绝并回滚闸门、Redis 不可用（UNAVAILABLE）
 * 退回进程内单用户闸门、取得（ACQUIRED）任务结束后用 Lua 安全释放。全局 4 并发与异常释放由
 * {@link AgentConcurrencyTest} 覆盖，这里聚焦分布式锁这一层。
 */
class AgentDistributedLockTest {

    private static final AgentAskRequest REQUEST = new AgentAskRequest("检索测试", null, 1, null);

    private ChatModel model;
    private ThreadPoolTaskExecutor executor;
    private ThreadPoolTaskScheduler watchdog;
    private RedisCacheService redis;
    private AgentServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserContext.clear();
        model = mock(ChatModel.class);
        SpaceAccessService access = mock(SpaceAccessService.class);
        when(access.scopeForCurrentUser(null)).thenReturn(RetrievalScope.unrestricted());
        RagProperties props = new RagProperties();
        props.setApiKey("offline-test-placeholder");
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getAgentThreads());
        executor.setMaxPoolSize(props.getAgentThreads());
        executor.setQueueCapacity(props.getAgentQueueCapacity());
        executor.setThreadNamePrefix("test-agent-lock-");
        executor.initialize();
        watchdog = new ThreadPoolTaskScheduler();
        watchdog.setPoolSize(1);
        watchdog.setThreadNamePrefix("test-agent-lock-watch-");
        watchdog.initialize();
        redis = mock(RedisCacheService.class);
        ObjectProvider<RedisCacheService> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        service = new AgentServiceImpl(model, mock(AgentKnowledgeTools.class), access, props,
                new ObjectMapper(), executor, watchdog, redisProvider, new KbCacheProperties());
    }

    @AfterEach
    void cleanContext() {
        executor.shutdown();
        watchdog.shutdown();
        UserContext.clear();
    }

    private void answerModel() {
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("一段回答文本")).build());
    }

    @Test
    @DisplayName("锁被其他实例占用：拒绝本次请求并回滚闸门，锁释放后同账号可再次发起")
    void heldLockRejectsAndRollsBackGates() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.HELD);
        setUser(1);
        assertThatThrownBy(() -> service.ask(REQUEST)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getCode()).isEqualTo(4290));

        // 锁释放后（模拟另一实例任务结束）：同账号再次发起应成功，证明前两层闸门已回滚干净
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.ACQUIRED);
        answerModel();
        setUser(1);
        assertThat(service.ask(REQUEST).modelCalls()).isEqualTo(1);
        verify(redis).unlock(eq("agent:lock:1"), anyString());
    }

    @Test
    @DisplayName("Redis 不可用：退回进程内单用户闸门，同账号并发仍被拒绝")
    void unavailableLockFallsBackToLocalGuard() throws Exception {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.UNAVAILABLE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockingModel(entered);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AgentAskResponse> running = pool.submit(() -> asUser(1));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                setUser(1);
                // 分布式锁降级，但进程内 runningUsers 仍挡住同账号第二个任务
                assertThatThrownBy(() -> service.ask(REQUEST)).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(4290));
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).modelCalls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("取得锁的任务正常结束后，用任务 ID 作 owner 走 Lua 安全释放")
    void acquiredLockIsReleasedOnCompletion() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.ACQUIRED);
        answerModel();
        setUser(1);
        assertThat(service.ask(REQUEST).modelCalls()).isEqualTo(1);
        verify(redis).unlock(eq("agent:lock:1"), anyString());
    }

    @Test
    @DisplayName("模型异常后也释放分布式锁，下一次请求不会被卡死")
    void modelFailureReleasesLock() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.ACQUIRED);
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("offline failure"));
        setUser(1);
        for (int i = 0; i < 3; i++) {
            assertThat(service.ask(REQUEST).stopReason()).isEqualTo(AgentAskResponse.StopReason.MODEL_ERROR);
        }
        // 每次结束都释放锁（3 次任务各一次）
        verify(redis, org.mockito.Mockito.times(3)).unlock(eq("agent:lock:1"), anyString());
    }

    private CountDownLatch blockingModel(CountDownLatch entered) {
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试闸门未被释放");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("一段回答文本")).build();
        });
        return release;
    }

    private AgentAskResponse asUser(long id) {
        setUser(id);
        try {
            return service.ask(REQUEST);
        } finally {
            UserContext.clear();
        }
    }

    private static void setUser(long id) {
        UserContext.set(new LoginUser(id, "test" + id, "测试账号", "USER", 0));
    }
}