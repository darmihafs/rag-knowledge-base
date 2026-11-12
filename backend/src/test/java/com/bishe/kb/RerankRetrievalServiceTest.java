package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.RetrievalService.Route;
import com.bishe.kb.service.impl.DashScopeRerankClient;
import com.bishe.kb.service.impl.DashScopeRerankClient.ScoredIndex;
import com.bishe.kb.service.impl.RerankRetrievalService;
import com.bishe.kb.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rerank 精排装饰器的离线单元测试：Mockito 顶掉粗排实现与 rerank 客户端，
 * 确定性覆盖「重排 / 截断 / 透传 / 降级」四类行为，不连数据库也不发网络请求。
 *
 * <p>重点保护两条契约：
 * <ol>
 *   <li>精排失败（超时/报错/空结果）必须平滑回退 RRF 粗排 Top-K，绝不把异常抛进问答链路；</li>
 *   <li>LEXICAL / SEMANTIC 单路与精排不可用场景必须原样透传，消融实验与旧测试不受污染。</li>
 * </ol>
 */
class RerankRetrievalServiceTest {

    private static final String QUESTION = "滑动窗口是怎么做流量控制的";

    private RetrievalServiceImpl delegate;
    private DashScopeRerankClient client;
    private RagProperties props;
    private RerankRetrievalService service;

    @BeforeEach
    void setUp() {
        delegate = Mockito.mock(RetrievalServiceImpl.class);
        client = Mockito.mock(DashScopeRerankClient.class);
        props = new RagProperties();
        props.setApiKey("sk-unit-test");
        Mockito.when(client.isAvailable()).thenReturn(true);
        service = new RerankRetrievalService(delegate, client, props);
    }

    @Test
    @DisplayName("混合路：粗排多取 candidates 条，精排按 score 重排后映射回原切片")
    void hybridShouldRerankCoarseCandidates() {
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"), chunk(3L, "丙"));
        RetrievalScope scope = RetrievalScope.ofSpace(7L);
        Mockito.when(delegate.retrieve(QUESTION, 20, Route.HYBRID, scope)).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(new ScoredIndex(2, 0.9), new ScoredIndex(0, 0.6), new ScoredIndex(1, 0.2)));

        List<KbChunk> result = service.retrieve(QUESTION, 5, Route.HYBRID, scope);

        assertThat(result).extracting(KbChunk::getId).containsExactly(3L, 1L, 2L);
        // 粗排候选数 = max(topK, candidates=20)，权限范围原样下传
        Mockito.verify(delegate).retrieve(QUESTION, 20, Route.HYBRID, scope);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> docs = ArgumentCaptor.forClass(List.class);
        Mockito.verify(client).rerank(Mockito.eq(QUESTION), docs.capture(), Mockito.eq(5));
        assertThat(docs.getValue()).containsExactly("甲", "乙", "丙");
    }

    @Test
    @DisplayName("保留条数 = min(topK, topN)：两侧上限都不能被突破")
    void keepShouldBeMinOfTopKAndTopN() {
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"), chunk(3L, "丙"),
                chunk(4L, "丁"), chunk(5L, "戊"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.anyInt(), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(new ScoredIndex(4, 0.9), new ScoredIndex(3, 0.8),
                        new ScoredIndex(2, 0.7), new ScoredIndex(1, 0.6), new ScoredIndex(0, 0.5)));

        // topK=3 < topN=5：最多回 3 条
        assertThat(service.retrieve(QUESTION, 3, Route.HYBRID))
                .extracting(KbChunk::getId).containsExactly(5L, 4L, 3L);

        // topN=2 < topK=5：精排只要 2 条，客户端也只被要求返回 2 条
        props.getRerank().setTopN(2);
        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID))
                .extracting(KbChunk::getId).containsExactly(5L, 4L);
        Mockito.verify(client).rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.eq(2));
    }

    @Test
    @DisplayName("topN 配成 0（误配）：按 topK 兜底，不会把结果截成空")
    void zeroTopNShouldFallBackToTopK() {
        props.getRerank().setTopN(0);
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.anyInt(), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(new ScoredIndex(1, 0.9), new ScoredIndex(0, 0.5)));

        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID))
                .extracting(KbChunk::getId).containsExactly(2L, 1L);
        Mockito.verify(client).rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.eq(5));
    }

    @Test
    @DisplayName("精排不可用（关开关/无密钥）：按原 topK 透传粗排，一次 rerank 都不调")
    void unavailableShouldPassthroughWithOriginalTopK() {
        Mockito.when(client.isAvailable()).thenReturn(false);
        List<KbChunk> coarse = List.of(chunk(1L, "甲"));
        RetrievalScope scope = RetrievalScope.ofSpace(3L);
        Mockito.when(delegate.retrieve(QUESTION, 5, Route.HYBRID, scope)).thenReturn(coarse);

        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID, scope)).containsExactlyElementsOf(coarse);
        Mockito.verify(delegate).retrieve(QUESTION, 5, Route.HYBRID, scope);
        Mockito.verify(client, Mockito.never()).rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt());
    }

    @Test
    @DisplayName("LEXICAL / SEMANTIC 单路：即使精排可用也原样透传，消融实验不被污染")
    void singleRoutesShouldPassthrough() {
        List<KbChunk> hits = List.of(chunk(1L, "甲"));
        for (Route route : List.of(Route.LEXICAL, Route.SEMANTIC)) {
            Mockito.when(delegate.retrieve(QUESTION, 5, route, RetrievalScope.unrestricted())).thenReturn(hits);
            assertThat(service.retrieve(QUESTION, 5, route)).containsExactlyElementsOf(hits);
            Mockito.verify(delegate).retrieve(QUESTION, 5, route, RetrievalScope.unrestricted());
        }
        Mockito.verify(client, Mockito.never()).rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt());
    }

    @Test
    @DisplayName("精排抛异常（超时/报错）：降级为 RRF 粗排前 topK 条，顺序保持粗排名次")
    void rerankFailureShouldFallBackToCoarseTopK() {
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"), chunk(3L, "丙"),
                chunk(4L, "丁"), chunk(5L, "戊"), chunk(6L, "己"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.anyInt(), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenThrow(new IllegalStateException("rerank 接口调用失败: request timed out"));

        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID))
                .extracting(KbChunk::getId).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("精排返回空列表：同样走降级，而不是把空结果当成「没有答案」")
    void emptyRerankResultShouldFallBack() {
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.anyInt(), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of());

        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID))
                .extracting(KbChunk::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("粗排为空（含空授权）：直接返回空，不发起精排请求")
    void emptyCoarseShouldSkipRerank() {
        RetrievalScope denied = RetrievalScope.visible(List.of());
        Mockito.when(delegate.retrieve(QUESTION, 20, Route.HYBRID, denied)).thenReturn(List.of());

        assertThat(service.retrieve(QUESTION, 5, Route.HYBRID, denied)).isEmpty();
        Mockito.verify(client, Mockito.never()).rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt());
    }

    @Test
    @DisplayName("空问题 / topK≤0：交给粗排按旧语义处理，精排不参与")
    void invalidInputShouldPassthrough() {
        Mockito.when(delegate.retrieve("  ", 5, Route.HYBRID, RetrievalScope.unrestricted()))
                .thenReturn(List.of());
        Mockito.when(delegate.retrieve(QUESTION, 0, Route.HYBRID, RetrievalScope.unrestricted()))
                .thenReturn(List.of());

        assertThat(service.retrieve("  ", 5, Route.HYBRID)).isEmpty();
        assertThat(service.retrieve(QUESTION, 0, Route.HYBRID)).isEmpty();
        Mockito.verify(client, Mockito.never()).rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt());
    }

    @Test
    @DisplayName("candidates 配得比 topK 小：粗排候选数按 topK 兜底，不会越排越少")
    void candidatesSmallerThanTopKShouldUseTopK() {
        props.getRerank().setCandidates(3);
        List<KbChunk> coarse = List.of(chunk(1L, "甲"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.eq(10), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        Mockito.when(client.rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt()))
                .thenReturn(List.of(new ScoredIndex(0, 0.9)));

        assertThat(service.retrieve(QUESTION, 10, Route.HYBRID)).containsExactlyElementsOf(coarse);
        Mockito.verify(delegate).retrieve(Mockito.eq(QUESTION), Mockito.eq(10), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class));
    }

    @Test
    @DisplayName("降级计数器：只在精排失败回退时自增，成功与透传都不计数")
    void fallbackCountShouldTrackDegradationsOnly() {
        List<KbChunk> coarse = List.of(chunk(1L, "甲"), chunk(2L, "乙"));
        Mockito.when(delegate.retrieve(Mockito.eq(QUESTION), Mockito.anyInt(), Mockito.eq(Route.HYBRID),
                Mockito.any(RetrievalScope.class))).thenReturn(coarse);
        assertThat(service.fallbackCount()).isZero();

        // 成功重排：不计数。用 doReturn/doThrow 风格打桩：
        // 方法已被 stub 成抛异常后，再用 when(mock.rerank(...)) 会在打桩现场直接触发异常
        Mockito.doReturn(List.of(new ScoredIndex(1, 0.9), new ScoredIndex(0, 0.5)))
                .when(client).rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt());
        service.retrieve(QUESTION, 5, Route.HYBRID);
        assertThat(service.fallbackCount()).isZero();

        // 异常降级 + 空结果降级：各计一次
        Mockito.doThrow(new IllegalStateException("rerank 接口调用失败: 429 Too Many Requests"))
                .when(client).rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt());
        service.retrieve(QUESTION, 5, Route.HYBRID);
        assertThat(service.fallbackCount()).isEqualTo(1);
        Mockito.doReturn(List.of())
                .when(client).rerank(Mockito.eq(QUESTION), Mockito.anyList(), Mockito.anyInt());
        service.retrieve(QUESTION, 5, Route.HYBRID);
        assertThat(service.fallbackCount()).isEqualTo(2);

        // 单路透传：精排根本没参与，不计数
        Mockito.when(delegate.retrieve(QUESTION, 5, Route.LEXICAL, RetrievalScope.unrestricted()))
                .thenReturn(coarse);
        service.retrieve(QUESTION, 5, Route.LEXICAL);
        assertThat(service.fallbackCount()).isEqualTo(2);
    }

    private KbChunk chunk(long id, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        chunk.setEnabled(1);
        return chunk;
    }
}