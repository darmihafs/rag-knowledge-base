package com.bishe.kb.service.impl;

import com.bishe.kb.common.BizException;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.model.dto.AskRequest;
import com.bishe.kb.model.dto.AskStreamRequest;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.vo.AskResponse;
import com.bishe.kb.model.vo.ChatTurn;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.ChatSessionService;
import com.bishe.kb.service.RagAnswerService;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.SpaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 问答实现：调用 {@link RetrievalService} 做混合双路召回，再叠加最近 3 轮会话历史交给大模型流式作答。
 *
 * <p>检索与生成解耦：召回与融合在 {@link RetrievalService} 里，本类只管 Prompt、SSE 事件与引用格式；
 * 向量不可用时自动退化为纯词法，链路不中断。
 *
 * <p>一轮流式问答的完整时序：
 * <ol>
 *   <li>校验会话存在（不存在直接以 error 事件结束，不做无主的落库）</li>
 *   <li>先读历史、再写本轮 user 消息——顺序反了会把当前问题当成历史喂回模型</li>
 *   <li>推 meta 事件（召回数 / 历史条数 / 是否演示模式）</li>
 *   <li>逐字推 delta；结束后推 citations、把完整回答落库拿到消息 ID、最后推 done（done 里带回 messageId）</li>
 * </ol>
 *
 * <p>SSE 事件约定（前端按 event 名分派）：
 * <ul>
 *   <li>{@code meta}      —— {@code {"retrieved":n,"topK":k,"history":n,"sessionId":1,"mock":false}}</li>
 *   <li>{@code delta}     —— {@code {"text":"..."}}，逐字增量，前端直接追加</li>
 *   <li>{@code citations} —— 引用来源数组（文档名 + 片段序号 + 摘要），回答末尾推送</li>
 *   <li>{@code done}      —— {@code {"elapsedMillis":123,"messageId":456}}，正常结束；messageId 为 null 表示落库失败</li>
 *   <li>{@code error}     —— {@code {"code":5001,"message":"..."}}，异常结束</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerServiceImpl implements RagAnswerService {

    private static final int SNIPPET_LENGTH = 200;
    /** 演示模式下每次推送的字符数与间隔，模拟真实打字机节奏 */
    private static final int MOCK_STEP = 3;
    private static final long MOCK_DELAY_MILLIS = 24L;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final KbDocumentMapper documentMapper;
    private final ChatSessionService sessionService;
    private final RetrievalService retrievalService;
    private final SpaceAccessService spaceAccessService;
    private final RagProperties props;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor ragStreamExecutor;

    // ---------------------------------------------------------------- 同步问答

    @Override
    public AskResponse ask(AskRequest request) {
        if (StringUtils.isBlank(props.getApiKey())) {
            throw new BizException(5001, "未配置模型密钥，请设置环境变量 RAG_API_KEY 后重启");
        }
        long start = System.currentTimeMillis();

        // 空间权限在进入召回之前判定：越权直接 4030，未选空间则收敛到当前账号可读的空间集合
        List<KbChunk> hits = retrievalService.retrieve(request.question(), topK(request.topK()),
                spaceAccessService.scopeForCurrentUser(request.spaceId()));
        if (hits.isEmpty()) {
            return AskResponse.empty(NO_HIT_TEXT);
        }

        Map<Long, String> titles = loadTitles(hits);
        List<dev.langchain4j.data.message.ChatMessage> messages =
                buildMessages(request.question(), hits, titles, List.of());
        String answer = chatModel.chat(messages).aiMessage().text();

        return new AskResponse(answer, toCitations(hits, titles), System.currentTimeMillis() - start);
    }

    // ---------------------------------------------------------------- 流式问答

    @Override
    public void askStream(AskStreamRequest request, SseEmitter emitter) {
        // ThreadLocal 不跨线程：先在 Tomcat 请求线程上抓住当前登录用户，
        // 再随任务带进业务线程池，否则 doStream 里的会话归属校验会因取不到用户而失败。
        LoginUser current = UserContext.get();
        // 断连 / 超时只置一个协作取消标记：LangChain4j 的 StreamingChatModel 没有暴露取消句柄，
        // 但至少要做到「不再写库、不再往已关闭的连接推事件」，避免半截回答污染会话历史。
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            cancelled.set(true);
            log.warn("SSE 超时结束 question={}", StringUtils.abbreviate(request.question(), 40));
            complete(emitter);
        });
        emitter.onError(e -> {
            cancelled.set(true);
            log.debug("SSE 连接异常（多为前端主动取消）: {}", e.getMessage());
        });
        emitter.onCompletion(() -> {
            cancelled.set(true);
            log.debug("SSE 连接关闭 question={}", StringUtils.abbreviate(request.question(), 40));
        });

        try {
            // 立刻交给业务线程池，Tomcat 请求线程马上释放
            ragStreamExecutor.execute(() -> {
                if (current != null) {
                    UserContext.set(current);
                }
                try {
                    doStream(request, emitter, current, cancelled);
                } finally {
                    // 线程会被复用，务必清理，避免身份串号
                    UserContext.clear();
                }
            });
        } catch (TaskRejectedException e) {
            // 线程池已改成 AbortPolicy：满了就明确拒绝，绝不能把几十秒的流式任务压回 Tomcat 线程
            cancelled.set(true);
            log.warn("流式问答队列已满，拒绝本次请求 question={}",
                    StringUtils.abbreviate(request.question(), 40));
            sendError(emitter, 4290, "当前问答请求过多，请稍后重试");
        }
    }

    private void doStream(AskStreamRequest request, SseEmitter emitter, LoginUser current,
                          AtomicBoolean cancelled) {
        long start = System.currentTimeMillis();
        Long sessionId = request.sessionId();
        try {
            if (cancelled.get()) {
                log.debug("客户端在任务开始前已断开，跳过 session={}", sessionId);
                return;
            }
            boolean mock = props.isMockEnabled();
            if (!mock && StringUtils.isBlank(props.getApiKey())) {
                sendError(emitter, 5001, "未配置模型密钥：请设置环境变量 RAG_API_KEY 后重启后端");
                return;
            }
            // 会话不存在就直接结束，不能把消息写到不存在的会话上
            sessionService.requireSession(sessionId);

            // 空间权限判在落库之前：越权请求不该留下一条「问了但没答」的用户消息污染会话历史与审计，
            // 也保证未授权空间的切片根本不进候选池（scope 会拼进召回 SQL，而不是召回后再筛）
            RetrievalScope scope = spaceAccessService.scopeForCurrentUser(request.spaceId());

            // 1) 先取历史（此时本轮提问还没入库）2) 再落库用户提问
            List<ChatTurn> history = sessionService.loadRecentHistory(sessionId, props.getHistoryMessages());
            // 已经断开就不写用户消息：否则历史里会留下一条永远没有回答的提问
            if (cancelled.get()) {
                log.debug("客户端在落库前断开，本轮提问不写入 session={}", sessionId);
                return;
            }
            sessionService.saveUserMessage(sessionId, request.question());

            int topK = topK(request.topK());
            List<KbChunk> hits = retrievalService.retrieve(request.question(), topK, scope);
            if (cancelled.get()) {
                log.debug("客户端在召回后断开，停止推送 session={}", sessionId);
                return;
            }
            Map<Long, String> titles = loadTitles(hits);
            send(emitter, "meta", Map.of(
                    "retrieved", hits.size(),
                    "topK", topK,
                    "history", history.size(),
                    "sessionId", sessionId,
                    "mock", mock));

            boolean noHit = hits.isEmpty();
            // 只有「既没检索到资料、又没有历史可依据」才走固定提示；
            // 有历史时说明这是一次追问（如“它”“上面第二点”），交给模型基于对话上下文作答。
            if (noHit && history.isEmpty()) {
                streamText(emitter, NO_HIT_TEXT, cancelled);
                if (cancelled.get()) {
                    return;
                }
                send(emitter, "citations", List.of());
                // 没召回也是一轮完整对话，照样落库，否则刷新页面这一问就消失了
                Long savedId = persistAssistant(current, sessionId, NO_HIT_TEXT, List.of());
                sendDone(emitter, start, savedId);
                return;
            }

            // 本轮没检索到新资料时引用为空，但回答仍可能基于历史生成
            List<AskResponse.Citation> citations = noHit ? List.of() : toCitations(hits, titles);
            if (mock) {
                log.warn("rag.mock-enabled=true，本次回答由本地演示逻辑生成，未调用大模型");
                String answer = noHit
                        ? mockFollowUp(request.question(), history.size())
                        : mockAnswer(request.question(), hits, titles, history.size());
                streamText(emitter, answer, cancelled);
                if (cancelled.get()) {
                    return;
                }
                send(emitter, "citations", citations);
                Long mockAnswerId = persistAssistant(current, sessionId, answer, citations);
                sendDone(emitter, start, mockAnswerId);
                return;
            }

            List<dev.langchain4j.data.message.ChatMessage> messages = noHit
                    ? buildFollowUpMessages(request.question(), history)
                    : buildMessages(request.question(), hits, titles, history);
            // 累积模型吐出的增量：落库存的必须是用户实际看到的完整文本
            StringBuilder answerBuffer = new StringBuilder();

            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (StringUtils.isEmpty(partialResponse) || cancelled.get()) {
                        return;
                    }
                    answerBuffer.append(partialResponse);
                    try {
                        send(emitter, "delta", Map.of("text", partialResponse));
                    } catch (SseClosedException e) {
                        // 回调线程抛异常只会被 LangChain4j 记日志，这里自己标记取消，后续增量不再处理
                        cancelled.set(true);
                        log.debug("SSE 已关闭，停止接收模型增量 session={}", sessionId);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    if (cancelled.get()) {
                        log.info("客户端已断开，丢弃本次模型回答 session={}", sessionId);
                        return;
                    }
                    // 引用来源放在回答末尾推送，前端收到后再渲染“参考资料”
                    send(emitter, "citations", citations);
                    String answer = answerBuffer.length() > 0
                            ? answerBuffer.toString()
                            : StringUtils.defaultString(completeResponse.aiMessage().text());
                    Long answerId = persistAssistant(current, sessionId, answer, citations);
                    sendDone(emitter, start, answerId);
                    log.info("流式问答完成 session={} question={} 召回={} 历史={} 耗时={}ms",
                            sessionId, StringUtils.abbreviate(request.question(), 40), hits.size(),
                            history.size(), System.currentTimeMillis() - start);
                }

                @Override
                public void onError(Throwable error) {
                    if (cancelled.get()) {
                        log.debug("客户端已断开，忽略模型回调错误 session={}: {}", sessionId, rootMessage(error));
                        return;
                    }
                    log.error("流式问答模型调用失败 session={}", sessionId, error);
                    // 失败的半截回答不落库：下次提问时它只会污染上下文
                    sendError(emitter, 5002, "模型调用失败：" + rootMessage(error));
                }
            });
        } catch (BizException e) {
            sendError(emitter, e.getCode(), e.getMessage());
        } catch (SseClosedException e) {
            log.debug("客户端已断开，停止推送: {}", e.getMessage());
        } catch (Exception e) {
            log.error("流式问答异常 session={}", sessionId, e);
            sendError(emitter, 5000, "服务异常：" + rootMessage(e));
        }
    }

    /**
     * 回答落库并取回消息主键。
     *
     * <p>第十一部分的「答案反馈 / BadCase 工单」要把评价锚定到这条 assistant 消息上，
     * 所以必须在 {@code done} 事件发出前拿到自增 ID——这里改为同步写：一次 insert 只有几毫秒，
     * 远比把落库丢回线程池再等 Future 划算（线程池被本次流式任务占着，嵌套提交有饥饿风险）。
     *
     * <p>显式带上请求线程快照的用户：{@code onCompleteResponse} 运行在 OkHttp 回调线程上，
     * 那里没有 ThreadLocal 身份。当前持久化不依赖上下文，但把身份补齐能让后续任何
     * 归属校验/审计逻辑不会突然 NPE 或串号。
     *
     * @return 消息 ID；落库失败返回 null，前端据此禁用该条回答的反馈按钮
     */
    private Long persistAssistant(LoginUser user, Long sessionId, String answer,
                                  List<AskResponse.Citation> citations) {
        LoginUser previous = UserContext.get();
        if (user != null) {
            UserContext.set(user);
        }
        try {
            return sessionService.saveAssistantMessage(sessionId, answer, citations);
        } catch (RuntimeException e) {
            // 落库失败不影响已经推给前端的回答，只记日志
            log.error("回答落库失败 session={}", sessionId, e);
            return null;
        } finally {
            // 回调线程同样会被复用（OkHttp 连接池线程），绝不能把身份留在上面
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.set(previous);
            }
        }
    }

    // ---------------------------------------------------------------- SSE 工具

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // 前端取消请求 / 关闭页面都会走到这里，属于正常情况
            throw new SseClosedException(e.getMessage());
        } catch (IllegalStateException e) {
            throw new SseClosedException("emitter 已结束: " + e.getMessage());
        }
    }

    /**
     * 推送 {@code done} 事件收尾。
     *
     * <p>带上 {@code messageId}：前端据此把「这条回答」和后续点赞/点踩绑定到库里的 assistant 消息；
     * 落库失败时为 null，前端会禁用该条回答的反馈入口（Map.of 不接受 null，故用 HashMap）。
     */
    private void sendDone(SseEmitter emitter, long start, Long messageId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("elapsedMillis", System.currentTimeMillis() - start);
        payload.put("messageId", messageId);
        send(emitter, "done", payload);
        complete(emitter);
    }

    private void sendError(SseEmitter emitter, int code, String message) {
        try {
            send(emitter, "error", Map.of("code", code, "message", message));
        } catch (SseClosedException ignored) {
            log.debug("推送错误事件时客户端已断开");
        }
        complete(emitter);
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("emitter complete 忽略异常: {}", e.getMessage());
        }
    }

    /** 演示模式 / 无召回时的逐字推送，每个增量前检查一次协作取消标记 */
    private void streamText(SseEmitter emitter, String text, AtomicBoolean cancelled) {
        for (int i = 0; i < text.length(); i += MOCK_STEP) {
            if (cancelled.get()) {
                return;
            }
            send(emitter, "delta", Map.of("text", text.substring(i, Math.min(text.length(), i + MOCK_STEP))));
            sleep(MOCK_DELAY_MILLIS);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SseClosedException("推送线程被中断");
        }
    }

    // ---------------------------------------------------------------- Prompt 组装

    /**
     * 系统提示（含本轮召回资料）+ 历史对话 + 当前问题。
     *
     * <p>历史用真正的 user/assistant 消息而不是拼进系统提示：模型对多轮结构的理解更好，
     * 也省得自己处理转义。每条历史按 {@code rag.history-max-chars} 截断，防止长回答撑爆上下文。
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            String question, List<KbChunk> hits, Map<Long, String> titles, List<ChatTurn> history) {

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt(buildContext(hits, titles))));
        appendHistory(messages, history);
        messages.add(UserMessage.from(question));
        return messages;
    }

    /**
     * 追问场景（本轮没检索到新资料，但有对话历史）的 Prompt。
     *
     * <p>此时资料区为空，改为要求模型只依据历史里已经出现的信息作答，历史里没有的不要编造。
     * 上一轮的回答本身携带了检索到的内容，因此模型据此回答追问仍是有据可依的。
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildFollowUpMessages(
            String question, List<ChatTurn> history) {

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(FOLLOW_UP_PROMPT));
        appendHistory(messages, history);
        messages.add(UserMessage.from(question));
        return messages;
    }

    /** 把历史对话按时间正序追加成 user/assistant 消息，每条按 history-max-chars 截断 */
    private void appendHistory(List<dev.langchain4j.data.message.ChatMessage> messages, List<ChatTurn> history) {
        for (ChatTurn turn : history) {
            String text = StringUtils.abbreviate(StringUtils.defaultString(turn.content()), props.getHistoryMaxChars());
            if (StringUtils.isBlank(text)) {
                continue;
            }
            messages.add(turn.isAssistant() ? AiMessage.from(text) : UserMessage.from(text));
        }
    }

    private static final String NO_HIT_TEXT = "知识库中没有检索到与该问题相关的内容，暂时无法回答。可以先去「文档管理」上传资料再试。";

    private Map<Long, String> loadTitles(List<KbChunk> hits) {
        List<Long> docIds = hits.stream().map(KbChunk::getDocumentId).distinct().toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, KbDocument::getTitle, (a, b) -> a));
    }

    private List<AskResponse.Citation> toCitations(List<KbChunk> hits, Map<Long, String> titles) {
        return hits.stream()
                .map(c -> new AskResponse.Citation(c.getDocumentId(), titles.get(c.getDocumentId()),
                        c.getChunkIndex(), c.getPageNumber(),
                        StringUtils.abbreviate(c.getContent(), SNIPPET_LENGTH), null))
                .toList();
    }

    private String buildContext(List<KbChunk> hits, Map<Long, String> titles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            KbChunk chunk = hits.get(i);
            sb.append('[').append(i + 1).append("] 来源：")
                    .append(titles.getOrDefault(chunk.getDocumentId(), "未知文档"))
                    // 对外统一用 1 基序号，和前端引用区的“第 N 段”保持一致
                    .append("（片段 ").append(chunk.getChunkIndex() + 1).append("）\n")
                    .append(chunk.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 系统提示 = 防幻觉规则（可运行时配置）+ 本轮召回资料区。
     *
     * <p>规则区取 {@code rag.system-prompt}，留空则回落到内置默认，
     * 这样「引擎参数配置」页里改 Prompt 立刻生效，也不会因为管理员误清空而失去约束。
     */
    private String systemPrompt(String context) {
        String rules = StringUtils.defaultIfBlank(StringUtils.trimToNull(props.getSystemPrompt()), DEFAULT_RULES);
        return rules + "\n# 资料\n" + context;
    }

    /** 内置默认防幻觉规则；与 {@link #FOLLOW_UP_PROMPT} 一样只描述约束，不含资料区 */
    private static final String DEFAULT_RULES = """
            你是一个私有知识库问答助手。请严格遵守：
            1. 只依据下面提供的资料回答，资料中没有的内容不要编造；
            2. 答案中用 [编号] 标注引用的资料条目；
            3. 资料不足以回答时，明确说明“知识库中未收录该内容”；
            4. 用中文回答，结论先行，控制在 300 字以内，除非问题需要展开；
            5. 可以使用 Markdown 排版（列表、加粗、代码块）；
            6. 本轮之前可能附带对话历史，只用于理解追问里的指代（如“它”“上面那条”“再详细点”），
               不要重复回答历史问题；资料只针对当前问题检索，历史与资料冲突时以资料为准。
            """;

    /** 追问场景（本轮无新召回、仅有历史）使用的系统提示，不含资料区 */
    private static final String FOLLOW_UP_PROMPT = """
            你是一个私有知识库问答助手。本轮没有检索到新的资料片段，但下面附带了你们之前的对话历史。
            请严格遵守：
            1. 只依据对话历史中已经出现过的信息回答用户的追问（如“它”“上面第二点”“再详细说说”）；
            2. 历史里没有提到的内容不要编造；若无法确定，明确说明“根据之前的对话无法确定，建议补充资料或重新提问”；
            3. 用中文回答，结论先行，简洁清晰，可使用 Markdown 排版；
            4. 不要整段复述历史，只针对当前追问作答。
            """;

    /** 演示模式下的追问回答：不调模型，给一段可读的占位说明 */
    private String mockFollowUp(String question, int historySize) {
        return "> **演示模式**（`rag.mock-enabled=true`）：未调用大模型。\n\n"
                + "本轮未检索到新的资料片段，但已携带 **%d** 条历史消息用于理解追问「%s」。"
                .formatted(historySize, question)
                + "\n\n配置 `RAG_API_KEY` 并把 `rag.mock-enabled` 设为 `false`，即可让大模型基于对话历史作答。";
    }

    /** 演示模式回答：不调模型，直接把召回片段整理成可读文本，用于验证 SSE 与前端渲染 */
    private String mockAnswer(String question, List<KbChunk> hits, Map<Long, String> titles, int historySize) {
        StringBuilder sb = new StringBuilder();
        sb.append("> **演示模式**（`rag.mock-enabled=true`）：未调用大模型，以下内容来自本地检索结果。\n\n");
        sb.append("关于「").append(question).append("」，知识库中命中 **").append(hits.size()).append("** 个片段");
        sb.append("，本轮携带历史消息 **").append(historySize).append("** 条：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KbChunk chunk = hits.get(i);
            sb.append(i + 1).append(". **")
                    .append(titles.getOrDefault(chunk.getDocumentId(), "未知文档"))
                    .append("** 片段 ").append(chunk.getChunkIndex() + 1).append("：")
                    .append(StringUtils.abbreviate(chunk.getContent().replace("\n", " "), 120))
                    .append("\n");
        }
        sb.append("\n配置 `RAG_API_KEY` 并把 `rag.mock-enabled` 设为 `false`，即可得到大模型生成的正式回答。");
        return sb.toString();
    }

    private int topK(Integer requested) {
        int value = requested == null || requested <= 0 ? props.getTopK() : requested;
        return Math.min(value, 20);
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return StringUtils.defaultIfBlank(cur.getMessage(), cur.getClass().getSimpleName());
    }

    /** 客户端断开时用于中断推送循环 */
    private static class SseClosedException extends RuntimeException {
        SseClosedException(String message) {
            super(message);
        }
    }
}
