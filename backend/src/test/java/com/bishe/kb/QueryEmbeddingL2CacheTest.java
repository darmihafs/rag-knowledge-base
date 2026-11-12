package com.bishe.kb;

import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.service.impl.LocalAtomicFileVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问题向量二级缓存（Caffeine L1 + Redis L2）的单元测试：纯内存、不起 Spring、不出网。
 * L2 用 Mockito 打桩 {@link RedisCacheService}，验证「L1 命中不出网也不查 L2、L2 命中省去远程向量化、
 * L2 未命中回源并写回、L2 故障降级仅用 L1」四条路径，以及 L1 命中仍返回同一实例。
 */
class QueryEmbeddingL2CacheTest {

    private static final float[] VECTOR = {1f, 0f, 0f};

    /** 记录远程向量化次数的假模型 */
    private static final class CountingEmbeddingModel implements EmbeddingModel {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Response<Embedding> embed(String text) {
            calls.add(text);
            return Response.from(Embedding.from(VECTOR));
        }

        int times() {
            return calls.size();
        }
    }

    private RagProperties enabledProps() {
        RagProperties props = new RagProperties();
        props.setEmbeddingEnabled(true);
        props.setEmbeddingModel("text-embedding-v3");
        props.setEmbeddingStorePath("./data/embeddings/none-" + System.nanoTime() + ".json");
        return props;
    }

    @SuppressWarnings("unchecked")
    private LocalAtomicFileVectorStore newStore(EmbeddingModel model, RagProperties props, RedisCacheService redis) {
        ObjectProvider<EmbeddingModel> modelProvider = mock(ObjectProvider.class);
        when(modelProvider.getObject()).thenReturn(model);
        when(modelProvider.getIfAvailable()).thenReturn(model);
        ObjectProvider<RedisCacheService> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        return new LocalAtomicFileVectorStore(modelProvider, new InMemoryEmbeddingStore<>(),
                props, new ObjectMapper(), redisProvider);
    }

    @Test
    @DisplayName("L2 命中直接复用向量：一次远程向量化都不发，Key 形如 rag:emb:{sha256}")
    void l2HitAvoidsRemote() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(startsWith("rag:emb:"))).thenReturn("[1.0,0.0,0.0]");
        LocalAtomicFileVectorStore store = newStore(model, enabledProps(), redis);

        Embedding embedding = store.embedQuestion("滑动窗口是怎么工作的");

        assertThat(embedding.vector()).containsExactly(VECTOR);
        assertThat(model.times()).isZero();
        verify(redis).get(startsWith("rag:emb:"));
    }

    @Test
    @DisplayName("L1+L2 都未命中：远程向量化一次，并同时写回 L1 与 L2")
    void missFallsBackToRemoteAndWritesBothLevels() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString())).thenReturn(null);
        LocalAtomicFileVectorStore store = newStore(model, enabledProps(), redis);

        Embedding first = store.embedQuestion("TCP 三次握手的原因");
        assertThat(model.times()).isEqualTo(1);
        verify(redis).set(startsWith("rag:emb:"), anyString(), any(Duration.class));

        // 第二次命中 L1：不再查 L2、也不再出网，且返回同一实例
        Embedding second = store.embedQuestion("TCP 三次握手的原因");
        assertThat(second).isSameAs(first);
        assertThat(model.times()).isEqualTo(1);
        verify(redis, times(1)).get(anyString());
    }

    @Test
    @DisplayName("L2 值损坏：反序列化失败当作未命中，回源远程向量化而不是抛异常")
    void corruptL2ValueFallsBackToRemote() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString())).thenReturn("这不是合法的向量 JSON");
        LocalAtomicFileVectorStore store = newStore(model, enabledProps(), redis);

        Embedding embedding = store.embedQuestion("操作系统的时间片轮转");

        assertThat(embedding.vector()).containsExactly(VECTOR);
        assertThat(model.times()).isEqualTo(1);
    }

    @Test
    @DisplayName("L2 读取降级（返回 null）：仅用 L1 与远程，问答主链路不受影响")
    void l2DegradedStillWorks() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        // RedisCacheService 内部降级后 get 返回 null，等价于 L2 不可用
        when(redis.get(anyString())).thenReturn(null);
        LocalAtomicFileVectorStore store = newStore(model, enabledProps(), redis);

        store.embedQuestion("同一个问题");
        store.embedQuestion("同一个问题");

        // 第一次出网，第二次命中 L1
        assertThat(model.times()).isEqualTo(1);
    }

    @Test
    @DisplayName("换 embedding 模型：L2 Key 随模型名变化，绝不复用旧语义空间的向量")
    void l2KeyCarriesModelName() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString())).thenReturn(null);
        RagProperties props = enabledProps();
        LocalAtomicFileVectorStore store = newStore(model, props, redis);

        store.embedQuestion("TCP 三次握手的原因");
        props.setEmbeddingModel("text-embedding-v4");
        store.embedQuestion("TCP 三次握手的原因");

        // 两个模型各出网一次，且写了两个不同的 L2 Key
        assertThat(model.times()).isEqualTo(2);
        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).set(keys.capture(), anyString(), any(Duration.class));
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        assertThat(keys.getAllValues()).allMatch(k -> k.startsWith("rag:emb:"));
    }

    @Test
    @DisplayName("未装配 Redis（四参构造）：退化为仅 L1，行为与升级前一致")
    void noRedisFallsBackToL1Only() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RedisCacheService redis = mock(RedisCacheService.class);
        // 四参构造不接 Redis：这里用 mock 仅为对照，实际不会被调用
        LocalAtomicFileVectorStore store = newStore(model, enabledProps(), null);

        Embedding first = store.embedQuestion("滑动窗口");
        Embedding second = store.embedQuestion("滑动窗口");

        assertThat(second).isSameAs(first);
        assertThat(model.times()).isEqualTo(1);
        verify(redis, never()).get(anyString());
    }
}