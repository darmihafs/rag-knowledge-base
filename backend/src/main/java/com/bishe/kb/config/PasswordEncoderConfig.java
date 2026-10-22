package com.bishe.kb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 只注册一个 {@link PasswordEncoder}（BCrypt）用于密码哈希与校验。
 *
 * <p>刻意不引入 spring-boot-starter-security：那会自动装配整套过滤器链、表单登录、CSRF 等，
 * 与本阶段手写的 {@link com.bishe.kb.security.AuthInterceptor} 职责重叠且难以协调。
 * 仅借用 spring-security-crypto 里的 BCrypt 实现即可。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
