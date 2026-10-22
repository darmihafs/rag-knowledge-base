package com.bishe.kb.security;

/**
 * 当前登录用户的最小上下文快照，由 {@link com.bishe.kb.util.JwtUtil} 从 token 解析得到，
 * 存放在 {@link UserContext} 的 ThreadLocal 里，供 Service 层做数据隔离与 RBAC 判断。
 *
 * <p>刻意不含 password 等敏感字段。
 *
 * <p>{@code tokenVersion} 是签发时的令牌版本号，拦截器会拿它与库中当前值比对，
 * 用于「改密码 / 管理员重置 / 主动退出」后立即吊销此前签发的 token。
 */
public record LoginUser(Long id, String username, String nickname, String role, int tokenVersion) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
