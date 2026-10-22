package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 切换用户角色入参：{@code role} 取 ADMIN 或 USER。
 */
public record RoleUpdateRequest(

        @NotBlank(message = "role 不能为空")
        String role
) {
}
