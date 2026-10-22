package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用/禁用账号入参：{@code status} 取 1（启用）或 0（禁用）。
 */
public record StatusUpdateRequest(

        @NotNull(message = "status 不能为空")
        @Min(value = 0, message = "status 只能是 0 或 1")
        @Max(value = 1, message = "status 只能是 0 或 1")
        Integer status
) {
}
