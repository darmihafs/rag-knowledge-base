package com.bishe.kb.util;

import com.bishe.kb.common.BizException;
import com.bishe.kb.config.AuthProperties;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发与校验令牌。
 *
 * <p>采用 jjwt 0.12.x 的新式 API（{@code Jwts.builder().signWith(key)} /
 * {@code Jwts.parser().verifyWith(key)}）。签名算法由密钥长度自动选择（HS256/384/512），
 * 密钥至少 32 字节。<b>密钥没有任何默认值</b>：{@code auth.secret} 为空时本类构造即失败、
 * 服务拒绝启动，避免「忘配环境变量却用代码里的出厂密钥跑起来了」这种最危险的失误。
 *
 * <p>令牌里带 {@code uid / username / nickname / role / tv} 五个载荷，校验通过后直接还原成
 * {@link LoginUser}，无需每次请求都回表查库；账号是否被禁用、令牌版本号是否已吊销，
 * 则由拦截器按需查库确认。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(AuthProperties props) {
        String secret = props.getSecret();
        if (StringUtils.isBlank(secret)) {
            // 绝不回退到内置默认密钥：密钥一旦写死在代码里，任何人拿到源码就能伪造任意身份的 token。
            // 本地开发写在 gitignore 的 application-local.yml，服务器用环境变量 AUTH_JWT_SECRET。
            throw new IllegalStateException("""
                    未配置 JWT 签名密钥，服务拒绝启动。请设置环境变量 AUTH_JWT_SECRET，\
                    或在 backend/src/main/resources/application-local.yml 里补：
                      auth:
                        secret: <至少 32 字节的随机串>
                    """);
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("auth.secret 至少需要 32 字节，当前 " + secretBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttlMillis = props.getExpireMinutes() * 60_000L;
    }

    /** 依据用户信息签发 token */
    public String generateToken(SysUser user) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlMillis);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole())
                .claim("nickname", user.getNickname())
                // tv = 令牌版本号：账号发生「改密 / 被重置 / 退出」后该值递增，旧 token 随即失配
                .claim("tv", user.tokenVersionOrDefault())
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token，成功返回 {@link LoginUser}。
     *
     * @throws BizException 4011 令牌非法 / 4012 令牌过期
     */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object uid = claims.get("uid");
            Long id = uid instanceof Number n ? n.longValue() : null;
            String role = claims.get("role", String.class);
            String nickname = claims.get("nickname", String.class);
            // 早期签发的 token 没有 tv，按 0 处理；升级后用户重新登录即可拿到带版本号的 token
            Object tv = claims.get("tv");
            int tokenVersion = tv instanceof Number n ? n.intValue() : 0;
            return new LoginUser(id, claims.getSubject(), nickname, role, tokenVersion);
        } catch (ExpiredJwtException e) {
            throw new BizException(4012, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 校验失败: {}", e.getMessage());
            throw new BizException(4011, "登录状态无效，请重新登录");
        }
    }
}
