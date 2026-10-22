package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.service.impl.VectorIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问题向量缓存与启动预热的单元测试（纯内存，不起 Spring 上下文，也不出网）。
 *
 * <p>这一路的成本结构决定了测试的重点：语义召回慢不是因为算得慢，而是因为<b>每个问题都要出一次公网</b>。
 * 所以要用一个会数调用次数的假模型，证明三件事——重复问题不再出网、换模型不会复用旧向量、
 * 未启用时一次请求都不该发出去。用计数桩而不是 Mockito 打桩，是因为「发了几次、发了什么内容」
 * 本身就是被断言的对象，比 verify 更直观。
 */
class QuestionEmbeddingCacheTest {

    /** 命中这道题时向量库里的正确答案：切片 101 与问题同向，102 反向 */
    private static final float[] QUESTION_VECTOR = {1f, 0f, 0f};
    private static final float[] OPPOSITE_VECTOR = {-1f, 0f, 0f};

    /** 记录每一次远程向量化调用的假模型：calls 就是「出网次数」 */
    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Response<Embedding> embed(String text) {
            calls.add(text);
            return Response.from(Embedding.from(QUESTION_VECTOR));
        }

        int times() {
            return calls.size();
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }

    private VectorIndexService newService(EmbeddingModel model, RagProperties props,
                                          EmbeddingStore<TextSegment> store) {
        ObjectProvider<EmbeddingModel> provider = new ObjectProvider<>() {
            @Override
            public EmbeddingModel getObject() {
                return model;
            }

            @Override
            public EmbeddingModel getIfAvailable() {
                return model;
            }
        };
        return new VectorIndexService(provider, store, props, new ObjectMapper());
    }

    private VectorIndexService newService(EmbeddingModel model, RagProperties props) {
        return newService(model, props, new InMemoryEmbeddingStore<>());
    }

    /** 打开向量检索的最小配置：测试里绝不依赖真实的 store 文件与网络 */
    private RagProperties enabledProps() {
        RagProperties props = new RagProperties();
        props.setEmbeddingEnabled(true);
        props.setEmbeddingModel("text-embedding-v3");
        // 指向一个必然不存在的文件：inspectStore() 拿不到现成维度，才会真正去读 probedDimension，
        // 也顺便保证单测不会碰开发机上那份 1536 维的真实向量库
        props.setEmbeddingStorePath("./data/embeddings/none-" + System.nanoTime() + ".json");
        return props;
    }

    /** 向量库按生产口径写入：id 用 chunkId，检索侧才会拿到 Long 主键而不是随机 UUID */
    private EmbeddingStore<TextSegment> storeWithTwoChunks() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.add("101", Embedding.from(QUESTION_VECTOR), TextSegment.from("滑动窗口是接收方告知发送方当前可接收数据量的机制"));
        store.add("102", Embedding.from(OPPOSITE_VECTOR), TextSegment.from("操作系统的时间片轮转调度算法"));
        return store;
    }

    @Test
    @DisplayName("同一个问题第二次问不再出网：命中缓存直接复用向量")
    void secondIdenticalQuestionHitsCache() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        VectorIndexService service = newService(model, enabledProps());

        Embedding first = service.embedQuestion("滑动窗口是怎么工作的");
        Embedding second = service.embedQuestion("滑动窗口是怎么工作的");

        assertThat(model.times()).isEqualTo(1);
        // 复用同一个实例，而不是重新算一个值相同的向量
        assertThat(second).isSameAs(first);
        assertThat(second.dimension()).isEqualTo(3);
    }

    @Test
    @DisplayName("句首句尾空白归一化：同一句话的两种写法共用一个缓存位")
    void whitespaceVariantHitsSameCacheEntry() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        VectorIndexService service = newService(model, enabledProps());

        service.embedQuestion("  滑动窗口是怎么工作的  ");
        service.embedQuestion("滑动窗口是怎么工作的");

        assertThat(model.times()).isEqualTo(1);
        // 出网的是去掉首尾空白的干净句子
        assertThat(model.calls()).containsExactly("滑动窗口是怎么工作的");
    }

    @Test
    @DisplayName("语义召回走缓存：连问两次同一题只发一次向量请求，且召回结果不变")
    void searchChunkIdsUsesCachedQuestionVector() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RagProperties props = enabledProps();
        // 与生产一致的口径：只按排名截断会被低分噪声淹没，这里卡掉反向的那条
        props.setSemanticMinScore(0.1d);
        VectorIndexService service = newService(model, props, storeWithTwoChunks());

        List<Long> first = service.searchChunkIds("滑动窗口是怎么工作的", 5);
        List<Long> second = service.searchChunkIds("滑动窗口是怎么工作的", 5);

        assertThat(first).containsExactly(101L);
        assertThat(second).isEqualTo(first);
        assertThat(model.times()).isEqualTo(1);
    }

    @Test
    @DisplayName("缓存 key 带模型名：换了 embedding 模型绝不能复用旧语义空间的向量")
    void cacheKeyCarriesModelName() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RagProperties props = enabledProps();
        VectorIndexService service = newService(model, props);

        service.embedQuestion("TCP 三次握手的原因");
        props.setEmbeddingModel("text-embedding-v4");
        service.embedQuestion("TCP 三次握手的原因");

        assertThat(model.times()).isEqualTo(2);
    }

    @Test
    @DisplayName("LRU 有界：容量 1 时问第三个问题会把最老的挤出缓存，内存不会随问题数无限增长")
    void lruEvictsEldestEntry() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RagProperties props = enabledProps();
        props.setEmbeddingQueryCacheSize(1);
        VectorIndexService service = newService(model, props);

        service.embedQuestion("问题一");
        service.embedQuestion("问题二");
        // 问题一已被问题二挤掉，只能重新出网
        service.embedQuestion("问题一");

        assertThat(model.times()).isEqualTo(3);
    }

    @Test
    @DisplayName("容量配 0 就是不缓存：每个问题都实打实出网一次")
    void cacheDisabledAlwaysCallsRemote() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RagProperties props = enabledProps();
        props.setEmbeddingQueryCacheSize(0);
        VectorIndexService service = newService(model, props);

        service.embedQuestion("同一个问题");
        service.embedQuestion("同一个问题");

        assertThat(model.times()).isEqualTo(2);
    }

    @Test
    @DisplayName("预热在向量检索未启用时一次都不出网：离线演示不会因此多花一分钱")
    void warmUpSkippedWhenDisabled() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        RagProperties props = enabledProps();
        props.setEmbeddingEnabled(false);
        VectorIndexService service = newService(model, props);

        assertThat(service.warmUp()).contains("已跳过");
        assertThat(model.times()).isZero();
        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.embedQuestion("任何问题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("预热发一次远程调用并顺带把维度填进缓存：大屏不用再探测第二次")
    void warmUpFiresOneRemoteCallAndCachesDimension() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        VectorIndexService service = newService(model, enabledProps());

        String summary = service.warmUp();

        assertThat(summary).contains("完成").contains("维度=3");
        assertThat(model.times()).isEqualTo(1);
        // 维度已被预热填进缓存：大屏读维度不会再发第二次远程请求
        assertThat(service.resolveDimension()).isEqualTo(3);
        assertThat(model.times()).isEqualTo(1);
    }

    @Test
    @DisplayName("空问题直接拒绝，不给出网留一个必然失败的口子")
    void blankQuestionIsRejected() {
        CountingEmbeddingModel model = new CountingEmbeddingModel();
        VectorIndexService service = newService(model, enabledProps());

        assertThatThrownBy(() -> service.embedQuestion("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.embedQuestion(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(model.times()).isZero();
        // 召回入口自己的兜底：空问题返回空列表而不是异常
        assertThat(service.searchChunkIds("   ", 5)).isEmpty();
    }
}
