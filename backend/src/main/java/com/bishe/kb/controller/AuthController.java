package com.bishe.kb.controller;

import com.bishe.kb.common.BizException;
import com.bishe.kb.common.Result;
import com.bishe.kb.config.AuthProperties;
import com.bishe.kb.model.dto.LoginRequest;
import com.bishe.kb.model.dto.PasswordUpdateRequest;
import com.bishe.kb.model.dto.ProfileUpdateRequest;
import com.bishe.kb.model.dto.RegisterRequest;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.LoginVO;
import com.bishe.kb.model.vo.MyStatsVO;
import com.bishe.kb.model.vo.UserVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.SysUserService;
import com.bishe.kb.service.UserProfileService;
import com.bishe.kb.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与个人中心接口。
 *
 * <ul>
 *   <li>{@code POST /api/auth/login}      —— 账号密码登录，成功返回 JWT + 用户信息（白名单，无需 token）</li>
 *   <li>{@code POST /api/auth/register}   —— 自助注册，默认普通用户（白名单，无需 token）</li>
 *   <li>{@code GET  /api/auth/me}         —— 回显当前登录用户（需 token）</li>
 *   <li>{@code POST /api/auth/logout}     —— 退出登录（需 token）；服务端递增令牌版本号，真正作废该用户此前签发的全部 token，前端再清掉本地副本</li>
 *   <li>{@code PUT  /api/auth/profile}    —— 修改本人昵称（需 token）</li>
 *   <li>{@code PUT  /api/auth/password}   —— 修改本人密码，需验原密码（需 token）</li>
 *   <li>{@code GET  /api/auth/my-stats}   —— 本人使用统计（需 token）</li>
 * </ul>
 *
 * <p>后三个是个人中心入口，ADMIN 与 USER 都可访问：普通学生登录后至少有「AI 智能问答台」和
 * 「个人中心」两块地方可去，而不是只有一个孤零零的问答页。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService userService;
    private final UserProfileService profileService;
    private final JwtUtil jwtUtil;
    private final AuthProperties authProperties;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        SysUser user = userService.authenticate(request.username(), request.password());
        String token = jwtUtil.generateToken(user);
        log.info("用户登录成功 id={} username={} role={}", user.getId(), user.getUsername(), user.getRole());
        return Result.ok(new LoginVO(token, UserVO.from(user), authProperties.getExpireMinutes() * 60_000L));
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        SysUser user = userService.register(request);
        // 注册即登录，省去用户再输一遍账号密码
        String token = jwtUtil.generateToken(user);
        return Result.ok(new LoginVO(token, UserVO.from(user), authProperties.getExpireMinutes() * 60_000L));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        LoginUser current = UserContext.require();
        SysUser user = userService.findByUsername(current.username());
        if (user == null || !user.isEnabled()) {
            throw new BizException(4010, "登录状态已失效，请重新登录");
        }
        return Result.ok(UserVO.from(user));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginUser current = UserContext.get();
        // 前端删本地 token 只是「本地登出」；这里递增令牌版本号，让已被窃取的 token 在服务端真正作废
        profileService.logout(current);
        log.info("用户退出登录 username={}", current == null ? "unknown" : current.username());
        return Result.ok();
    }

    // ---------------------------------------------------------------- 个人中心

    /** 改昵称：返回最新用户信息，前端直接刷新本地缓存，省一次 /me */
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return Result.ok(profileService.updateProfile(request.nickname(), UserContext.require()));
    }

    /** 改密码：成功后令牌版本号递增，手里的旧 token 立即失效，前端清态并跳登录页重新登录 */
    @PutMapping("/password")
    public Result<Void> updatePassword(@Valid @RequestBody PasswordUpdateRequest request) {
        profileService.changePassword(request, UserContext.require());
        return Result.ok();
    }

    @GetMapping("/my-stats")
    public Result<MyStatsVO> myStats() {
        return Result.ok(profileService.myStats(UserContext.require()));
    }
}
