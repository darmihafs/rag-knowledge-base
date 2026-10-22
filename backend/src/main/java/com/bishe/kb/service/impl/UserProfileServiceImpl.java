package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.ChatFeedbackMapper;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.ChatSessionMapper;
import com.bishe.kb.model.dto.PasswordUpdateRequest;
import com.bishe.kb.model.entity.ChatFeedback;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.MyStatsVO;
import com.bishe.kb.model.vo.UserVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.service.SysUserService;
import com.bishe.kb.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 个人中心实现。
 *
 * <p>统计一律以「本人当前仍存在的会话」为口径：删掉的会话连带物理清理了消息，
 * 因此提问数、回答数会随之下降，这符合用户在页面上的直觉（看不到的对话不该计入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final SysUserService userService;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatFeedbackMapper feedbackMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserVO updateProfile(String nickname, LoginUser current) {
        String trimmed = StringUtils.trimToNull(nickname);
        if (trimmed == null) {
            throw new BizException(4001, "昵称不能为空");
        }
        if (trimmed.length() > 32) {
            throw new BizException(4001, "昵称最多 32 字");
        }
        SysUser user = requireSelf(current);
        user.setNickname(trimmed);
        userService.updateById(user);
        log.info("用户修改昵称 id={} nickname={}", user.getId(), trimmed);
        return UserVO.from(user);
    }

    @Override
    public void changePassword(PasswordUpdateRequest request, LoginUser current) {
        SysUser user = requireSelf(current);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            // 只说「原密码不正确」：此时身份已由 JWT 确认，不存在账号枚举风险
            throw new BizException(4001, "原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BizException(4001, "新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userService.updateById(user);
        // 改密即吊销：版本号 +1 后，此前签发的所有 token（含发起本次请求的这一个）都不再被接受
        userService.invalidateTokens(user.getId());
        log.info("用户修改密码成功 id={} username={}，旧 token 已随令牌版本号递增而失效，前端需引导重新登录",
                user.getId(), user.getUsername());
    }

    @Override
    public void logout(LoginUser current) {
        SysUser user = current == null ? null : userService.findByUsername(current.username());
        if (user == null) {
            // 账号已被删除：token 自然也没了归属，按成功处理即可
            return;
        }
        userService.invalidateTokens(user.getId());
        log.info("用户退出登录 id={} username={}，服务端已吊销其现存 token", user.getId(), user.getUsername());
    }

    @Override
    public MyStatsVO myStats(LoginUser current) {
        SysUser user = requireSelf(current);
        Long userId = user.getId();

        List<Long> sessionIds = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .select(ChatSession::getId)
                        .eq(ChatSession::getUserId, userId))
                .stream().map(ChatSession::getId).toList();
        long questionCount = sessionIds.isEmpty() ? 0
                : messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .in(ChatMessage::getSessionId, sessionIds)
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER));
        long answerCount = sessionIds.isEmpty() ? 0
                : messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .in(ChatMessage::getSessionId, sessionIds)
                .eq(ChatMessage::getRole, ChatMessage.ROLE_ASSISTANT));
        long feedbackCount = feedbackMapper.selectCount(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getUserId, userId));
        long badCaseCount = feedbackMapper.selectCount(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getUserId, userId)
                .eq(ChatFeedback::getRating, ChatFeedback.RATING_DOWN));

        return new MyStatsVO(sessionIds.size(), questionCount, answerCount,
                feedbackCount, badCaseCount, user.getCreateTime());
    }

    /** 个人中心只操作自己：按 JWT 里的用户名回表，账号没了就按登录失效处理 */
    private SysUser requireSelf(LoginUser current) {
        SysUser user = current == null ? null : userService.findByUsername(current.username());
        if (user == null) {
            throw new BizException(4010, "登录状态已失效，请重新登录");
        }
        if (!user.isEnabled()) {
            throw new BizException(4003, "账号已被禁用，请联系管理员");
        }
        return user;
    }
}
