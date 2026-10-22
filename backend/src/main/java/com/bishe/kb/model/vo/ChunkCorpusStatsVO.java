package com.bishe.kb.model.vo;

/**
 * 全局语料中心的顶部统计条：全库切片规模与启停分布。
 *
 * <p>统计口径是「全库」而非「当前筛选结果」，当前筛选下的总数直接用分页对象的
 * {@code total} 即可，两者在前端分开展示，避免管理员把筛选计数误读成库存量。
 *
 * <p>文档数 / 空间数取自冗余计数列（{@code kb_document.chunk_count}、{@code kb_space.doc_count}）
 * 做条件统计，不在 SQL 里写 {@code COUNT(DISTINCT ...)}，保持全项目「只用 LambdaQueryWrapper」的惯例。
 */
public record ChunkCorpusStatsVO(
        long totalChunks,
        long enabledChunks,
        long disabledChunks,
        long indexedDocuments,
        long activeSpaces
) {
}
