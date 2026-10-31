package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.RetrievalScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合双路召回实现（Hybrid Retrieval + RRF 融合），逻辑与第七阶段问答链路里的私有方法完全一致，
 * 仅做位置迁移，不改任何打分与截断规则。
 *
 * <ol>
 *   <li>路一 · 词法召回：{@link #lexicalRetrieve} 默认走 MySQL FULLTEXT ngram 索引打分
 *       （{@code rag.lexical-mode=like} 时退回逐词 LIKE），擅长精确关键词/专有名词；</li>
 *   <li>路二 · 语义召回：{@link VectorIndexService#searchChunkIds} 的向量余弦相似，擅长同义/近义表达
 *       （字面无重叠也能命中）；向量不可用时该路返回空，自动退化为纯词法。</li>
 *   <li>用 RRF（倒数排名融合）合并两路排名、去重，取最终 topK 回表加载分段正文。</li>
 * </ol>
 *
 * <p>两路各自多召回一些（{@code topK*2}）再融合，避免单路截断过早丢掉最终会胜出的片段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalServiceImpl implements RetrievalService {

    /** 一次提问最多展开多少个检索词，控制词法召回的查询规模 */
    private static final int MAX_TERMS = 12;

    /**
     * 指定范围时语义路的候选放大倍数：向量库不认识「空间」，只能先多取一些候选，
     * 再在回表时按范围过滤，否则收敛范围后的 topK 会被范围外的切片占满。
     */
    private static final int SCOPED_SEMANTIC_FACTOR = 3;

    private final KbChunkMapper chunkMapper;
    private final VectorIndexService vectorIndexService;
    private final RagProperties props;

    @Override
    public List<KbChunk> retrieve(String question, int topK, Route route, RetrievalScope scope) {
        if (StringUtils.isBlank(question) || topK <= 0) {
            return List.of();
        }
        RetrievalScope effective = scope == null ? RetrievalScope.unrestricted() : scope;
        if (effective.deniesEverything()) {
            // 授权集合为空 = 这个账号一个空间都没权限。这里必须直接空返回，
            // 少了这一层，「没授权」会一路走到 unrestricted 分支变成全库检索
            log.warn("检索范围为空（账号未授权任何知识库空间），本次召回直接返回空 question={}",
                    StringUtils.abbreviate(question, 40));
            return List.of();
        }
        return switch (route) {
            // 纯词法：词法路自己已完成打分与排序，直接返回
            case LEXICAL -> lexicalRetrieve(question, topK, effective);
            // 纯语义：只有分段 ID，需要回表并按名次重排
            case SEMANTIC -> loadRanked(
                    vectorIndexService.searchChunkIds(question,
                            semanticLimit(effective, topK), effective), effective);
            case HYBRID -> hybridRetrieve(question, topK, effective);
        };
    }

    /** 收敛范围后语义路要多取几倍候选，否则回表过滤完就不剩东西了 */
    private int semanticLimit(RetrievalScope scope, int topK) {
        return scope.restricted() ? topK * SCOPED_SEMANTIC_FACTOR : topK;
    }

    /** 混合召回：双路各取 2 倍候选 -> RRF 融合去重 -> 回表重排 */
    private List<KbChunk> hybridRetrieve(String question, int topK, RetrievalScope scope) {
        int candidateK = topK * 2;

        List<Long> lexicalIds = lexicalRetrieve(question, candidateK, scope).stream()
                .map(KbChunk::getId)
                .toList();
        // 未启用/异常时语义路为空列表，融合会自动退化成纯词法
        List<Long> semanticIds =
                vectorIndexService.searchChunkIds(question, semanticLimit(scope, candidateK), scope);

        List<Long> fusedIds = rrfFuse(lexicalIds, semanticIds, topK);
        if (fusedIds.isEmpty()) {
            return List.of();
        }
        return loadRanked(fusedIds, scope);
    }

    /**
     * 回表按 id 批量取分段正文，再按传入的名次重排（selectBatchIds 不保证顺序）。
     *
     * <p>语义路可能召回已被人工禁用、已删除、或根本不属于本次范围的分段，
     * 这两道过滤都在这里补上：{@code enabled=0} 绝不进 Prompt，范围外的切片同样进不了。
     */
    private List<KbChunk> loadRanked(List<Long> rankedIds, RetrievalScope scope) {
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        List<KbChunk> rows = scope.restricted()
                ? chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                        .in(KbChunk::getId, rankedIds)
                        .inSql(KbChunk::getDocumentId, spaceSubquery(scope)))
                : chunkMapper.selectBatchIds(rankedIds);
        Map<Long, KbChunk> byId = rows.stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled() == 1)
                .collect(Collectors.toMap(KbChunk::getId, c -> c, (a, b) -> a));
        return rankedIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * RRF（Reciprocal Rank Fusion）倒数排名融合：每个文档的得分 = Σ 1/(k + rank)，rank 从 1 开始。
     * 只看名次不看两路原始分数的量纲，天然规避了「词法命中数」和「余弦相似度」不可直接相加的问题。
     *
     * @return 融合去重后、按得分降序（同分按 id 升序保证稳定）取前 limit 的分段 ID
     */
    private List<Long> rrfFuse(List<Long> lexicalIds, List<Long> semanticIds, int limit) {
        int k = props.getRrfK();
        Map<Long, Double> scores = new HashMap<>();
        addRrfScores(scores, lexicalIds, k);
        addRrfScores(scores, semanticIds, k);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 把一路召回结果的名次换算成 RRF 得分累加进 scores（rank 从 1 开始） */
    private void addRrfScores(Map<Long, Double> scores, List<Long> rankedIds, int k) {
        for (int rank = 0; rank < rankedIds.size(); rank++) {
            Long id = rankedIds.get(rank);
            scores.merge(id, 1.0d / (k + rank + 1), Double::sum);
        }
    }

    /**
     * 词法召回：先把问题切成候选词（标点/空格分词，中文长词再切 2-gram，否则整句基本命中不了），
     * 再按 {@code rag.lexical-mode} 选实现——默认交给 FULLTEXT ngram 索引一条 SQL 完成匹配与打分，
     * 索引不可用或显式配成 {@code like} 时走 {@link #likeRetrieve} 的逐词 LIKE 兜底。
     */
    private List<KbChunk> lexicalRetrieve(String question, int topK, RetrievalScope scope) {
        List<String> terms = buildTerms(question);
        if (terms.isEmpty()) {
            return List.of();
        }
        if (props.isFulltextLexical()) {
            try {
                // 一条 SQL 里完成多词 OR 匹配 + 相关度打分 + 排序截断，命中走 ft_chunk_content(ngram)
                String expression = booleanExpression(terms);
                if (scope.spaceId() != null) {
                    return chunkMapper.searchByFulltextInSpace(expression, topK, scope.spaceId());
                }
                if (scope.visibleSpaceIds() != null) {
                    return chunkMapper.searchByFulltextInSpaces(expression, topK, scope.visibleSpaceIds());
                }
                return chunkMapper.searchByFulltext(expression, topK);
            } catch (RuntimeException e) {
                // 索引被删、数据库不支持该语法等：退回 LIKE，宁可慢也不让问答断掉
                log.warn("全文索引词法召回失败，本次退回 LIKE：{}", e.getMessage());
            }
        }
        return likeRetrieve(terms, topK, scope);
    }

    /**
     * 词法召回的 LIKE 兜底实现（纯字面匹配，不依赖任何数据库特性）：
     * <ol>
     *   <li>逐词查询，按“命中词数”给片段打分，相当于一个极简的词频排序</li>
     *   <li>按得分降序取 topK</li>
     * </ol>
     *
     * <p>代价是每个检索词一次 {@code LIKE '%词%'} 全表扫描，只作为全文索引不可用时的降级路径。
     */
    private List<KbChunk> likeRetrieve(List<String> terms, int topK, RetrievalScope scope) {
        Map<Long, KbChunk> byId = new LinkedHashMap<>();
        Map<Long, Integer> score = new HashMap<>();

        for (String term : terms) {
            List<KbChunk> part = chunkMapper.selectPage(new Page<>(1, (long) topK * 2),
                            scoped(scope, new LambdaQueryWrapper<KbChunk>()
                                    .eq(KbChunk::getEnabled, 1)
                                    .like(KbChunk::getContent, term)))
                    .getRecords();
            for (KbChunk chunk : part) {
                byId.putIfAbsent(chunk.getId(), chunk);
                score.merge(chunk.getId(), 1, Integer::sum);
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparingInt((KbChunk c) -> score.getOrDefault(c.getId(), 0)).reversed()
                        .thenComparing(KbChunk::getDocumentId)
                        .thenComparing(KbChunk::getChunkIndex))
                .limit(topK)
                .toList();
    }

    /**
     * 给 wrapper 补上范围约束（LIKE 兜底路与回表都用它）。
     *
     * <p>{@code inSql} 拼的是子查询，唯一的动态部分是空间 ID 列表，而这些值都是
     * {@code Long}——单个空间来自 {@code RetrievalScope.ofSpace} 前的正数校验，集合来自
     * {@code kb_space_member} 主键列，不存在字符串注入面。
     */
    private LambdaQueryWrapper<KbChunk> scoped(RetrievalScope scope, LambdaQueryWrapper<KbChunk> wrapper) {
        return scope.restricted()
                ? wrapper.inSql(KbChunk::getDocumentId, spaceSubquery(scope))
                : wrapper;
    }

    /**
     * 范围 -> 「范围内全部文档 ID」的子查询。
     *
     * <p>带上 {@code deleted = 0} 是因为 {@code inSql} 写的是原生子查询，MyBatis-Plus 的
     * 逻辑删除条件不会注进去：不写这一句，一个已删除文档的残留切片照样能被召回。
     */
    private String spaceSubquery(RetrievalScope scope) {
        if (scope.spaceId() != null) {
            return "select id from kb_document where space_id = " + scope.spaceId() + " and deleted = 0";
        }
        List<Long> visible = scope.visibleSpaceIds();
        if (visible == null || visible.isEmpty()) {
            // 空集合拼出 in () 是语法错误；真正的空授权在 retrieve 入口已被 deniesEverything 挡掉，
            // 这里再兜一层「永远不命中」，保证任何内部调用方都不会意外退化成全库。
            return "select id from kb_document where 1 = 0";
        }
        String joined = visible.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return "select id from kb_document where space_id in (" + joined + ") and deleted = 0";
    }

    /**
     * 检索词 -> BOOLEAN MODE 表达式：空格分隔即 OR 语义，靠相关度自然地把命中词多的排到前面。
     *
     * <p>{@code + - > < ( ) ~ * " @} 在 BOOLEAN MODE 里是操作符，必须去掉——正常情况下
     * {@link #buildTerms} 已经把标点洗掉了，这里再兜一层，避免脏词把整条 SQL 的语义改掉。
     */
    private String booleanExpression(List<String> terms) {
        return terms.stream()
                .map(term -> term.replaceAll("[+\\-><()~*\"@]", ""))
                .filter(term -> !term.isBlank())
                .collect(Collectors.joining(" "));
    }

    /** 问题 -> 候选检索词：英文按空格，中文额外切 2-gram */
    private List<String> buildTerms(String question) {
        String normalized = StringUtils.lowerCase(question)
                .replaceAll("[\\p{Punct}\\p{Space}，。？！、；：‘’“”（）【】《》]+", " ")
                .trim();
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : normalized.split(" ")) {
            String word = raw.trim();
            if (word.isEmpty()) {
                continue;
            }
            terms.add(word);
            if (word.length() > 4 && containsCjk(word)) {
                for (int i = 0; i + 2 <= word.length(); i++) {
                    terms.add(word.substring(i, i + 2));
                }
            }
        }
        return terms.stream()
                .filter(t -> t.length() >= 2 || !containsCjk(t))
                .limit(MAX_TERMS)
                .toList();
    }

    private boolean containsCjk(String text) {
        return text.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }
}
