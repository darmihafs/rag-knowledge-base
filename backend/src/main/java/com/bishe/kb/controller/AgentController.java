package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.dto.AgentAskRequest;
import com.bishe.kb.model.vo.AgentAskResponse;
import com.bishe.kb.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 登录级入口；不复用管理员目录接口，所有工具在服务端重新收敛空间范围。 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;
    private final RagProperties props;

    /** 同步执行，保留给脚本与旧客户端；硬超时由服务端 Future.get 强制执行。 */
    @PostMapping("/ask")
    public Result<AgentAskResponse> ask(@Valid @RequestBody AgentAskRequest request) {
        return Result.ok(agentService.ask(request));
    }

    /**
     * 流式执行：meta → step → done/error。
     *
     * <p>用 POST + fetch 流消费，理由与问答流式接口相同：问题不放进 URL，且能携带 JWT。
     * SSE 连接超时比 Agent 硬预算多留 30 秒，保证服务端有足够时间推送 done。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AgentAskRequest request, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(Math.max(1, props.getAgentTimeoutMillis()) + 30_000L);
        agentService.askStream(request, emitter);
        return emitter;
    }

    /** 取消当前账号正在运行的智能体任务；已结束或不属于当前账号时 data=false。 */
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Boolean> cancel(@PathVariable String taskId) {
        return Result.ok(agentService.cancel(taskId));
    }
}
