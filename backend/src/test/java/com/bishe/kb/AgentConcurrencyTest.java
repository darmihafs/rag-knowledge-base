package com.bishe.kb;

import com.bishe.kb.common.BizException;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 并发闸门测试，不起 Spring、不访问数据库和模型网络。 */
class AgentConcurrencyTest {
    private static final AgentAskRequest REQUEST = new AgentAskRequest("检索测试", null, 1, null);
    private ChatModel model;
    private ThreadPoolTaskExecutor executor;
    private ThreadPoolTaskScheduler watchdog;
    private AgentServiceImpl service;

    @BeforeEach
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
        executor.setThreadNamePrefix("test-agent-");
        executor.initialize();
        watchdog = new ThreadPoolTaskScheduler();
        watchdog.setPoolSize(1);
        watchdog.setThreadNamePrefix("test-agent-watch-");
        watchdog.initialize();
        service = new AgentServiceImpl(model, mock(AgentKnowledgeTools.class), access, props,
                new ObjectMapper(), executor, watchdog);
    }

    @AfterEach
    void cleanContext() {
        executor.shutdown();
        watchdog.shutdown();
        UserContext.clear();
    }

    @Test
    @DisplayName("同账号并发任务立即拒绝，先前任务结束后可以再次请求")
    void duplicateUserIsRejectedAndReleased() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockingModel(entered);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AgentAskResponse> running = pool.submit(() -> asUser(1));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                setUser(1);
                assertThatThrownBy(() -> service.ask(REQUEST)).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(4290));
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).modelCalls()).isEqualTo(1);
            assertThat(service.ask(REQUEST).modelCalls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("全局最多四个并发任务，第五个立即拒绝且不遗留账号锁")
    void globalCapacityRejectsWithoutLeakingUserLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = blockingModel(entered);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<AgentAskResponse>> running = new ArrayList<>();
            for (long id = 1; id <= 4; id++) {
                long userId = id;
                running.add(pool.submit(() -> asUser(userId)));
            }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                setUser(99);
                assertThatThrownBy(() -> service.ask(REQUEST)).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(4290));
            } finally {
                release.countDown();
            }
            for (Future<AgentAskResponse> result : running) {
                assertThat(result.get(5, TimeUnit.SECONDS).modelCalls()).isEqualTo(1);
            }
            assertThat(service.ask(REQUEST).modelCalls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("模型异常后也释放所有闸门，下一次请求不会被卡死")
    void modelFailureReleasesAdmission() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("offline failure"));
        setUser(1);
        for (int i = 0; i < 6; i++) {
            assertThat(service.ask(REQUEST).stopReason()).isEqualTo(AgentAskResponse.StopReason.MODEL_ERROR);
        }
    }

    @Test
    @DisplayName("服务被直接调用但无登录上下文时拒绝，不采用内部检索的无限制语义")
    void missingContextIsDenied() {
        assertThatThrownBy(() -> service.ask(REQUEST)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getCode()).isEqualTo(4010));
    }

    private CountDownLatch blockingModel(CountDownLatch entered) {
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试闸门未被释放");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("未查证的答案不会对外返回")).build();
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
