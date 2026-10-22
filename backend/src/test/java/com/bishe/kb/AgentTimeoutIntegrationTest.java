package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 硬超时回归：模型调用卡住时，同步接口必须在预算到点后返回 TIME_LIMIT，
 * 而不是等供应商标配的 60 秒甚至无限期挂住。
 *
 * <p>单独一个 H2 库与独立上下文，把 {@code rag.agent-timeout-millis} 调到 300ms；
 * 其余 Agent 测试仍使用默认 90 秒，不被拖慢。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kb_agent_timeout;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "rag.agent-timeout-millis=300"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentTimeoutIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private RagProperties props;

    @MockitoBean
    private ChatModel chatModel;
    @MockitoBean
    private StreamingChatModel streamingChatModel;
    @MockitoBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        props.setApiKey("timeout-test-key-not-real");
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);
    }

    @AfterEach
    void restore() {
        props.setMockEnabled(false);
        props.setEmbeddingEnabled(false);
    }

    @Test
    @DisplayName("模型调用阻塞时，300ms 硬超时强制返回 TIME_LIMIT 而不是等待供应商")
    void blockingModelIsInterruptedByHardTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("超时取消中断了模型调用");
            }
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("不应返回")).build();
        }).when(chatModel).chat(any(ChatRequest.class));

        long start = System.nanoTime();
        String body = mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question", "滑动窗口")))
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        JsonNode data = mapper.readTree(body).path("data");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(data.path("stopReason").asText()).isEqualTo("TIME_LIMIT");
        assertThat(data.path("answer").asText()).contains("任务时间上限");
        assertThat(data.path("modelCalls").asInt()).isEqualTo(1);
        assertThat(elapsed).isLessThan(2_000L);
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body).path("data").path("token").asText();
    }
}
