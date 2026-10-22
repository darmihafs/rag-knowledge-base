package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量启用 / 禁用切片：一次勾选多条，统一改成同一状态。
 *
 * <p>{@code enabled} 只接受 1 或 0（不做「切换」语义，避免批量时每条结果方向不一致，
 * 前端也无法确定地回显）。单条切换走 {@code PUT /api/chunks/{id}/toggle-status}。
 */
public record ChunkBatchStatusRequest(

        @NotEmpty(message = "请至少选择一条切片")
        List<Long> ids,

        @NotNull(message = "enabled 不能为空")
        @Min(value = 0, message = "enabled 只能取 0 或 1")
        @Max(value = 1, message = "enabled 只能取 0 或 1")
        Integer enabled
) {
}
