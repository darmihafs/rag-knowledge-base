package com.bishe.kb.service.impl;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.model.dto.RetrieveCompareRequest;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.vo.RetrieveCompareVO;
import com.bishe.kb.service.KbSpaceService;
import com.bishe.kb.service.RetrievalCompareService;
import com.bishe.kb.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 三路召回对比实现。
 *
 * <p>三条路各跑一次、各自计时，再一次性批量回表取文档标题（避免逐条查库产生 N+1）。
 * 词法路不依赖任何外部服务，语义路与混合路都包含一次问题向量化请求，
 * 所以二者耗时口径相同、可横向比较，而与词法路比较时应当看召回质量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCompareServiceImpl implements RetrievalCompareService {

    /** 卡片里展示的片段长度：太短看不出内容差异，太长把三列布局撑爆 */
    private static final int SNIPPET_LENGTH = 300;

    private static final int MAX_TOP_K = 20;

    private final RetrievalService retrievalService;
    private final VectorIndexService vectorIndexService;
    private final KbDocumentMapper documentMapper;
    private final KbSpaceService spaceService;
    private final RagProperties props;

    @Override
    public RetrieveCompareVO compare(RetrieveCompareRequest request) {
        int topK = resolveTopK(request.topK());
        String question = request.question().trim();
        boolean vectorAvailable = vectorIndexService.isEnabled();
        Long spaceId = normalizeSpace(request.spaceId());

        List<TimedRoute> runs = List.of(
                run(question, topK, spaceId, RetrievalService.Route.LEXICAL, true, null),
                run(question, topK, spaceId, RetrievalService.Route.SEMANTIC, vectorAvailable,
                        vectorAvailable ? null
                                : "向量化未启用或向量模型不可用（rag.embedding-enabled=false），语义路返回空"),
                run(question, topK, spaceId, RetrievalService.Route.HYBRID, true,
                        vectorAvailable ? null : "语义路不可用时，RRF 融合自动退化为纯词法结果"));

        Map<Long, String> titles = titlesOf(runs);
        List<RetrieveCompareVO.Route> routes = runs.stream()
                .map(r -> toRouteVO(r, topK, titles))
                .toList();

        log.info("检索对比完成 question={} topK={} spaceId={} 词法={} 语义={} 混合={}",
                StringUtils.abbreviate(question, 40), topK, spaceId,
                runs.get(0).hits().size(), runs.get(1).hits().size(), runs.get(2).hits().size());
        return new RetrieveCompareVO(question, topK, spaceId, engine(vectorAvailable, spaceId), routes);
    }

    /** 跑一条路并计时，先只保留原始分段，标题留到后面批量补 */
    private TimedRoute run(String question, int topK, Long spaceId, RetrievalService.Route route,
                           boolean available, String unavailableNote) {
        long start = System.currentTimeMillis();
        List<KbChunk> hits = retrievalService.retrieve(question, topK, route, spaceId);
        long elapsed = System.currentTimeMillis() - start;

        String note = unavailableNote;
        if (note == null && hits.isEmpty()) {
            note = "该路在本轮没有召回到任何启用中的切片";
        }
        return new TimedRoute(route, elapsed, available, note, hits);
    }

    private RetrieveCompareVO.Route toRouteVO(TimedRoute run, int topK, Map<Long, String> titles) {
        List<RetrieveCompareVO.Item> items = new ArrayList<>(run.hits().size());
        for (int i = 0; i < run.hits().size(); i++) {
            KbChunk chunk = run.hits().get(i);
            items.add(new RetrieveCompareVO.Item(i + 1, chunk.getId(), chunk.getDocumentId(),
                    titles.get(chunk.getDocumentId()), chunk.getChunkIndex(), chunk.getPageNumber(),
                    chunk.getCharCount(),
                    StringUtils.abbreviate(StringUtils.defaultString(chunk.getContent()), SNIPPET_LENGTH)));
        }
        return new RetrieveCompareVO.Route(run.route().name(), strategyCn(run.route()),
                description(run.route(), topK), run.elapsedMillis(), run.available(),
                run.note(), items.size(), items);
    }

    /** 三路涉及到的文档 ID 去重后一次查询，标题按 documentId 建索引 */
    private Map<Long, String> titlesOf(List<TimedRoute> runs) {
        Set<Long> docIds = new LinkedHashSet<>();
        for (TimedRoute r : runs) {
            r.hits().forEach(c -> {
                if (c.getDocumentId() != null) {
                    docIds.add(c.getDocumentId());
                }
            });
        }
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, KbDocument::getTitle, (a, b) -> a));
    }

    private RetrieveCompareVO.Engine engine(boolean vectorAvailable, Long spaceId) {
        return new RetrieveCompareVO.Engine(props.getRrfK(), props.getEmbeddingModel(), vectorAvailable,
                lexicalStrategy(),
                "问题向量化后在向量库做余弦相似度检索，按分数降序",
                "两路各取 2×topK 候选，用 RRF Σ1/(k+rank) 融合去重后回表重排",
                scopeStrategy(spaceId));
    }

    /**
     * 检索范围口径。空间被选中时三路共用同一个 {@code spaceId}，
     * 页面上要能看出「这三路是在同一个范围内比的」，否则比较不成立。
     */
    private String scopeStrategy(Long spaceId) {
        if (spaceId == null) {
            return "全库检索（不限制知识库空间）";
        }
        KbSpace space = spaceService.getById(spaceId);
        return "已收敛到空间 #" + spaceId + (space == null ? "（该空间不存在，召回必为空）" : "「" + space.getName() + "」");
    }

    /** 与检索服务同一口径：null 与非正数都表示「不限制空间」 */
    private Long normalizeSpace(Long spaceId) {
        return spaceId == null || spaceId <= 0 ? null : spaceId;
    }

    /**
     * 词法路的口径说明：必须跟着 {@code rag.lexical-mode} 走。
     *
     * <p>检索实验室把这行原样显示给评审看，写死成「LIKE」而实际跑全文索引，等于页面在骗人。
     */
    private String lexicalStrategy() {
        return props.isFulltextLexical()
                ? "标点/空格切词 + 中文 2-gram，MATCH(content) AGAINST(... IN BOOLEAN MODE) 命中 ngram 全文索引"
                : "标点/空格切词 + 中文 2-gram，逐词 LIKE 后按命中词数排序（全文索引不可用时的兜底路径）";
    }

    private String description(RetrievalService.Route route, int topK) {
        return switch (route) {
            case LEXICAL -> props.isFulltextLexical()
                    ? "纯词法：不做语义理解，靠 ngram 全文索引的字面命中与相关度排序，取前 " + topK + " 条"
                    : "纯词法：不做语义理解，靠字面 2-gram 命中，取前 " + topK + " 条";
            case SEMANTIC -> "纯语义：只看向量相似度，同义改写也能命中，取前 " + topK + " 条";
            case HYBRID -> "混合双路 + RRF(k=" + props.getRrfK() + ")：两路候选融合去重，取前 " + topK + " 条";
        };
    }

    private String strategyCn(RetrievalService.Route route) {
        return switch (route) {
            case LEXICAL -> props.isFulltextLexical() ? "纯词法（FULLTEXT ngram）" : "纯词法（2-gram LIKE）";
            case SEMANTIC -> "纯语义向量";
            case HYBRID -> "混合双路 + RRF";
        };
    }

    private int resolveTopK(Integer requested) {
        int value = requested == null || requested <= 0 ? props.getTopK() : requested;
        return Math.min(value, MAX_TOP_K);
    }

    /** 单路跑的中间结果：原始分段 + 耗时 + 可用性说明 */
    private record TimedRoute(RetrievalService.Route route, long elapsedMillis, boolean available,
                              String note, List<KbChunk> hits) {
    }
}
