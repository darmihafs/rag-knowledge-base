package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员处理工单：改状态 + 写处理备注。
 *
 * <p>{@code status} 取 0（退回待处理）/ 1（已修复）/ 2（已忽略）；
 * 服务层要求非 0 时必须填 {@code adminNote}，避免「点了已修复却不知道为什么」。
 */
public record FeedbackResolveRequest(

        @NotNull(message = "status 不能为空")
        @Min(value = 0, message = "status 只能取 0 / 1 / 2")
        @Max(value = 2, message = "status 只能取 0 / 1 / 2")
        Integer status,

        @Size(max = 500, message = "处理备注最多 500 字")
        String adminNote
) {
}
