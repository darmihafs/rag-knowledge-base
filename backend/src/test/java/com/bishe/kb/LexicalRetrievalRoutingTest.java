package com.bishe.kb;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalService.Route;
import com.bishe.kb.service.impl.RetrievalServiceImpl;
import com.bishe.kb.service.impl.VectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词法召回两条实现路径的切换与降级（第十二阶段：LIKE 全表扫描 -> FULLTEXT 索引）。
 *
 * <p>这里用纯 Mockito 而不是 {@code @SpringBootTest}：H2 没有 {@code MATCH ... AGAINST}，
 * 集成测试里永远只能跑 LIKE 分支，全文分支的「命中即用 / 异常降级 / 配置切换」三种行为
 * 只能在把 Mapper 顶掉之后才能被确定性地断言。
 */
class LexicalRetrievalRoutingTest {

    private static final String QUESTION = "TCP 的滑动窗口是做什么的";

    private KbChunkMapper chunkMapper;
    private RagProperties props;
    private RetrievalServiceImpl retrievalService;

    @BeforeEach
    void setUp() {
        chunkMapper = Mockito.mock(KbChunkMapper.class);
        props = new RagProperties();
        retrievalService = new RetrievalServiceImpl(chunkMapper, Mockito.mock(VectorIndexService.class), props);
    }

    @Test
    @DisplayName("默认走全文索引：一条 SQL 出结果，不再逐词 LIKE")
    void defaultModeShouldUseFulltext() {
        KbChunk hit = chunk(1L, "滑动窗口机制可以避免接收方缓冲区溢出");
        Mockito.when(chunkMapper.searchByFulltext(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of(hit));

        assertThat(retrievalService.retrieve(QUESTION, 5, Route.LEXICAL)).containsExactly(hit);

        Mockito.verify(chunkMapper, Mockito.never())
                .selectPage(Mockito.<Page<KbChunk>>any(), Mockito.any());
    }

    @Test
    @DisplayName("BOOLEAN MODE 表达式：词之间空格即 OR，操作符字符被洗掉")
    void booleanExpressionShouldOrTermsAndStripOperators() {
        Mockito.when(chunkMapper.searchByFulltext(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());

        retrievalService.retrieve("C++ 与 TCP 的滑动窗口", 5, Route.LEXICAL);

        ArgumentCaptor<String> expression = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chunkMapper).searchByFulltext(expression.capture(), Mockito.eq(5));
        // 「+」这类字符在 BOOLEAN MODE 里是操作符，混进去会改掉整条查询的语义
        assertThat(expression.getValue()).doesNotContain("+").doesNotContain("\"").doesNotContain("(");
        // 词与词之间用空格分隔，交给引擎按相关度排序，而不是手工 AND 强制全命中
        assertThat(expression.getValue().split(" ")).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("全文 SQL 抛异常：自动退回 LIKE，问答链路不能因检索实现而中断")
    void fulltextFailureShouldFallBackToLike() {
        Mockito.when(chunkMapper.searchByFulltext(Mockito.anyString(), Mockito.anyInt()))
                .thenThrow(new RuntimeException("only fulltext indices can be used with MATCH"));
        // LIKE 兜底分支靠逐词 selectPage 取候选
        Page<KbChunk> page = new Page<>(1, 10);
        page.setRecords(List.of(chunk(7L, "滑动窗口用于流量控制")));
        Mockito.when(chunkMapper.selectPage(Mockito.<Page<KbChunk>>any(), Mockito.any())).thenReturn(page);

        List<KbChunk> result = retrievalService.retrieve(QUESTION, 5, Route.LEXICAL);

        assertThat(result).extracting(KbChunk::getId).containsExactly(7L);
        Mockito.verify(chunkMapper, Mockito.atLeastOnce())
                .selectPage(Mockito.<Page<KbChunk>>any(), Mockito.any());
    }

    @Test
    @DisplayName("显式配成 like（H2 等场景）：完全不碰全文索引接口")
    void likeModeShouldNeverTouchFulltext() {
        props.setLexicalMode("like");
        Page<KbChunk> page = new Page<>(1, 10);
        page.setRecords(List.of(chunk(9L, "滑动窗口")));
        Mockito.when(chunkMapper.selectPage(Mockito.<Page<KbChunk>>any(), Mockito.any())).thenReturn(page);

        assertThat(retrievalService.retrieve(QUESTION, 5, Route.LEXICAL)).hasSize(1);

        Mockito.verify(chunkMapper, Mockito.never())
                .searchByFulltext(Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    @DisplayName("配置留空或写错一律按 fulltext：手误不能静默退化成全表扫描")
    void blankOrTypoModeShouldKeepFulltext() {
        assertThat(new RagProperties().isFulltextLexical()).isTrue();
        props.setLexicalMode(null);
        assertThat(props.isFulltextLexical()).isTrue();
        props.setLexicalMode("  FULLTEXT ");
        assertThat(props.isFulltextLexical()).isTrue();
        props.setLexicalMode("LIKE");
        assertThat(props.isFulltextLexical()).isFalse();
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
