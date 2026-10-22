package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 文档列表分页查询入参。
 */
public record DocumentPageQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        Integer pageSize,

        /** 标题模糊关键字，可空 */
        String keyword,

        /** 按知识库空间过滤，可空表示全部空间 */
        Long spaceId
) {
}
