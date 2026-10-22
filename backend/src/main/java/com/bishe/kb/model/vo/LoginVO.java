package com.bishe.kb.model.vo;

/**
 * 登录/注册成功后返回给前端的载荷：JWT 令牌 + 用户信息 + 过期时间（毫秒时间戳，供前端展示/预判）。
 */
public record LoginVO(String token, UserVO user, long expiresIn) {
}
