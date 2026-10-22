package com.bishe.kb.service.impl;

import com.bishe.kb.model.vo.AgentAskResponse;
import com.bishe.kb.model.vo.AgentAskResponse.StopReason;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.service.RetrievalScope;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 一次 Agent 运行的服务端句柄：注册表用它取消任务，看门狗用它做硬超时。
 *
 * <p>结果只能由 {@link #publish(Supplier)} 发布一次，保证「模型正常收尾」「90 秒硬超时」
 * 「用户主动取消」「客户端断连」四个来源不会重复推送 done。状态快照本身在
 * {@link AgentRunState} 上加锁，因此超时线程可以读取执行线程已产生的步骤。
 */
final class AgentTask {
    final String id;
    final LoginUser login;
    final Long userId;
    final Long spaceId;
    final String question;
    final int maxSteps;
    final boolean mock;
    final RetrievalScope initialScope;
    /** 同步接口为 null；流式接口持有 SseEmitter。 */
    final SseEmitter emitter;

    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile AgentRunState state;
    private volatile AgentAskResponse terminalResult;
    private volatile Future<?> future;
    private volatile ScheduledFuture<?> watchdog;

    AgentTask(String id, LoginUser login, Long spaceId, String question, int maxSteps, boolean mock,
              RetrievalScope initialScope, SseEmitter emitter) {
        this.id = id;
        this.login = login;
        this.userId = login.id();
        this.spaceId = spaceId;
        this.question = question;
        this.maxSteps = maxSteps;
        this.mock = mock;
        this.initialScope = initialScope;
        this.emitter = emitter;
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean isTerminal() {
        return terminal.get();
    }

    void cancel() {
        cancelled.set(true);
    }

    void future(Future<?> future) {
        this.future = future;
    }

    void watchdog(ScheduledFuture<?> watchdog) {
        this.watchdog = watchdog;
    }

    Future<?> future() {
        return future;
    }

    /**
     * 发布唯一终态。
     *
     * @return 本次调用真正发布的结果；已经有其它来源收尾时返回 null
     */
    synchronized AgentAskResponse publish(Supplier<AgentAskResponse> supplier) {
        if (terminal.get()) {
            return null;
        }
        AgentAskResponse result = supplier.get();
        terminalResult = result;
        terminal.set(true);
        cancelWatchdog();
        return result;
    }

    /** 只标记终态、不产生结果（流式错误收尾）。 */
    synchronized boolean markTerminal() {
        if (terminal.get()) {
            return false;
        }
        terminal.set(true);
        cancelWatchdog();
        return true;
    }

    synchronized AgentAskResponse result() {
        return terminalResult;
    }

    /** 取一致的步骤快照；执行线程还没创建 state 时返回空结果。 */
    AgentAskResponse snapshot(StopReason reason, String answer) {
        AgentRunState current = state;
        if (current == null) {
            return new AgentAskResponse(answer, List.of(), List.of(), elapsedMillis(), 0, reason, mock);
        }
        return current.finish(answer, reason, mock);
    }

    void state(AgentRunState state) {
        this.state = state;
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** 并发/账号闸门只释放一次：请求线程超时返回与 worker 正常结束可能同时发生。 */
    boolean releaseOnce() {
        return released.compareAndSet(false, true);
    }

    private void cancelWatchdog() {
        ScheduledFuture<?> scheduled = watchdog;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
