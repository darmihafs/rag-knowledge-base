package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 全局语料中心的一行切片：在 {@link ChunkVO} 之外补齐「它属于哪篇文档、哪个空间」。
 *
 * <p>跨文档列表必须能一眼看出来源，否则管理员无法判断该禁用哪条语料。
 * 文档可能已被删除（逻辑删除），此时 {@code documentTitle} 回退为「文档已删除」，
 * 保证列表不因反查失败而丢行。
 */
public record CorpusChunkVO(
        Long id,
        Long documentId,
        String documentTitle,
        Long spaceId,
        String spaceName,
        String fileType,
        Integer chunkIndex,
        Integer pageNumber,
        Integer charCount,
        Integer enabled,
        String content,
        LocalDateTime createTime
) {

    /** 反查不到文档时的兜底标题 */
    public static final String MISSING_DOCUMENT = "文档已删除";
    /** 反查不到空间时的兜底名称 */
    public static final String MISSING_SPACE = "未归属空间";
}
