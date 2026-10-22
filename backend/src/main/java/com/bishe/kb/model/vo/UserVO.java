package com.bishe.kb.model.vo;

import com.bishe.kb.model.entity.SysUser;

/**
 * 对外的用户信息视图：绝不含 password。
 */
public record UserVO(Long id, String username, String nickname, String role) {

    public static UserVO from(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }
}
