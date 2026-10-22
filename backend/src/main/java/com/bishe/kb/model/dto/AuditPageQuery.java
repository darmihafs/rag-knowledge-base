package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 全站问答审计日志的分页查询入参。
 *
 * <p>审计以「一次提问」为一行：{@code keyword} 模糊匹配问题正文，
 * {@code username} 精确匹配提问用户（后端先按用户名定位其会话，再过滤消息）。
 */
public record AuditPageQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        @Max(value = 100, message = "pageSize 最多 100")
        Integer pageSize,

        /** 问题关键字，可空 */
        String keyword,

        /** 提问用户名（精确），可空 */
        String username
) {
}
