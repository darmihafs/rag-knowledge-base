package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员新建账号入参。
 *
 * <p>密码在 Service 层统一走 BCrypt 编码后才落库，本对象只在内存里短暂持有明文。
 * {@code role} 允许 ADMIN / USER，为空按普通用户处理。
 */
public record UserCreateRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3~32 之间")
        String username,

        @NotBlank(message = "初始密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
        String password,

        @Size(max = 64, message = "昵称最长 64 字")
        String nickname,

        String role
) {
}
