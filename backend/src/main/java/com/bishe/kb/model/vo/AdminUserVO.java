package com.bishe.kb.model.vo;

import com.bishe.kb.model.entity.SysUser;

import java.time.LocalDateTime;

/**
 * 用户管理表格视图：比 {@link UserVO} 多状态、注册时间与活跃度，同样绝不含密码。
 *
 * <p>{@code sessionCount}/{@code questionCount} 由会话表与消息表按用户聚合得出，
 * 让管理员在禁用账号前能先看到「这个号到底在用吗」。
 */
public record AdminUserVO(
        Long id,
        String username,
        String nickname,
        String role,
        Integer status,
        LocalDateTime createTime,
        long sessionCount,
        long questionCount
) {

    public static AdminUserVO of(SysUser user, long sessionCount, long questionCount) {
        return new AdminUserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole(),
                user.getStatus(), user.getCreateTime(), sessionCount, questionCount);
    }
}
