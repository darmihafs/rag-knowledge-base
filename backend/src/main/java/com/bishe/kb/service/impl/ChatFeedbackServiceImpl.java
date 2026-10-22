package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.ChatFeedbackMapper;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.ChatSessionMapper;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.dto.FeedbackCreateRequest;
import com.bishe.kb.model.dto.FeedbackPageQuery;
import com.bishe.kb.model.dto.FeedbackResolveRequest;
import com.bishe.kb.model.entity.ChatFeedback;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.FeedbackBriefVO;
import com.bishe.kb.model.vo.FeedbackStatsVO;
import com.bishe.kb.model.vo.FeedbackVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.ChatFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 问答反馈与 BadCase 工单实现。
 *
 * <p>三条实现要点：
 * <ol>
 *   <li><b>一个用户对一条回答只有一张工单</b>：重复提交按改评处理（赞踩互转），并退回待处理，
 *       否则同一句话能被刷成多条工单，质量指标就失真了。这条规则由唯一键
 *       {@code uk_feedback_message_user(message_id, user_id)}（见 V7）在库侧兜底，
 *       服务层撞键时合并进既有工单而不是把异常抛给用户。</li>
 *   <li><b>只能评价自己的回答</b>：被评价消息所属会话的 {@code user_id} 必须等于当前用户，
 *       管理员例外（要能替用户补录归因）。越权一律按 4030 拒绝，而不是静默忽略。</li>
 *   <li><b>工单要活过消息</b>：删会话会物理清理 chat_message，工单保留，列表侧对反查不到的
 *       消息显示「消息已清理」，这样 BadCase 分布仍是检索质量的历史证据。</li>
 * </ol>
 *
 * <p>列表补齐遵循全项目一致的「批量反查 + 内存归并」写法，不做逐行查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFeedbackServiceImpl extends ServiceImpl<ChatFeedbackMapper, ChatFeedback>
        implements ChatFeedbackService {

    /** 反馈说明入库上限，与表列宽度一致 */
    private static final int COMMENT_MAX_LENGTH = 500;

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SysUserMapper userMapper;

    // ------------------------------------------------------------------ 用户侧

    @Override
    public FeedbackBriefVO submit(FeedbackCreateRequest request, LoginUser current) {
        int rating = request.rating();
        if (rating != ChatFeedback.RATING_UP && rating != ChatFeedback.RATING_DOWN) {
            throw new BizException(4001, "rating 只能取 1（赞）或 -1（踩）");
        }
        ChatMessage message = messageMapper.selectById(request.messageId());
        if (message == null || !ChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BizException(4040, "该回答已不存在或已被清理，无法反馈");
        }
        requireFeedbackAllowed(message, current);

        boolean thumbsUp = rating == ChatFeedback.RATING_UP;
        // 同用户同消息只保留一张工单：已存在就改评，并把状态退回待处理
        ChatFeedback feedback = existingOf(message.getId(), current.id());
        boolean isNew = feedback == null;
        if (isNew) {
            feedback = new ChatFeedback();
            feedback.setMessageId(message.getId());
            feedback.setUserId(current.id());
        }
        applyRating(feedback, message, rating, request, thumbsUp);
        try {
            saveOrUpdate(feedback);
        } catch (DuplicateKeyException e) {
            // 「先查后写」在并发下不是原子的：双击或两个标签页同时提交时，另一个请求已抢先插入，
            // 唯一键 uk_feedback_message_user 把它挡在库外。这里合并到赢家的行上重跑一次改评，
            // 用户视角就是一次成功的改评，而不是冒出一条重复工单。
            ChatFeedback winner = existingOf(message.getId(), current.id());
            if (winner == null) {
                throw e;
            }
            applyRating(winner, message, rating, request, thumbsUp);
            updateById(winner);
            feedback = winner;
            isNew = false;
            log.info("并发重复提交反馈已合并到既有工单 messageId={} userId={}", message.getId(), current.id());
        }

        log.info("{}反馈 messageId={} userId={} rating={} issueType={}",
                isNew ? "新增" : "改评", message.getId(), current.id(), rating, feedback.getIssueType());
        return toBrief(feedback);
    }

    @Override
    public List<FeedbackBriefVO> mine(Long sessionId) {
        LoginUser current = UserContext.require();
        return list(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getUserId, current.id())
                .eq(sessionId != null, ChatFeedback::getSessionId, sessionId)
                .orderByDesc(ChatFeedback::getId))
                .stream().map(this::toBrief).toList();
    }

    // ------------------------------------------------------------------ 管理员侧

    @Override
    public Page<FeedbackVO> pageForAdmin(FeedbackPageQuery query) {
        LambdaQueryWrapper<ChatFeedback> wrapper = new LambdaQueryWrapper<ChatFeedback>()
                .eq(query.rating() != null, ChatFeedback::getRating, query.rating())
                .eq(query.status() != null, ChatFeedback::getStatus, query.status())
                .eq(StringUtils.isNotBlank(query.issueType()), ChatFeedback::getIssueType, query.issueType())
                .orderByDesc(ChatFeedback::getId);

        Page<ChatFeedback> source = page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<ChatFeedback> rows = source.getRecords();

        Page<FeedbackVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        if (rows.isEmpty()) {
            return result;
        }

        Map<Long, ChatMessage> answers = loadMessages(rows.stream().map(ChatFeedback::getMessageId)
                .filter(Objects::nonNull).distinct().toList());
        Map<Long, String> questions = loadQuestions(answers.values());
        Map<Long, SysUser> users = loadUsers(rows.stream().map(ChatFeedback::getUserId).toList());

        List<FeedbackVO> records = new ArrayList<>(rows.size());
        for (ChatFeedback f : rows) {
            ChatMessage answer = f.getMessageId() == null ? null : answers.get(f.getMessageId());
            SysUser user = f.getUserId() == null ? null : users.get(f.getUserId());
            records.add(new FeedbackVO(
                    f.getId(), f.getMessageId(), f.getSessionId(),
                    f.getUserId(),
                    user == null ? "已删除用户" : user.getUsername(),
                    user == null ? null : user.getNickname(),
                    f.getRating(), f.getIssueType(), f.getComment(), f.getStatus(), f.getAdminNote(),
                    answer == null ? null : questions.get(answer.getId()),
                    answer == null ? null : answer.getContent(),
                    f.getCreateTime(), f.getUpdateTime()));
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public FeedbackVO resolve(Long id, FeedbackResolveRequest request, LoginUser operator) {
        ChatFeedback feedback = id == null ? null : getById(id);
        if (feedback == null) {
            throw new BizException(4040, "工单不存在: " + id);
        }
        int status = request.status();
        if (status != ChatFeedback.STATUS_PENDING && status != ChatFeedback.STATUS_FIXED
                && status != ChatFeedback.STATUS_IGNORED) {
            throw new BizException(4004, "status 只能取 0（待处理）/ 1（已修复）/ 2（已忽略）");
        }
        String note = StringUtils.trimToNull(request.adminNote());
        if (status != ChatFeedback.STATUS_PENDING && note == null) {
            throw new BizException(4001, "结单必须填写处理备注，说明结论与依据");
        }
        feedback.setStatus(status);
        feedback.setAdminNote(StringUtils.abbreviate(note, COMMENT_MAX_LENGTH));
        updateById(feedback);
        log.info("工单处置 id={} status={} 操作人={}", id, status, operator.username());

        SysUser user = feedback.getUserId() == null ? null : userMapper.selectById(feedback.getUserId());
        ChatMessage answer = feedback.getMessageId() == null ? null : messageMapper.selectById(feedback.getMessageId());
        String question = answer == null ? null
                : loadQuestions(List.of(answer)).get(answer.getId());
        return new FeedbackVO(feedback.getId(), feedback.getMessageId(), feedback.getSessionId(),
                feedback.getUserId(),
                user == null ? "已删除用户" : user.getUsername(),
                user == null ? null : user.getNickname(),
                feedback.getRating(), feedback.getIssueType(), feedback.getComment(),
                feedback.getStatus(), feedback.getAdminNote(), question,
                answer == null ? null : answer.getContent(),
                feedback.getCreateTime(), feedback.getUpdateTime());
    }

    @Override
    public FeedbackStatsVO stats() {
        long total = count();
        long thumbsUp = count(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getRating, ChatFeedback.RATING_UP));
        long thumbsDown = count(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getRating, ChatFeedback.RATING_DOWN));
        long pendingBadCase = count(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getRating, ChatFeedback.RATING_DOWN)
                .eq(ChatFeedback::getStatus, ChatFeedback.STATUS_PENDING));
        long fixed = count(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getStatus, ChatFeedback.STATUS_FIXED));
        long ignored = count(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getStatus, ChatFeedback.STATUS_IGNORED));

        // 类型占比只在点踩样本里算：把点赞混进分母会稀释真正要定位的缺陷分布
        Map<String, Long> byType = list(new LambdaQueryWrapper<ChatFeedback>()
                .select(ChatFeedback::getIssueType)
                .eq(ChatFeedback::getRating, ChatFeedback.RATING_DOWN))
                .stream()
                .collect(Collectors.groupingBy(
                        f -> StringUtils.defaultIfBlank(f.getIssueType(), ChatFeedback.DEFAULT_ISSUE_TYPE),
                        Collectors.counting()));
        List<FeedbackStatsVO.IssueTypeCount> issueTypes = byType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new FeedbackStatsVO.IssueTypeCount(e.getKey(), e.getValue(),
                        thumbsDown == 0 ? 0d : round(e.getValue() * 1d / thumbsDown)))
                .toList();

        return new FeedbackStatsVO(total, thumbsUp, thumbsDown,
                total == 0 ? 0d : round(thumbsUp * 1d / total),
                pendingBadCase, fixed, ignored, issueTypes);
    }

    // ------------------------------------------------------------------ 内部工具

    /** 会话主人可以评价自己的回答，管理员可以补录；其余一律 4030 */
    private void requireFeedbackAllowed(ChatMessage message, LoginUser current) {
        if (current.isAdmin()) {
            return;
        }
        ChatSession session = sessionMapper.selectById(message.getSessionId());
        if (session == null) {
            throw new BizException(4040, "该回答所属会话已删除，无法反馈");
        }
        if (!Objects.equals(session.getUserId(), current.id())) {
            throw new BizException(4030, "只能评价自己的回答");
        }
    }

    /**
     * 把本次评价落到工单实体上（不含主键归属字段）。
     *
     * <p>点赞清空归因与说明——好样本不需要纠偏理由；点踩把空的/非法的问题类型归一化为默认值，
     * 保证工单列表的类型分布统计不会因为脏值出现「其他」以外的野类型。状态一律退回待处理：改评
     * 意味着用户重新表达了意见，管理员此前的结单结论不再适用。
     */
    private void applyRating(ChatFeedback feedback, ChatMessage message, int rating,
                             FeedbackCreateRequest request, boolean thumbsUp) {
        feedback.setSessionId(message.getSessionId());
        feedback.setRating(rating);
        feedback.setIssueType(thumbsUp ? null : normalizeIssueType(request.issueType()));
        feedback.setComment(thumbsUp ? null
                : StringUtils.abbreviate(StringUtils.trimToNull(request.comment()), COMMENT_MAX_LENGTH));
        feedback.setStatus(ChatFeedback.STATUS_PENDING);
    }

    private ChatFeedback existingOf(Long messageId, Long userId) {
        List<ChatFeedback> exists = list(new LambdaQueryWrapper<ChatFeedback>()
                .eq(ChatFeedback::getMessageId, messageId)
                .eq(ChatFeedback::getUserId, userId)
                .orderByAsc(ChatFeedback::getId));
        // 历史脏数据里同一条回答可能出现多张工单，取最早的一张继续改，其余不动
        return exists.isEmpty() ? null : exists.get(0);
    }

    private String normalizeIssueType(String issueType) {
        String trimmed = StringUtils.trimToNull(issueType);
        return trimmed == null || !ChatFeedback.ISSUE_TYPES.contains(trimmed)
                ? ChatFeedback.DEFAULT_ISSUE_TYPE
                : trimmed;
    }

    private FeedbackBriefVO toBrief(ChatFeedback f) {
        return new FeedbackBriefVO(f.getMessageId(), f.getRating(), f.getIssueType(), f.getComment());
    }

    private Map<Long, ChatMessage> loadMessages(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return messageMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ChatMessage::getId, m -> m, (a, b) -> a));
    }

    private Map<Long, SysUser> loadUsers(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
    }

    /**
     * 为每条被评价的回答找回它那一轮的提问：同会话中 ID 比它小的最后一条 user 消息。
     *
     * <p>一次查询把这批会话的提问全部取回（ID 上限取本页最大回答 ID），在内存里按会话分组、
     * 从后往前挑第一条早于该回答的，避免逐行查库。
     */
    private Map<Long, String> loadQuestions(Collection<ChatMessage> answers) {
        Set<Long> sessionIds = answers.stream().map(ChatMessage::getSessionId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        long upperBound = answers.stream().mapToLong(ChatMessage::getId).max().orElse(0L);
        List<ChatMessage> questions = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .select(ChatMessage::getId, ChatMessage::getSessionId, ChatMessage::getContent)
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER)
                .in(ChatMessage::getSessionId, sessionIds)
                .lt(ChatMessage::getId, upperBound)
                .orderByAsc(ChatMessage::getId));

        Map<Long, List<ChatMessage>> bySession = questions.stream()
                .collect(Collectors.groupingBy(ChatMessage::getSessionId, Collectors.toList()));

        Map<Long, String> paired = new HashMap<>();
        for (ChatMessage answer : answers) {
            List<ChatMessage> sameSession = bySession.get(answer.getSessionId());
            if (sameSession == null) {
                continue;
            }
            for (int i = sameSession.size() - 1; i >= 0; i--) {
                ChatMessage candidate = sameSession.get(i);
                if (candidate.getId() < answer.getId()) {
                    paired.put(answer.getId(), candidate.getContent());
                    break;
                }
            }
        }
        return paired;
    }

    /** 比率保留四位小数，前端再格式化成百分数，避免二进制浮点尾巴出现在接口里 */
    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
