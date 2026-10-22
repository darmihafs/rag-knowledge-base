package com.bishe.kb.service;

import com.bishe.kb.model.dto.AskRequest;
import com.bishe.kb.model.dto.AskStreamRequest;
import com.bishe.kb.model.vo.AskResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答服务：检索知识库片段 -> 组装 Prompt -> 调用大模型生成带出处的回答。
 */
public interface RagAnswerService {

    /** 同步问答：无状态调试接口，一次性返回完整答案 + 引用，不落库、不带历史 */
    AskResponse ask(AskRequest request);

    /**
     * 流式问答：通过 SSE 逐字推送，绑定会话，带最近 3 轮上下文，问答双方都落库。
     * 事件约定见 {@code com.bishe.kb.controller.KnowledgeController}。
     * 本方法立即返回，推送在独立线程池中进行。
     */
    void askStream(AskStreamRequest request, SseEmitter emitter);
}
