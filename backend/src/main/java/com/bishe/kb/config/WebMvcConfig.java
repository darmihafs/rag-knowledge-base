package com.bishe.kb.config;

import com.bishe.kb.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：开发期跨域 + 全局认证/RBAC 拦截器注册。
 *
 * <p>生产环境应由 Nginx 同域转发或把 allowedOriginPatterns 收敛到具体域名。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Content-Range：前端按分段读取原文预览时需要读到它才知道总长与区间
                .exposedHeaders("Content-Disposition", "Content-Range", "Accept-Ranges")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 认证拦截器覆盖所有 /api/** 请求；白名单（登录/注册/健康检查）与 CORS 预检在拦截器内部放行。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
    }
}

