package com.bishe.kb.model.vo;

import java.util.List;

/**
 * 三路召回对比结果（检索对比实验室专用）。
 *
 * <ul>
 *   <li>{@code routes} —— 固定三条：LEXICAL / SEMANTIC / HYBRID，顺序即前端三列的排布顺序；</li>
 *   <li>{@code engine} —— 本次生效的引擎参数，便于页面上说明「RRF k=60」「向量路是否可用」。</li>
 * </ul>
 *
 * <p>耗时为各路端到端召回时间（毫秒）：词法路只有数据库 LIKE，语义/混合路含一次问题向量化网络请求，
 * 因此耗时对比要与质量对比一起看，不能只看快慢。
 */
public record RetrieveCompareVO(
        String question,
        int topK,
        /** 本次召回收敛到的知识库空间 ID；null 表示全库检索 */
        Long spaceId,
        Engine engine,
        List<Route> routes
) {

    /** 引擎快照：让页面能如实标注参数口径，而不是让人去猜后端配置 */
    public record Engine(
            int rrfK,
            String embeddingModel,
            boolean vectorAvailable,
            String lexicalStrategy,
            String semanticStrategy,
            String hybridStrategy,
            /** 检索范围口径，例如「全库（不限制空间）」或「已收敛到空间：协议库」 */
            String scopeStrategy
    ) {
    }

    /**
     * 单条召回路的结果。
     *
     * @param available 该路本次是否真的产出了召回能力：语义路在向量化关闭时为空且 available=false
     * @param note      不可用或退化时的说明文案，前端直接展示
     */
    public record Route(
            String strategy,
            String strategyCn,
            String description,
            long elapsedMillis,
            boolean available,
            String note,
            int hitCount,
            List<Item> items
    ) {
    }

    /** 召回列表里的一条切片；rank 从 1 开始，就是该路内的名次 */
    public record Item(
            int rank,
            Long chunkId,
            Long documentId,
            String documentTitle,
            Integer chunkIndex,
            Integer pageNumber,
            Integer charCount,
            String snippet
    ) {
    }
}
