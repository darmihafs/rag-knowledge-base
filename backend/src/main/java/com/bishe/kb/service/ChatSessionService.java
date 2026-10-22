package com.bishe.kb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.entity.ChatSession;
import com.bishe.kb.model.vo.AskResponse;
import com.bishe.kb.model.vo.ChatMessageVO;
import com.bishe.kb.model.vo.ChatTurn;
import com.bishe.kb.model.vo.SessionVO;

import java.util.List;

/**
 * 会话与消息持久化：多轮对话的记忆载体。
 *
 * <p>职责边界：本服务只管「存」和「取」，不关心大模型；
 * 由 {@code RagAnswerServiceImpl} 在流式问答前后调用，负责拼上下文与落库时机。
 */
public interface ChatSessionService extends IService<ChatSession> {

    /** 会话列表，按最后活跃时间倒序 */
    List<SessionVO> listSessions();

    /** 新建会话，title 为空则用默认标题 */
    SessionVO createSession(String title);

    /** 删除会话：会话逻辑删除，名下消息物理清理 */
    void deleteSession(Long sessionId);

    /** 某个会话的全部历史消息，按时间正序 */
    List<ChatMessageVO> listMessages(Long sessionId);

    /** 校验会话存在并返回，不存在抛 4040 */
    ChatSession requireSession(Long sessionId);

    /**
     * 取最近 limit 条历史消息（时间正序），用于拼多轮上下文。
     * 必须在写入本轮 user 消息之前调用，否则会把当前问题也算进历史。
     */
    List<ChatTurn> loadRecentHistory(Long sessionId, int limit);

    /** 落库用户提问；若这是会话的第一条消息，顺带把标题改成问题前 15 字 */
    void saveUserMessage(Long sessionId, String question);

    /**
     * 落库模型完整回答与引用来源。
     *
     * @return 新消息的主键 ID；供 SSE {@code done} 事件带回前端，作为「这条回答」的反馈锚点
     */
    Long saveAssistantMessage(Long sessionId, String answer, List<AskResponse.Citation> citations);
}
