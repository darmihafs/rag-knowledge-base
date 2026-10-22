package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.dto.RegisterRequest;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.service.SpaceAccessService;
import com.bishe.kb.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现。
 *
 * <p>登录失败时统一返回「用户名或密码错误」，不区分是账号不存在还是密码不匹配，防止账号枚举。
 * 被禁用（status=0）的账号即使密码正确也拒绝登录。自助注册固定为 USER 角色，
 * 并自动获得默认知识库空间的成员资格（{@code SpaceAccessService#joinDefaultSpace}）——
 * 否则新账号一条授权都没有，问答会命中「可见空间为空 = 拒绝一切」，表现为问什么都没结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final SpaceAccessService spaceAccessService;

    @Override
    public SysUser findByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
    }

    @Override
    public SysUser authenticate(String username, String rawPassword) {
        SysUser user = findByUsername(username);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException(4001, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(4003, "账号已被禁用，请联系管理员");
        }
        return user;
    }

    @Override
    public SysUser register(RegisterRequest request) {
        String username = request.username().trim();
        if (findByUsername(username) != null) {
            throw new BizException(4002, "用户名已被占用");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.isBlank(request.nickname()) ? username : request.nickname().trim());
        user.setRole(SysUser.ROLE_USER);
        user.setStatus(SysUser.STATUS_ENABLED);
        save(user);
        // 新账号必须有一条成员行，否则「可见集合为空 = 拒绝一切」会把它关在库外：
        // 注册完问什么都没结果，看起来像检索坏了，其实是没人给他授权过
        spaceAccessService.joinDefaultSpace(user);
        log.info("新用户注册成功 id={} username={} role=USER", user.getId(), username);
        return user;
    }

    @Override
    public void invalidateTokens(Long userId) {
        if (userId == null) {
            return;
        }
        // 用 SQL 自增，不「读出来 +1 再写回」：两处并发时后者会覆盖前者，版本号就可能少加一次
        boolean applied = update(new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .setSql("token_version = token_version + 1"));
        log.info("吊销既有登录令牌 userId={} 生效={}", userId, applied);
    }
}
