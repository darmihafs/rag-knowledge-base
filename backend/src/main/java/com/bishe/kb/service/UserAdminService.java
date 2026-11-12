package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.model.dto.UserCreateRequest;
import com.bishe.kb.model.dto.UserPageQuery;
import com.bishe.kb.model.vo.AdminUserVO;
import com.bishe.kb.security.LoginUser;

/**
 * 管理员侧的账号治理：分页检索、建号、切角色、启停用、重置密码。
 *
 * <p>与 {@code SysUserService}（登录/注册，面向所有用户）分开，是为了把「改别人」的危险操作
 * 集中在一处做自我保护校验，避免认证服务被管理功能污染。
 */
public interface UserAdminService {

    Page<AdminUserVO> pageUsers(UserPageQuery query);

    AdminUserVO createUser(UserCreateRequest request);

    /**
     * 切换角色。禁止管理员把自己降级，也不允许把系统里最后一名管理员移走。
     *
     * @param operator 当前登录管理员，用于自我保护判定
     */
    AdminUserVO updateRole(Long userId, String role, LoginUser operator);

    /** 启用/禁用账号；不能禁用自己，也不能禁掉最后一名管理员 */
    AdminUserVO updateStatus(Long userId, Integer status, LoginUser operator);

    /**
     * 重置密码为系统默认初始密码，并吊销该账号既有令牌。
     * 响应只回脱敏提示，不再明文回传固定初始密码；用户登录后可自行改密，系统不存任何可逆密码。
     */
    String resetPassword(Long userId);
}
