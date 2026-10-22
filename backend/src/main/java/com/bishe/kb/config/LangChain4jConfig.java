package com.bishe.kb.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * RAG 模型接入层：把外部大模型服务装配成 LangChain4j 的抽象接口，
 * service 只依赖 {@link ChatModel} / {@link EmbeddingModel}，换供应商时不改业务代码。
 *
 * <p>{@code rag.embedding-enabled=false} 时不注册向量模型，开发期可以先跑通
 * “上传 -> 解析 -> 分块 -> 落库”链路，不需要任何 API Key。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class LangChain4jConfig {

    @Bean
    public ChatModel chatModel(RagProperties props) {
        log.info("初始化对话模型: {} @ {}", props.getChatModel(), props.getBaseUrl());
        return OpenAiChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getChatModel())
                .temperature(props.getTemperature())
                .timeout(Duration.ofSeconds(60))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 流式对话模型：SSE 打字机效果的核心，逐 token 回调而不是等整段回答。
     */
    @Bean
    public StreamingChatModel streamingChatModel(RagProperties props) {
        log.info("初始化流式对话模型: {} @ {}", props.getChatModel(), props.getBaseUrl());
        return OpenAiStreamingChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getChatModel())
                .temperature(props.getTemperature())
                .timeout(Duration.ofSeconds(120))
                .logRequests(false)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag", name = "embedding-enabled", havingValue = "true")
    public EmbeddingModel embeddingModel(RagProperties props) {
        log.info("初始化向量模型: {}（超时 {} 秒）", props.getEmbeddingModel(), props.getEmbeddingTimeoutSeconds());
        return OpenAiEmbeddingModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getEmbeddingModel())
                // 检索侧的超时比对话侧紧得多：向量回不来就让词法路顶上，别把用户挂在 SSE 连接上干等
                .timeout(Duration.ofSeconds(props.getEmbeddingTimeoutSeconds()))
                .build();
    }

    /**
     * 向量库：一期用 LangChain4j 的内存实现 + 本地 JSON 持久化，零外部依赖。
     *
     * <p>启动时若 {@code rag.embedding-store-path} 指向的文件已存在，就用 {@code fromFile}
     * 把上次序列化的向量加载回来（重启不丢索引）；文件不存在或损坏则退回空库，绝不阻断启动。
     *
     * <p>该 Bean 无条件注册：即便 {@code embedding-enabled=false}，检索侧也需要一个可注入的
     * {@link EmbeddingStore}，此时它只是保持为空，双路召回自动退化成纯词法。
     *
     * <p>二期切 Milvus / Qdrant：引入对应 starter，只改这里返回外部实现 + 检索 service，业务不动。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(RagProperties props) {
        Path path = Paths.get(props.getEmbeddingStorePath()).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            try {
                InMemoryEmbeddingStore<TextSegment> loaded = InMemoryEmbeddingStore.fromFile(path);
                log.info("已从本地文件加载向量库: {} ({} 条)", path, loaded.size());
                return loaded;
            } catch (RuntimeException e) {
                log.warn("向量库文件加载失败，改用空库（不影响启动）: {} —— {}", path, e.getMessage());
            }
        }
        log.info("初始化空向量库，持久化路径: {}", path);
        return new InMemoryEmbeddingStore<>();
    }
}
