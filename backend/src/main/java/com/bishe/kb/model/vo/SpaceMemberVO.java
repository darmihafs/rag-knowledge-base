package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 空间成员条目：给管理员的「空间成员」面板用，比裸的 {@code kb_space_member} 多带用户可读信息。
 *
 * <p>{@code role} 是账号全局角色（ADMIN/USER），与空间成员身份是两个维度：ADMIN 不需要成员行
 * 就能看到全部空间，而成员行只决定「这个普通用户能不能读这个空间」。
 */
public record SpaceMemberVO(
        Long userId,
        String username,
        String nickname,
        String role,
        LocalDateTime grantTime
) {
}
