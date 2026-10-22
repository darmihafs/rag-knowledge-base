package com.bishe.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置，映射 application.yml 里的 {@code auth.*}。
 *
 * <p>{@code testBypass} 仅供测试环境：为 true 时，缺失 Authorization 头的请求会被放行并
 * 自动注入一个管理员身份，从而让第七阶段之前写下的、不带 token 的 H2 集成测试保持全绿。
 * 生产环境必须为 false（默认值即 false）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * JWT 签名密钥（HS256 要求至少 32 字节）。
     *
     * <p><b>故意没有默认值</b>：出厂密钥等于把「谁能登录成管理员」写进了公开仓库。
     * 本地开发写在 git-ignored 的 {@code application-local.yml}，服务器用环境变量
     * {@code AUTH_JWT_SECRET}；两者都没有时 {@link com.bishe.kb.util.JwtUtil} 会让启动直接失败。
     */
    private String secret;

    /** token 有效期（分钟），默认 12 小时；吊销能力由 sys_user.token_version 提供 */
    private long expireMinutes = 720;

    /** 测试放行开关：仅 test profile 打开，详见类注释 */
    private boolean testBypass = false;
}
