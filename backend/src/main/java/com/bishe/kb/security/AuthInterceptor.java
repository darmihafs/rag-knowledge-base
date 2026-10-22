package com.bishe.kb.security;

import com.bishe.kb.common.BizException;
import com.bishe.kb.common.Result;
import com.bishe.kb.config.AuthProperties;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.service.SysUserService;
import com.bishe.kb.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 全局认证 + RBAC 拦截器。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>放行 CORS 预检（OPTIONS）与白名单（登录 / 注册 / 健康检查）；</li>
 *   <li>解析 {@code Authorization: Bearer <token>}，校验并还原 {@link LoginUser} 写入 {@link UserContext}；
 *       缺失或非法一律 401。为让「禁用账号 / 退出后」立即失效，这里再回表确认账号仍启用；</li>
 *   <li>按角色做 RBAC：普通用户访问管理类接口直接 403。管理类接口按两种方式收敛——
 *       前缀（空间 / 文档 / 切片 / 大盘 / 账号 / 审计与配置）与精确路径（检索对比，
 *       因为它和问答同挂在 {@code /api/knowledge} 下，不能整段封）。
 *       前缀里再按精确路径开两个口子（文档预览、自己的可见空间列表），
 *       见 {@link #isUserCarveOut}：放开的是入口，不是数据。</li>
 * </ol>
 *
 * <p>{@code auth.test-bypass=true}（仅测试环境）时，缺失 token 的请求会被注入一个管理员身份放行，
 * 使第七阶段之前写下的、不带 token 的 H2 集成测试保持全绿；生产默认关闭。
 *
 * <p>失败响应用真实 HTTP 状态码（401/403）+ 统一 {@link Result} body，前端 axios 拦截器据状态码分流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 无需登录即可访问的路径（精确匹配） */
    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/health"
    );

    /** 仅管理员可访问的接口前缀：普通用户命中即 403 */
    private static final String[] ADMIN_ONLY_PREFIXES = {
            "/api/spaces",
            "/api/documents",
            "/api/chunks",
            "/api/dashboard",
            // 第十阶段后台管理：账号治理、审计日志、运行时参数配置
            "/api/users",
            "/api/admin"
    };

    /**
     * 仅管理员可访问的「精确路径」。
     *
     * <p>不能把 {@code /api/knowledge} 整体设为管理员专属——普通用户的核心功能（问答）就挂在这个前缀下。
     * 检索对比实验室会把全库切片内容成批展示出来，所以按完整路径单独收敛。
     */
    private static final Set<String> ADMIN_ONLY_PATHS = Set.of(
            "/api/knowledge/retrieve-compare"
    );

    /**
     * 管理员前缀下「普通用户也可访问」的精确路径。
     *
     * <p>{@code /api/spaces} 整体是管理员前缀，但普通用户问答时需要知道自己是哪些空间的成员，
     * 才能渲染空间选择器；{@code /visible} 只返回归属于自己的空间，越权面由
     * {@code SpaceAccessService} 在实现里收口，所以按精确路径单独放行——
     * 与 {@link #isDocumentPreview} 同一套「前缀封死 + 精确路径放行」的处理方式。
     */
    private static final Set<String> ADMIN_PREFIX_USER_PATHS = Set.of(
            "/api/spaces/visible"
    );

    /** 测试放行时注入的管理员身份，与 V4 种子的 admin(id=1) 对齐 */
    private static final LoginUser TEST_ADMIN = new LoginUser(1L, "admin", "系统管理员", SysUser.ROLE_ADMIN, 0);

    private final JwtUtil jwtUtil;
    private final SysUserService userService;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检不带业务头，直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (WHITELIST.contains(path)) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = resolveToken(header);

        // 无 token：测试环境放行并注入管理员，生产环境 401
        if (StringUtils.isBlank(token)) {
            if (authProperties.isTestBypass()) {
                UserContext.set(TEST_ADMIN);
                return true;
            }
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 4010, "未登录或登录已过期，请先登录");
            return false;
        }

        LoginUser loginUser;
        try {
            loginUser = jwtUtil.parse(token);
        } catch (BizException e) {
            // 4011 非法 / 4012 过期，都属于未认证，统一 401，业务码原样带回供前端区分文案
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getCode(), e.getMessage());
            return false;
        }

        // 回表确认账号仍存在且启用，让「禁用 / 删除」能即时生效（JWT 本身无法主动作废）
        SysUser dbUser = userService.findByUsername(loginUser.username());
        if (dbUser == null || !dbUser.isEnabled()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 4010, "账号不可用，请重新登录");
            return false;
        }
        // 令牌版本号失配 = 该 token 已被吊销：改密码、管理员重置密码、主动退出登录都会让版本号 +1，
        // 这是「JWT 无状态无法作废」的补位设计，见 sys_user.token_version（V7）
        if (loginUser.tokenVersion() != dbUser.tokenVersionOrDefault()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 4012, "登录状态已变更，请重新登录");
            return false;
        }
        // 以库里的最新角色为准，防止用户提权后旧 token 仍带旧角色
        LoginUser effective = new LoginUser(dbUser.getId(), dbUser.getUsername(),
                dbUser.getNickname(), dbUser.getRole(), dbUser.tokenVersionOrDefault());
        UserContext.set(effective);

        // RBAC：普通用户不得触碰管理类接口；但文档原文预览 /api/documents/{id}/preview 例外——
        // 普通用户点击回答引用需要查看来源原文，故对该路径豁免“仅管理员”，仍要求已登录。
        // 数据可见性不在这里收口，由 KbDocumentServiceImpl.preparePreview 按「仅已完成索引的文档」再判一次。
        if (!effective.isAdmin() && isAdminOnly(path) && !isUserCarveOut(path)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, 4030, "无权限访问该功能，仅管理员可用");
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程会被复用，务必清理，避免身份串号
        UserContext.clear();
    }

    /** 从 {@code Authorization: Bearer xxx} 中取出裸 token，格式不对返回 null */
    private String resolveToken(String header) {
        if (StringUtils.isBlank(header)) {
            return null;
        }
        String prefix = "Bearer ";
        if (header.startsWith(prefix)) {
            return header.substring(prefix.length()).trim();
        }
        // 容错：也接受不带 Bearer 前缀的裸 token
        return header.trim();
    }

    private boolean isAdminOnly(String path) {
        // 前缀命中即可；/api/knowledge 只能按精确路径放行，否则会把普通用户的问答一起挡掉
        if (ADMIN_ONLY_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : ADMIN_ONLY_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 管理员前缀下仍对登录用户开放的路径：文档原文预览 + 自己的可见空间列表。
     *
     * <p>两者都只是「放开功能入口」，数据可见性由服务层按各自的口径再判一次——
     * 预览走 {@code KbDocumentServiceImpl.requirePreviewVisible}（仅已完成索引的文档 + 空间可读），
     * {@code /api/spaces/visible} 走 {@code SpaceAccessServiceImpl.visibleSpaces}（只回自己有成员行的空间）。
     */
    private boolean isUserCarveOut(String path) {
        return (path.startsWith("/api/documents/") && path.endsWith("/preview"))
                || ADMIN_PREFIX_USER_PATHS.contains(path);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}
