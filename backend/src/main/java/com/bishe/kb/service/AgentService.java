package com.bishe.kb.service;

import com.bishe.kb.model.dto.AgentAskRequest;
import com.bishe.kb.model.vo.AgentAskResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 有预算、只读、按当前账号空间权限执行的知识库智能体。 */
public interface AgentService {
    /** 同步执行；最多等待硬超时时间，到点强制返回 TIME_LIMIT。 */
    AgentAskResponse ask(AgentAskRequest request);

    /**
     * 流式执行：meta（含 taskId）→ step（每次工具调用后）→ done（完整结果）或 error。
     *
     * <p>方法本身立即返回，任务在独立 Agent 线程池执行；客户端断开或超时由服务端取消。
     */
    void askStream(AgentAskRequest request, SseEmitter emitter);

    /** 取消当前用户正在运行的指定任务；任务不存在、已结束或不属于当前用户时返回 false。 */
    boolean cancel(String taskId);
}
