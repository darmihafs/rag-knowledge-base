package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.AskRequest;
import com.bishe.kb.model.dto.AskStreamRequest;
import com.bishe.kb.model.dto.RetrieveCompareRequest;
import com.bishe.kb.model.vo.AskResponse;
import com.bishe.kb.model.vo.RetrieveCompareVO;
import com.bishe.kb.service.RagAnswerService;
import com.bishe.kb.service.RetrievalCompareService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答与检索接口。
 *
 * <p>三个入口共用同一套检索内核：
 * <ul>
 *   <li>{@code POST /api/knowledge/ask}             —— 同步、无状态，一次性返回完整答案，不落库（调试用）</li>
 *   <li>{@code POST /api/knowledge/ask/stream}      —— SSE 流式，绑定会话、带多轮上下文、逐字推送 + 末尾附引用来源</li>
 *   <li>{@code POST /api/knowledge/retrieve-compare} —— 检索对比实验室，同一问题并排跑词法/语义/混合三路（仅管理员）</li>
 * </ul>
 *
 * <p>流式事件名：{@code meta} / {@code delta} / {@code citations} / {@code done} / {@code error}，
 * 具体结构见 {@link com.bishe.kb.service.impl.RagAnswerServiceImpl} 类注释。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    /** 流式连接最长保持 5 分钟，足够长文档生成 */
    private static final long STREAM_TIMEOUT_MILLIS = 300_000L;

    private final RagAnswerService answerService;
    private final RetrievalCompareService retrievalCompareService;

    @PostMapping("/ask")
    public Result<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return Result.ok(answerService.ask(request));
    }

    /**
     * 检索对比：只做召回、不调用大模型，因此没有 token 成本，可以随便点。
     * 返回三路的召回切片、名次与各自耗时，供前端并排证明 RRF 混合检索的价值。
     *
     * <p>这是一个只读的诊断接口，但能看出全库内容，故在拦截器里按精确路径收敛为管理员可用。
     */
    @PostMapping("/retrieve-compare")
    public Result<RetrieveCompareVO> retrieveCompare(@Valid @RequestBody RetrieveCompareRequest request) {
        return Result.ok(retrievalCompareService.compare(request));
    }

    /**
     * 流式问答。用 POST + fetch ReadableStream 消费（EventSource 只支持 GET，问题放 URL 里还要处理编码）。
     * sessionId 必填：本轮提问与模型回答都会落到 chat_message，并带最近 3 轮历史进 Prompt。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AskStreamRequest request, HttpServletResponse response) {
        // 关掉反向代理缓冲，否则 Nginx/Vite 会攒够一批再下发，打字机效果就没了
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        // 超时 / 异常 / 完成回调统一由 RagAnswerServiceImpl 注册：
        // 它要在断连时置协作取消标记，避免把半截回答写进会话历史，这里不再重复注册覆盖它。
        answerService.askStream(request, emitter);
        return emitter;
    }
}
