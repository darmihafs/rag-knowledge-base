package com.bishe.kb.security;

import com.bishe.kb.common.BizException;

/**
 * 当前请求的登录用户上下文（ThreadLocal）。
 *
 * <p>由 {@link com.bishe.kb.security.AuthInterceptor} 在 preHandle 写入、afterCompletion 清理，
 * 业务代码通过 {@link #get()} / {@link #currentUserId()} 读取，避免把用户信息层层透传到方法签名。
 *
 * <p>务必成对 set/clear：Tomcat 线程会被复用，漏清理会让下一个请求读到上一个用户的身份。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 可能为 null（白名单接口或未认证） */
    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前用户 ID；未登录抛 401，供需要归属的业务（建会话等）调用 */
    public static Long currentUserId() {
        LoginUser user = HOLDER.get();
        if (user == null || user.id() == null) {
            throw new BizException(4010, "未登录或登录已过期");
        }
        return user.id();
    }

    /** 取当前登录用户；未登录抛 401 */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(4010, "未登录或登录已过期");
        }
        return user;
    }

    public static boolean isAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.isAdmin();
    }
}
