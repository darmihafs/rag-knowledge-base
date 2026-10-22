package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.ChatSessionMapper;
import com.bishe.kb.model.dto.UserCreateRequest;
import com.bishe.kb.model.dto.UserPageQuery;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.AdminUserVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.service.SpaceAccessService;
import com.bishe.kb.service.SysUserService;
import com.bishe.kb.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号治理实现。
 *
 * <p>三条硬规矩：
 * <ol>
 *   <li>管理员不能动自己——降级或禁用自己会把系统锁死在无人可管理的状态；</li>
 *   <li>系统必须至少保留一名「启用中的管理员」，最后一名 ADMIN 既不能被降级也不能被禁用；</li>
 *   <li>用户名唯一性先查再插，并兜底捕获唯一索引冲突：逻辑删除的用户仍占着 uk_username 索引，
 *       只靠 {@code findByUsername}（自动过滤 deleted）查不出来。</li>
 * </ol>
 *
 * <p>新建 USER 账号、以及把 ADMIN 降级成 USER，都会补一条默认知识库空间的成员行
 *   （{@link SpaceAccessService#joinDefaultSpace}）——权限收紧之后，没有成员行就等于问什么都没结果，
 *   这类「看起来像检索坏了」的问题不该留给管理员去猜。
 *
 * <p>活跃度两列（会话数 / 提问数）按当页用户批量聚合，避免逐行查询产生 N+1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminServiceImpl implements UserAdminService {

    /** 管理员重置密码后的初始密码，用户可自行修改 */
    private static final String DEFAULT_PASSWORD = "123456";

    private final SysUserService userService;
    private final SpaceAccessService spaceAccessService;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Page<AdminUserVO> pageUsers(UserPageQuery query) {
        String keyword = StringUtils.trimToNull(query.keyword());
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .and(keyword != null, w -> w.like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getNickname, keyword))
                .eq(StringUtils.isNotBlank(query.role()), SysUser::getRole, StringUtils.upperCase(query.role()))
                .eq(query.status() != null, SysUser::getStatus, query.status())
                .orderByDesc(SysUser::getId);

        Page<SysUser> source = userService.page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<Long> userIds = source.getRecords().stream().map(SysUser::getId).toList();
        Map<Long, Long> sessionCounts = new HashMap<>();
        Map<Long, Long> questionCounts = new HashMap<>();
        countActivity(userIds, sessionCounts, questionCounts);

        Page<AdminUserVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(source.getRecords().stream()
                .map(u -> AdminUserVO.of(u, sessionCounts.getOrDefault(u.getId(), 0L),
                        questionCounts.getOrDefault(u.getId(), 0L)))
                .toList());
        return result;
    }

    @Override
    public AdminUserVO createUser(UserCreateRequest request) {
        String username = StringUtils.trimToEmpty(request.username());
        if (userService.findByUsername(username) != null) {
            throw new BizException(4002, "用户名已被占用");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.isBlank(request.nickname()) ? username : request.nickname().trim());
        user.setRole(normalizeRole(request.role()));
        user.setStatus(SysUser.STATUS_ENABLED);
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 命中 uk_username：多半是与已逻辑删除的账号重名
            throw new BizException(4002, "用户名已被占用（可能是历史已删除账号）");
        }
        spaceAccessService.joinDefaultSpace(user);
        log.info("管理员新建账号 id={} username={} role={}", user.getId(), username, user.getRole());
        return AdminUserVO.of(user, 0L, 0L);
    }

    @Override
    public AdminUserVO updateRole(Long userId, String role, LoginUser operator) {
        SysUser user = requireUser(userId);
        String target = normalizeRole(role);
        if (target.equals(user.getRole())) {
            return AdminUserVO.of(user, 0L, 0L);
        }
        if (user.getId().equals(operator.id())) {
            throw new BizException(4005, "不能修改自己的角色，请由另一名管理员操作");
        }
        if (user.isAdmin() && isLastAdmin(user.getId())) {
            throw new BizException(4006, "系统至少要保留一名管理员");
        }
        String from = user.getRole();
        user.setRole(target);
        userService.updateById(user);
        // 降级成 USER 时补一条默认空间授权：ADMIN 本来绕过成员表，降下来却没有成员行，就什么都召不回了
        // （升级成 ADMIN 时 joinDefaultSpace 内部直接跳过，不会插出没意义的成员行）
        spaceAccessService.joinDefaultSpace(user);
        log.info("账号角色变更 id={} {} -> {}，操作人={}", userId, from, target, operator.username());
        return AdminUserVO.of(user, 0L, 0L);
    }

    @Override
    public AdminUserVO updateStatus(Long userId, Integer status, LoginUser operator) {
        if (status == null || (status != SysUser.STATUS_ENABLED && status != SysUser.STATUS_DISABLED)) {
            throw new BizException(4004, "status 只能取 1（启用）或 0（禁用）");
        }
        SysUser user = requireUser(userId);
        if (status == SysUser.STATUS_DISABLED) {
            if (user.getId().equals(operator.id())) {
                throw new BizException(4005, "不能禁用当前登录的账号");
            }
            if (user.isAdmin() && isLastAdmin(user.getId())) {
                throw new BizException(4006, "系统至少要保留一名启用中的管理员");
            }
        }
        user.setStatus(status);
        userService.updateById(user);
        log.info("账号状态变更 id={} status={}，操作人={}", userId, status, operator.username());
        return AdminUserVO.of(user, 0L, 0L);
    }

    @Override
    public String resetPassword(Long userId) {
        SysUser user = requireUser(userId);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        userService.updateById(user);
        // 被重置说明账号可能已失控：连同此前签发的 token 一起吊销，逼对方用新密码重新登录
        userService.invalidateTokens(user.getId());
        log.info("管理员重置密码 id={} username={}，已重置为系统初始密码并吊销旧 token", userId, user.getUsername());
        return DEFAULT_PASSWORD;
    }

    // ------------------------------------------------------------------ 内部工具

    private SysUser requireUser(Long userId) {
        SysUser user = userId == null ? null : userService.getById(userId);
        if (user == null) {
            throw new BizException(4040, "用户不存在或已被删除");
        }
        return user;
    }

    private String normalizeRole(String role) {
        String target = StringUtils.upperCase(StringUtils.trimToEmpty(role));
        if (target.isEmpty()) {
            return SysUser.ROLE_USER;
        }
        if (!SysUser.ROLE_ADMIN.equals(target) && !SysUser.ROLE_USER.equals(target)) {
            throw new BizException(4004, "角色只能取 ADMIN 或 USER");
        }
        return target;
    }

    /** 除 {@code excludeId} 之外，是否还有启用中的管理员；没有就说明他是最后一名 */
    private boolean isLastAdmin(Long excludeId) {
        long remaining = userService.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, SysUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, SysUser.STATUS_ENABLED)
                .ne(SysUser::getId, excludeId));
        return remaining == 0;
    }

    /**
     * 按当页用户批量统计会话数与提问数：两次查询（会话、用户提问）在内存里按 userId 归并，
     * 不在 SQL 里做跨库方言敏感的 join + 聚合。
     */
    private void countActivity(List<Long> userIds, Map<Long, Long> sessionCounts, Map<Long, Long> questionCounts) {
        if (userIds.isEmpty()) {
            return;
        }
        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .select(ChatSession::getId, ChatSession::getUserId)
                .in(ChatSession::getUserId, userIds));
        for (ChatSession session : sessions) {
            sessionCounts.merge(session.getUserId(), 1L, Long::sum);
        }
        List<Long> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        if (sessionIds.isEmpty()) {
            return;
        }
        Map<Long, Long> perSession = new HashMap<>();
        messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .select(ChatMessage::getSessionId)
                        .in(ChatMessage::getSessionId, sessionIds)
                        .eq(ChatMessage::getRole, ChatMessage.ROLE_USER))
                .forEach(m -> perSession.merge(m.getSessionId(), 1L, Long::sum));
        sessions.forEach(s -> questionCounts.merge(s.getUserId(), perSession.getOrDefault(s.getId(), 0L), Long::sum));
    }
}
