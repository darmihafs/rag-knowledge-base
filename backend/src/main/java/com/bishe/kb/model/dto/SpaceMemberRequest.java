package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 空间成员授权请求体：{@code POST /api/spaces/{id}/members}。
 *
 * <p>收的是用户 ID 列表而不是用户名——用户管理页与授权面板拿到的都是 ID，
 * 按用户名授权会引入「同名 / 改名后授权丢失」这种更难查的问题。
 */
public record SpaceMemberRequest(
        @NotEmpty(message = "请至少选择一个用户")
        @Size(max = 200, message = "单次最多授权 200 个账号")
        List<Long> userIds
) {
}
