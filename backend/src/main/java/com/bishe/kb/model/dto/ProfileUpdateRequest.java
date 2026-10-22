package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料：目前只放开昵称。
 *
 * <p>用户名与角色不接受自助修改——用户名是登录凭证和会话归属的锚点，角色涉及 RBAC，
 * 只能由管理员在「用户与权限管理」里变更。
 */
public record ProfileUpdateRequest(

        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最多 32 字")
        String nickname
) {
}
