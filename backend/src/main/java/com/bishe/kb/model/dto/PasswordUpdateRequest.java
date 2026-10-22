package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改登录密码：必须验原密码，且新密码长度与注册时同规则（6-32 位）。
 *
 * <p>原密码校验失败按 4001 返回，与「账号或密码错误」的登录提示区分开——这里用户已登录，
 * 提示要具体到「原密码不对」才有意义。
 */
public record PasswordUpdateRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "新密码长度需为 6-32 位")
        String newPassword
) {
}
