package com.bishe.kb.model.vo;

/**
 * 分段视图对象：前端“查看文档分段”与联调核对用。
 *
 * <p>{@code pageNumber} 为 PDF 分段的起始页码（从 1 开始），非 PDF 或无页概念时为 null。
 */
public record ChunkVO(
        Long id,
        Long documentId,
        Integer chunkIndex,
        Integer pageNumber,
        Integer charCount,
        Integer enabled,
        String content
) {
}
