package com.bishe.kb.service.impl;

import com.bishe.kb.model.vo.AgentAskResponse;
import com.bishe.kb.model.vo.AgentAskResponse.StopReason;
import com.bishe.kb.model.vo.AgentAskResponse.ToolStep;
import com.bishe.kb.model.vo.AskResponse.Citation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 每次请求独立的状态，禁止把对话、引用或工具结果放进单例服务字段。 */
final class AgentRunState {
    private final long started = System.nanoTime();
    private final long budgetMillis;
    private final BooleanSupplier cancelled;
    private final Consumer<ToolStep> onStep;

    /** 只在执行线程上读写；快照不读它，所以不参与同步。 */
    final List<ChatMessage> messages = new ArrayList<>();

    private final List<ToolStep> steps = new ArrayList<>();
    private final Map<String, Citation> citations = new LinkedHashMap<>();
    private int modelCalls;
    private boolean evidence;

    AgentRunState(long budgetMillis, BooleanSupplier cancelled, Consumer<ToolStep> onStep) {
        this.budgetMillis = budgetMillis;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.onStep = onStep;
    }

    boolean cancelled() {
        return cancelled.getAsBoolean();
    }

    boolean expired() {
        return elapsedMillis() >= budgetMillis;
    }

    synchronized void countModelCall() {
        modelCalls++;
    }

    synchronized int stepCount() {
        return steps.size();
    }

    synchronized boolean hasEvidence() {
        return evidence;
    }

    void execute(AgentKnowledgeTools tools, ToolExecutionRequest call, Long spaceId) {
        execute(tools, call, spaceId, false);
    }

    void execute(AgentKnowledgeTools tools, ToolExecutionRequest call, Long spaceId, boolean lexicalOnly) {
        long start = System.nanoTime();
        AgentKnowledgeTools.ToolResult result = tools.execute(call, spaceId, lexicalOnly);
        int stepNumber = stepCount() + 1;
        ToolStep step = new ToolStep(stepNumber, StringUtils.abbreviate(StringUtils.defaultString(call.name()), 100),
                StringUtils.abbreviate(StringUtils.defaultString(call.arguments()), 2000), result.observation(),
                result.success(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        // 状态更新在锁内完成；SSE 推送放到锁外，网络慢不能拖住取消/快照。
        synchronized (this) {
            steps.add(step);
            messages.add(ToolExecutionResultMessage.from(call, result.observation()));
            evidence |= result.hasEvidence();
            result.citations().forEach(c -> citations.putIfAbsent(c.documentId() + ":" + c.chunkIndex(), c));
        }
        if (onStep != null) {
            onStep.accept(step);
        }
    }

    /** 结果快照；同步超时、取消与正常收尾都通过这里拿一致的数据。 */
    synchronized AgentAskResponse finish(String answer, StopReason reason, boolean mock) {
        return new AgentAskResponse(answer, List.copyOf(steps), List.copyOf(citations.values()),
                elapsedMillis(), modelCalls, reason, mock);
    }

    /** 无有效证据：保留工具记录便于排查，但不把无关片段作为引用来源返回。 */
    synchronized AgentAskResponse finishNoEvidence(String answer, boolean mock) {
        return new AgentAskResponse(answer, List.copyOf(steps), List.of(),
                elapsedMillis(), modelCalls, StopReason.NO_EVIDENCE, mock);
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
