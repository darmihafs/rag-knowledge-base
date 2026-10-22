package com.bishe.kb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.RegisterRequest;
import com.bishe.kb.model.entity.SysUser;

/**
 * 用户服务：登录校验、自助注册、按用户名查询。密码哈希统一走注入的 {@code PasswordEncoder}。
 */
public interface SysUserService extends IService<SysUser> {

    /** 按用户名查询（自动过滤逻辑删除），不存在返回 null */
    SysUser findByUsername(String username);

    /**
     * 校验用户名密码：成功返回用户，失败抛业务异常。
     * 用户名不存在与密码错误返回同一提示，避免账号枚举。
     */
    SysUser authenticate(String username, String rawPassword);

    /** 自助注册：默认 USER 角色，用户名重复抛业务异常，返回新建用户 */
    SysUser register(RegisterRequest request);

    /**
     * 吊销该用户此前签发的全部 token：把 {@code sys_user.token_version} 加一，
     * 拦截器发现令牌里的版本号与库中不一致即按未登录处理（HTTP 401）。
     *
     * <p>改密码、管理员重置密码、主动退出登录都走这里。用户需要重新登录才能继续使用。
     */
    void invalidateTokens(Long userId);
}
