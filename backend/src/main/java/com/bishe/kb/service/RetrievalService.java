package com.bishe.kb.service;

import com.bishe.kb.model.entity.KbChunk;

import java.util.List;

/**
 * 检索服务：把「问什么」和「怎么召回」收敛到一个入口，生产只调混合路，消融实验可切单路。
 *
 * <p>抽出自问答实现的原因有两个：
 * <ol>
 *   <li>引用高亮溯源、评测跑批都需要「只召回、不生成」的能力，不该把 Prompt 组装搅进来；</li>
 *   <li>毕业论文的检索策略消融实验（纯词法 / 纯语义 / 混合 RRF）必须跑在<b>与线上完全相同</b>的
 *       召回代码上，否则实验数据没有说服力。</li>
 * </ol>
 */
public interface RetrievalService {

    /**
     * 召回路由。
     *
     * <p>{@link #HYBRID} 是生产配置；另外两个只为消融实验存在，线上永远不该单独走它们
     * ——纯词法遇到同义改写会召回为空，纯语义遇到专有名词/参数名容易被泛化词带偏。
     */
    enum Route {
        /** 路一：2-gram 切词 + LIKE 命中词数打分 */
        LEXICAL,
        /** 路二：问题向量化后在向量库做余弦相似搜索 */
        SEMANTIC,
        /** 双路各取候选再 RRF 倒数排名融合 */
        HYBRID
    }

    /**
     * 按指定路由召回分段，结果已回表、已过滤被人工禁用的切片，并按得分降序排列。
     *
     * @param question 用户问题
     * @param topK     最终返回的分段条数上限
     * @param route    召回路由
     * @return 命中的分段，按相关性从高到低；无命中时返回空列表
     */
    default List<KbChunk> retrieve(String question, int topK, Route route) {
        // 显式给 unrestricted：4 个参数的重载里 Long 与 RetrievalScope 都能接受 null，
        // 直接传 null 会在这一步变成歧义调用
        return retrieve(question, topK, route, RetrievalScope.unrestricted());
    }

    /**
     * 带语料范围收敛的召回：只允许命中落在 {@code scope} 之内的切片。
     *
     * <p>过滤下推到召回 SQL 里做（{@code document_id in (select id from kb_document where
     * space_id in (...) or space_id = ?)}），而不是「先全库召回再在内存里挑」——后者会让 topK
     * 被范围外的切片占满，指定空间反而召回不到东西，且授权集合越小越明显。
     *
     * <p>更重要的是：{@code scope} 是<b>权限</b>的载体而不只是过滤条件。
     * {@link RetrievalScope#deniesEverything()} 时必须返回空结果，绝不能因为「没有过滤条件」
     * 就退化成全库检索。
     */
    List<KbChunk> retrieve(String question, int topK, Route route, RetrievalScope scope);

    /** 兼容旧签名：只按单个空间收敛，不带授权集合（管理员诊断入口、评测跑批走这里）。 */
    default List<KbChunk> retrieve(String question, int topK, Route route, Long spaceId) {
        Long space = spaceId == null || spaceId <= 0 ? null : spaceId;
        return retrieve(question, topK, route, space == null ? RetrievalScope.unrestricted() : RetrievalScope.ofSpace(space));
    }

    /** 生产入口：混合双路 + RRF 融合 */
    default List<KbChunk> retrieve(String question, int topK) {
        return retrieve(question, topK, Route.HYBRID);
    }

    /** 生产入口的带空间版本：混合双路 + RRF 融合，可收敛到单个空间 */
    default List<KbChunk> retrieve(String question, int topK, Long spaceId) {
        return retrieve(question, topK, Route.HYBRID, spaceId);
    }

    /** 生产入口的授权版本：问答链路用它，空间权限在 {@code scope} 里已经判定过 */
    default List<KbChunk> retrieve(String question, int topK, RetrievalScope scope) {
        return retrieve(question, topK, Route.HYBRID, scope);
    }
}
