package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.KbSpaceMapper;
import com.bishe.kb.mapper.KbSpaceMemberMapper;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.entity.KbSpaceMember;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.SpaceMemberVO;
import com.bishe.kb.model.vo.SpaceVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.KbSpaceService;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.SpaceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 空间授权实现。
 *
 * <p>鉴权发生在<b>检索之前</b>而不是过滤在召回之后：可见空间集合会拼进召回 SQL 的子查询，
 * 未授权空间的切片根本不进候选池。若在内存里过滤，topK 会被越权切片占满，
 * 「隔离」就变成了「偶尔生效」。
 *
 * <p>每次请求查一次成员表（走 {@code idx_member_user} 索引）。没有加缓存是刻意的：
 * 授权关系必须立刻生效，撤销权限后还能检索到旧内容，比多一次主键索引查询严重得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpaceAccessServiceImpl implements SpaceAccessService {

    private final KbSpaceMemberMapper memberMapper;
    private final KbSpaceMapper spaceMapper;
    private final SysUserMapper userMapper;

    @Override
    public List<Long> currentVisibleSpaceIds() {
        LoginUser current = UserContext.get();
        // 没有上下文只可能是内部跑批/评测直调检索，按不限制处理；HTTP 请求由拦截器保证有身份
        if (current == null || current.isAdmin()) {
            return null;
        }
        return memberMapper.selectList(new LambdaQueryWrapper<KbSpaceMember>()
                        .select(KbSpaceMember::getSpaceId)
                        .eq(KbSpaceMember::getUserId, current.id()))
                .stream().map(KbSpaceMember::getSpaceId).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    public List<SpaceVO> visibleSpaces() {
        List<Long> ids = currentVisibleSpaceIds();
        if (ids != null) {
            // 可见集合为空时必须直接返回空列表：MyBatis-Plus 的 in(空集合) 会被条件忽略，
            // 少这一层判断就等于「没授权 = 看全部」
            if (ids.isEmpty()) {
                return List.of();
            }
            return spaceMapper.selectList(new LambdaQueryWrapper<KbSpace>()
                    .in(KbSpace::getId, ids)
                    .orderByAsc(KbSpace::getId)).stream().map(this::toVo).toList();
        }
        return spaceMapper.selectList(new LambdaQueryWrapper<KbSpace>().orderByAsc(KbSpace::getId))
                .stream().map(this::toVo).toList();
    }

    @Override
    public RetrievalScope scopeForCurrentUser(Long requestedSpaceId) {
        Long spaceId = requestedSpaceId == null || requestedSpaceId <= 0 ? null : requestedSpaceId;
        if (spaceId != null) {
            requireReadable(spaceId);
            return RetrievalScope.ofSpace(spaceId);
        }
        List<Long> visible = currentVisibleSpaceIds();
        return visible == null ? RetrievalScope.unrestricted() : RetrievalScope.visible(visible);
    }

    @Override
    public void requireReadable(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            throw new BizException(4030, "知识库空间参数非法");
        }
        List<Long> visible = currentVisibleSpaceIds();
        if (visible == null) {
            return;
        }
        if (!visible.contains(spaceId)) {
            log.warn("越权访问知识库空间被拒绝 userId={} spaceId={}",
                    currentUserIdOrNull(), spaceId);
            throw new BizException(4030, "无权访问该知识库空间");
        }
    }

    @Override
    public List<SpaceMemberVO> members(Long spaceId) {
        requireExistingSpace(spaceId);
        List<KbSpaceMember> rows = memberMapper.selectList(
                new LambdaQueryWrapper<KbSpaceMember>()
                        .eq(KbSpaceMember::getSpaceId, spaceId)
                        .orderByAsc(KbSpaceMember::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = rows.stream().map(KbSpaceMember::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        // 已删除的账号不再展示，但成员行保留：账号恢复后授权关系不该悄悄丢
        return rows.stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> {
                    SysUser u = users.get(m.getUserId());
                    return new SpaceMemberVO(u.getId(), u.getUsername(), u.getNickname(),
                            u.getRole(), m.getCreateTime());
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grant(Long spaceId, List<Long> userIds) {
        KbSpace space = requireExistingSpace(spaceId);
        List<Long> distinct = distinctUserIds(userIds);
        // 校验用户存在，避免把空间授权给一个不存在的 ID（那行记录既没意义又没法撤销时定位）
        // SysUser 上是 @TableLogic，selectCount 自带 deleted = 0
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, distinct)) != distinct.size()) {
            throw new BizException(4040, "所选用户中有不存在的账号");
        }
        List<Long> existed = memberMapper.selectList(new LambdaQueryWrapper<KbSpaceMember>()
                        .eq(KbSpaceMember::getSpaceId, spaceId)
                        .in(KbSpaceMember::getUserId, distinct))
                .stream().map(KbSpaceMember::getUserId).toList();
        int added = 0;
        for (Long userId : distinct) {
            if (existed.contains(userId)) {
                continue;
            }
            try {
                KbSpaceMember member = new KbSpaceMember();
                member.setSpaceId(spaceId);
                member.setUserId(userId);
                memberMapper.insert(member);
                added++;
            } catch (DuplicateKeyException e) {
                // 「先查再插」在并发双击下仍可能撞唯一键，这里当作已授权跳过，
                // 与 chat_feedback 的重复工单处理同一口径（见 V7 与 ChatFeedbackServiceImpl）
                log.debug("空间授权重复提交已被唯一键挡下 spaceId={} userId={}", spaceId, userId);
            }
        }
        log.info("空间授权完成 spaceId={} name={} 请求={} 新增={}", spaceId, space.getName(), distinct.size(), added);
        return added;
    }

    @Override
    public int revoke(Long spaceId, List<Long> userIds) {
        KbSpace space = requireExistingSpace(spaceId);
        List<Long> distinct = distinctUserIds(userIds);
        // 创建人不许被移出：他能删空整个空间，移出之后自己却看不见，等于把空间锁死没人能管
        if (space.getCreatedBy() != null && distinct.contains(space.getCreatedBy())) {
            throw new BizException(4031, "不能撤销空间创建人的访问权限：" + space.getName());
        }
        int removed = memberMapper.delete(new LambdaQueryWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getSpaceId, spaceId)
                .in(KbSpaceMember::getUserId, distinct));
        log.info("撤销空间授权 spaceId={} 请求={} 实际移除={}", spaceId, distinct.size(), removed);
        return removed;
    }

    @Override
    public void joinDefaultSpace(SysUser user) {
        if (user == null || user.getId() == null || SysUser.ROLE_ADMIN.equals(user.getRole())) {
            // ADMIN 绕过成员表，给它插一行只会让「空间成员」列表里多出一个没意义的管理员
            return;
        }
        Long spaceId = KbSpaceService.DEFAULT_SPACE_ID;
        if (spaceMapper.selectById(spaceId) == null) {
            log.warn("默认知识库空间不存在，跳过自动授权 userId={} spaceId={}", user.getId(), spaceId);
            return;
        }
        try {
            KbSpaceMember member = new KbSpaceMember();
            member.setSpaceId(spaceId);
            member.setUserId(user.getId());
            memberMapper.insert(member);
            log.info("账号已加入默认知识库空间 userId={} username={}", user.getId(), user.getUsername());
        } catch (DuplicateKeyException e) {
            log.debug("默认空间授权重复提交已被唯一键挡下 userId={}", user.getId());
        }
    }

    // ------------------------------------------------------------------ 内部方法

    private SpaceVO toVo(KbSpace space) {
        return new SpaceVO(space.getId(), space.getName(), space.getDescription(),
                space.getDocCount(), space.getChunkCount(), space.getCreateTime(), space.getUpdateTime());
    }

    private KbSpace requireExistingSpace(Long spaceId) {
        KbSpace space = spaceId == null ? null : spaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BizException(4040, "知识库空间不存在: " + spaceId);
        }
        return space;
    }

    private List<Long> distinctUserIds(List<Long> userIds) {
        List<Long> distinct = userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new BizException(4001, "请至少选择一个用户");
        }
        return distinct;
    }

    /** 只用于日志：越权告警里带上是谁，方便审计；取不到身份时给出 -1 而不是抛异常 */
    private static Long currentUserIdOrNull() {
        LoginUser current = UserContext.get();
        return current == null ? -1L : current.id();
    }
}
