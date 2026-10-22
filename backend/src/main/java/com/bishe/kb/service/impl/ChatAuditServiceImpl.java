package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.ChatSessionMapper;
import com.bishe.kb.mapper.SysUserMapper;
import com.bishe.kb.model.dto.AuditPageQuery;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.AskResponse;
import com.bishe.kb.model.vo.AuditDetailVO;
import com.bishe.kb.model.vo.AuditMessageVO;
import com.bishe.kb.service.ChatAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 问答审计实现：以 user 角色消息为「一行」，把同会话里紧随其后的 assistant 回答配对出来。
 *
 * <p>为什么要配对：{@code chat_message} 一问一答各存一行、之间没有外键，
 * 而审计表必须「一行看一次问答」。这里按主键单调递增的性质取「同会话中 ID 更大且最小」的
 * 那条回答，等价于时间上的下一条回复，且不依赖任何数据库方言函数。
 *
 * <p>会话标题与提问人分别用一次批量查询补齐（{@code selectBatchIds}），
 * 整页只有 4 次查询：提问分页、回答配对、会话、用户，不会随页大小线性增加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAuditServiceImpl implements ChatAuditService {

    /** 列表里回答摘要的截断长度 */
    private static final int SUMMARY_LENGTH = 220;

    private static final TypeReference<List<AskResponse.Citation>> CITATION_LIST = new TypeReference<>() {
    };

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Page<AuditMessageVO> pageMessages(AuditPageQuery query) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER)
                .like(StringUtils.isNotBlank(query.keyword()), ChatMessage::getContent,
                        StringUtils.trimToNull(query.keyword()))
                .orderByDesc(ChatMessage::getId);

        // 按用户名过滤：先把该用户的会话 ID 查出来当过滤条件；查不到就直接返回空页，不再打无谓的 SQL
        if (StringUtils.isNotBlank(query.username())) {
            List<Long> sessionIds = sessionIdsOf(query.username().trim());
            if (sessionIds.isEmpty()) {
                return new Page<>(query.pageNum(), query.pageSize(), 0);
            }
            wrapper.in(ChatMessage::getSessionId, sessionIds);
        }

        Page<ChatMessage> source = messageMapper.selectPage(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<ChatMessage> questions = source.getRecords();
        if (questions.isEmpty()) {
            return new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        }

        Map<Long, ChatMessage> answers = pairAnswers(questions);
        Map<Long, ChatSession> sessions = loadSessions(questions);
        Map<Long, SysUser> users = loadUsers(sessions.values());

        List<AuditMessageVO> records = new ArrayList<>(questions.size());
        for (ChatMessage question : questions) {
            ChatSession session = sessions.get(question.getSessionId());
            SysUser user = session == null || session.getUserId() == null
                    ? null : users.get(session.getUserId());
            ChatMessage answer = answers.get(question.getId());
            List<AskResponse.Citation> citations = readCitations(answer == null ? null : answer.getCitations());
            records.add(new AuditMessageVO(
                    question.getId(), question.getSessionId(),
                    session == null ? "（会话已删除）" : session.getTitle(),
                    user == null ? null : user.getId(),
                    user == null ? "已删除用户" : user.getUsername(),
                    user == null ? null : user.getNickname(),
                    question.getContent(),
                    answer == null ? null : StringUtils.abbreviate(answer.getContent(), SUMMARY_LENGTH),
                    citations.size(),
                    question.getCreateTime()));
        }

        Page<AuditMessageVO> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public AuditDetailVO detail(Long messageId) {
        ChatMessage question = messageId == null ? null : messageMapper.selectById(messageId);
        if (question == null || !ChatMessage.ROLE_USER.equals(question.getRole())) {
            throw new BizException(4040, "提问记录不存在");
        }
        ChatMessage answer = pairAnswers(List.of(question)).get(question.getId());
        ChatSession session = sessionMapper.selectById(question.getSessionId());
        SysUser user = session == null || session.getUserId() == null
                ? null : userMapper.selectById(session.getUserId());

        return new AuditDetailVO(
                question.getId(), question.getSessionId(),
                session == null ? "（会话已删除）" : session.getTitle(),
                user == null ? null : user.getId(),
                user == null ? "已删除用户" : user.getUsername(),
                user == null ? null : user.getNickname(),
                question.getContent(),
                answer == null ? null : answer.getContent(),
                question.getCreateTime(),
                readCitations(answer == null ? null : answer.getCitations()));
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 把提问与同会话中「下一条」回答配对。
     *
     * <p>一次性按会话 ID 把这页涉及的 assistant 消息取回来，在内存里按 ID 升序，
     * 再为每条提问挑出 ID 比它大的第一条——即时间上紧跟其后的那次回答（追问场景下也不会串轮）。
     */
    private Map<Long, ChatMessage> pairAnswers(List<ChatMessage> questions) {
        Set<Long> sessionIds = questions.stream()
                .map(ChatMessage::getSessionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        List<ChatMessage> answers = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, ChatMessage.ROLE_ASSISTANT)
                .in(ChatMessage::getSessionId, sessionIds)
                .orderByAsc(ChatMessage::getId));

        Map<Long, List<ChatMessage>> bySession = answers.stream()
                .collect(Collectors.groupingBy(ChatMessage::getSessionId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, ChatMessage> paired = new HashMap<>();
        for (ChatMessage question : questions) {
            for (ChatMessage answer : bySession.getOrDefault(question.getSessionId(), List.of())) {
                if (answer.getId() > question.getId()) {
                    paired.put(question.getId(), answer);
                    break;
                }
            }
        }
        return paired;
    }

    private Map<Long, ChatSession> loadSessions(List<ChatMessage> questions) {
        List<Long> ids = questions.stream().map(ChatMessage::getSessionId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sessionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ChatSession::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, SysUser> loadUsers(Collection<ChatSession> sessions) {
        List<Long> ids = sessions.stream().map(ChatSession::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
    }

    private List<Long> sessionIdsOf(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
        if (user == null) {
            return List.of();
        }
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .select(ChatSession::getId)
                        .eq(ChatSession::getUserId, user.getId()))
                .stream().map(ChatSession::getId).toList();
    }

    private List<AskResponse.Citation> readCitations(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CITATION_LIST);
        } catch (JsonProcessingException e) {
            log.warn("审计读取引用失败，按无引用处理: {}", e.getMessage());
            return List.of();
        }
    }
}
