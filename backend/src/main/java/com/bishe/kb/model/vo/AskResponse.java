package com.bishe.kb.model.vo;

import java.util.List;

/**
 * 问答响应：答案正文 + 引用片段（前端展示“来源出处”用）。
 */
public record AskResponse(
        String answer,
        List<Citation> citations,
        long elapsedMillis
) {

    /**
     * @param documentId 来源文档 ID
     * @param title      来源文档标题
     * @param chunkIndex 分段序号
     * @param pageNumber PDF 命中片段的起始页码（从 1 开始）；非 PDF 或无页码为 null，供前端 #page 跳转
     * @param snippet    命中片段摘要
     * @param score      相似度得分，可为空（关键词召回时没有得分）
     */
    public record Citation(
            Long documentId,
            String title,
            Integer chunkIndex,
            Integer pageNumber,
            String snippet,
            Double score
    ) {
    }

    public static AskResponse empty(String reason) {
        return new AskResponse(reason, List.of(), 0L);
    }
}
