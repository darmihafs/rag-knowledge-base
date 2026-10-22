package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求体：{@code POST /api/auth/register}。注册出来的账号一律是普通用户（USER）。
 *
 * <p>用户名限定字母/数字/下划线，避免中文或特殊字符带来的唯一索引与展示问题。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名为 3-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需为 6-32 位")
        String password,

        @Size(max = 32, message = "昵称过长")
        String nickname
) {
}
