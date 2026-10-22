package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 知识库空间分页查询入参。
 */
public record SpacePageQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        Integer pageSize,

        /** 名称模糊关键字，可空 */
        String keyword
) {
}
