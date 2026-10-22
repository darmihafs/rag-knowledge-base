package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.RoleUpdateRequest;
import com.bishe.kb.model.dto.StatusUpdateRequest;
import com.bishe.kb.model.dto.UserCreateRequest;
import com.bishe.kb.model.dto.UserPageQuery;
import com.bishe.kb.model.vo.AdminUserVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户与权限管理接口（整个 {@code /api/users} 前缀在 AuthInterceptor 里已锁为仅管理员）。
 *
 * <ul>
 *   <li>{@code GET  /api/users}                       —— 分页 + 关键词/角色/状态过滤</li>
 *   <li>{@code POST /api/users}                       —— 新建账号</li>
 *   <li>{@code PUT  /api/users/{id}/role}             —— 切换 ADMIN / USER</li>
 *   <li>{@code PUT  /api/users/{id}/status}           —— 启用 / 禁用</li>
 *   <li>{@code PUT  /api/users/{id}/reset-password}   —— 重置为系统初始密码</li>
 * </ul>
 *
 * <p>自我保护规则（不能降级/禁用当前登录管理员、系统至少保留一名启用管理员）统一在
 * {@link UserAdminService} 里校验，本类只负责把当前登录人作为操作者传进去。
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAdminService userAdminService;

    @GetMapping
    public Result<Page<AdminUserVO>> page(@Valid UserPageQuery query) {
        return Result.ok(userAdminService.pageUsers(query));
    }

    @PostMapping
    public Result<AdminUserVO> create(@Valid @RequestBody UserCreateRequest request) {
        LoginUser operator = UserContext.require();
        log.info("管理员建号 operator={} username={}", operator.username(), request.username());
        return Result.ok(userAdminService.createUser(request));
    }

    @PutMapping("/{id}/role")
    public Result<AdminUserVO> updateRole(@PathVariable Long id,
                                          @Valid @RequestBody RoleUpdateRequest request) {
        return Result.ok(userAdminService.updateRole(id, request.role(), UserContext.require()));
    }

    @PutMapping("/{id}/status")
    public Result<AdminUserVO> updateStatus(@PathVariable Long id,
                                            @Valid @RequestBody StatusUpdateRequest request) {
        return Result.ok(userAdminService.updateStatus(id, request.status(), UserContext.require()));
    }

    /** 返回重置后的明文初始密码，供管理员转告本人；库裡仍然只存 BCrypt 密文 */
    @PutMapping("/{id}/reset-password")
    public Result<String> resetPassword(@PathVariable Long id) {
        LoginUser operator = UserContext.require();
        log.info("管理员重置密码 operator={} targetId={}", operator.username(), id);
        return Result.ok(userAdminService.resetPassword(id));
    }
}
