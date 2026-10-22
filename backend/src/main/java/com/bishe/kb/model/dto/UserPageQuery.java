package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 用户管理分页查询入参。
 *
 * <p>{@code keyword} 同时模糊匹配用户名与昵称；{@code role}/{@code status} 为空表示不做该维度过滤。
 */
public record UserPageQuery(

        @NotNull(message = "pageNum 不能为空")
        @Min(value = 1, message = "pageNum 从 1 开始")
        Integer pageNum,

        @NotNull(message = "pageSize 不能为空")
        @Min(value = 1, message = "pageSize 至少为 1")
        @Max(value = 100, message = "pageSize 最多 100")
        Integer pageSize,

        String keyword,

        /** ADMIN / USER，空=全部 */
        String role,

        /** 1=启用 0=禁用，空=全部 */
        Integer status
) {
}
