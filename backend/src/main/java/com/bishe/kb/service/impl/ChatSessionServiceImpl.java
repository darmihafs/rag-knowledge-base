package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.ChatSessionMapper;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.vo.AskResponse;
import com.bishe.kb.model.vo.ChatMessageVO;
import com.bishe.kb.model.vo.ChatTurn;
import com.bishe.kb.model.vo.SessionVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.ChatSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话与消息持久化实现。
 *
 * <p>引用来源以 JSON 字符串存在 {@code chat_message.citations}：读出来再反序列化成
 * {@link AskResponse.Citation} 列表，前端拿到的结构与流式 SSE 的 citations 事件完全一致，
 * 因此「历史消息」和「实时回答」可以复用同一套渲染代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession>
        implements ChatSessionService {

    /** 自动标题截取长度，产品要求 15 字 */
    private static final int TITLE_MAX_LENGTH = 15;
    /** citations 字段反序列化目标类型 */
    private static final TypeReference<List<AskResponse.Citation>> CITATION_LIST =
            new TypeReference<>() {
            };

    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 会话

    @Override
    public List<SessionVO> listSessions() {
        // 数据隔离：只返回当前登录用户名下的会话，普通用户看不到别人的历史对话
        return list(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, UserContext.currentUserId())
                .orderByDesc(ChatSession::getUpdateTime)
                .orderByDesc(ChatSession::getId))
                .stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    public SessionVO createSession(String title) {
        ChatSession session = new ChatSession();
        // 归属当前登录用户，配合 listSessions 的过滤实现隔离
        session.setUserId(UserContext.currentUserId());
        session.setTitle(StringUtils.isBlank(title)
                ? ChatSession.DEFAULT_TITLE
                : StringUtils.substring(title.trim(), 0, 64));
        save(session);
        log.info("新建会话 id={} userId={} title={}", session.getId(), session.getUserId(), session.getTitle());
        return toVo(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long sessionId) {
        requireSession(sessionId);
        int removed = messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        removeById(sessionId);
        log.info("删除会话 id={} 连带清理消息 {} 条", sessionId, removed);
    }

    @Override
    public List<ChatMessageVO> listMessages(Long sessionId) {
        requireSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    public ChatSession requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new BizException(4001, "sessionId 不能为空");
        }
        ChatSession session = getById(sessionId);
        if (session == null) {
            throw new BizException(4040, "会话不存在或已删除: " + sessionId);
        }
        // 归属校验：只有会话主人（或管理员）能读写；对非主人一律按「不存在」处理，避免泄露他人会话是否存在
        LoginUser current = UserContext.require();
        boolean owner = session.getUserId() != null && session.getUserId().equals(current.id());
        if (!owner && !current.isAdmin()) {
            throw new BizException(4040, "会话不存在或无权访问: " + sessionId);
        }
        return session;
    }

    // ------------------------------------------------------------------ 消息

    @Override
    public List<ChatTurn> loadRecentHistory(Long sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ChatMessage> latestFirst = messageMapper.selectPage(new Page<>(1, limit),
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, sessionId)
                                .orderByDesc(ChatMessage::getId))
                .getRecords();
        // 取的是「最近 N 条」，但喂给模型要按时间正序，否则对话逻辑会颠倒
        List<ChatTurn> chronological = new ArrayList<>(latestFirst.size());
        for (int i = latestFirst.size() - 1; i >= 0; i--) {
            ChatMessage m = latestFirst.get(i);
            chronological.add(new ChatTurn(m.getRole(), m.getContent()));
        }
        return chronological;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUserMessage(Long sessionId, String question) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent(question);
        messageMapper.insert(message);

        ChatSession session = getById(sessionId);
        if (session == null) {
            return;
        }
        Long userTurns = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER));
        // 只有「默认标题 + 第一轮提问」才自动改名，用户手动改过的标题不覆盖
        if (userTurns != null && userTurns <= 1 && ChatSession.DEFAULT_TITLE.equals(session.getTitle())) {
            session.setTitle(buildTitle(question));
        }
        session.setUpdateTime(LocalDateTime.now());
        updateById(session);
    }

    @Override
    public Long saveAssistantMessage(Long sessionId, String answer, List<AskResponse.Citation> citations) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(ChatMessage.ROLE_ASSISTANT);
        message.setContent(StringUtils.defaultString(answer));
        message.setCitations(writeCitations(citations));
        messageMapper.insert(message);

        ChatSession session = getById(sessionId);
        if (session != null) {
            session.setUpdateTime(LocalDateTime.now());
            updateById(session);
        }
        log.debug("回答已落库 id={} sessionId={} 长度={} 引用={}", message.getId(), sessionId,
                message.getContent().length(), citations == null ? 0 : citations.size());
        return message.getId();
    }

    // ------------------------------------------------------------------ 内部工具

    /** 问题 -> 会话标题：压成单行后截前 15 字 */
    private String buildTitle(String question) {
        String oneLine = StringUtils.normalizeSpace(question);
        if (oneLine.length() <= TITLE_MAX_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    private String writeCitations(List<AskResponse.Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            // 引用只是展示用，序列化失败不该让整个问答失败
            log.warn("引用序列化失败，本条消息不保存引用: {}", e.getMessage());
            return null;
        }
    }

    private List<AskResponse.Citation> readCitations(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CITATION_LIST);
        } catch (JsonProcessingException e) {
            log.warn("引用反序列化失败，已忽略: {}", e.getMessage());
            return List.of();
        }
    }

    private SessionVO toVo(ChatSession session) {
        return new SessionVO(session.getId(), session.getTitle(),
                session.getCreateTime(), session.getUpdateTime());
    }

    private ChatMessageVO toVo(ChatMessage message) {
        return new ChatMessageVO(message.getId(), message.getSessionId(), message.getRole(),
                message.getContent(), readCitations(message.getCitations()), message.getCreateTime());
    }
}
