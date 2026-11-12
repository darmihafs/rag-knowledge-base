package com.bishe.kb.cache;

/**
 * 鉴权与权限边界缓存的 Redis Key 约定，集中一处避免各调用方拼串不一致。
 *
 * <ul>
 *   <li>{@code auth:token_ver:{userId}} —— 用户当前令牌版本号，拦截器据此短路吊销旧 token；</li>
 *   <li>{@code auth:spaces:{userId}} —— 用户可见知识库空间 ID 集合（JSON 数组），越权判定据此短路。</li>
 * </ul>
 *
 * <p>两者都在改密 / 退出 / 重置密码（版本号）或空间成员变更（集合）时主动淘汰，TTL 兜底 2 小时。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** 令牌版本号缓存 Key */
    public static String tokenVersion(Long userId) {
        return "auth:token_ver:" + userId;
    }

    /** 可见空间集合缓存 Key */
    public static String spaces(Long userId) {
        return "auth:spaces:" + userId;
    }

    /**
     * 登录连续失败计数 Key（防爆破）：按用户名小写归一，固定窗口 15 分钟，
     * 达上限后登录入口直接拒绝。与上面两个鉴权缓存不同，这里存的是计数而非身份数据。
     */
    public static String loginFailures(String normalizedUsername) {
        return "auth:login_fail:" + normalizedUsername;
    }
}