package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 全局切片语料中心的分页查询入参：跨文档检索全库切片。
 *
 * <p>与 {@link ChunkPageQuery}（限定单个文档）的区别是这里四个条件可任意组合：
 * {@code spaceId} 由服务层先解析成该空间下的文档 ID 集合再过滤，
 * {@code enabled} 为 null 表示不限状态，{@code keyword} 匹配切片正文。
 */
public record ChunkCorpusQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        @Max(value = 100, message = "pageSize 最多 100")
        Integer pageSize,

        /** 按知识库空间过滤，可空表示全部空间 */
        Long spaceId,

        /** 按所属文档过滤，可空表示全部文档 */
        Long documentId,

        /** 启用状态：1=只看在检索、0=只看被禁用，null=不限 */
        Integer enabled,

        /** 切片正文模糊关键字，可空 */
        String keyword
) {
}
