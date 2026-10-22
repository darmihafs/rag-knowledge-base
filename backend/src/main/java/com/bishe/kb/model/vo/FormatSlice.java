package com.bishe.kb.model.vo;

/**
 * 文档格式分布饼图的一个扇区。type 为文件后缀（pdf/docx/md...），count 为该格式文档数。
 */
public record FormatSlice(
        String type,
        long count
) {
}
