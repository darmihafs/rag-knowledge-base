package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * BadCase 工单列表的分页查询入参。
 *
 * <p>三个筛选条件都可空：{@code rating}（1 赞 / -1 踩）、{@code status}（0 待处理 / 1 已修复 / 2 已忽略）、
 * {@code issueType}（四个归因之一）。默认按最新工单倒序。
 */
public record FeedbackPageQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        @Max(value = 100, message = "pageSize 最多 100")
        Integer pageSize,

        Integer rating,

        Integer status,

        String issueType
) {
}
