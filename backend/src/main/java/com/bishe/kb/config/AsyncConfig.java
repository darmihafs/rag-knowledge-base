package com.bishe.kb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * SSE 流式问答的异步线程池。
 *
 * <p>大模型逐字返回是长耗时阻塞调用，绝不能占用 Tomcat 的请求线程，
 * 否则几个并发问答就能把 web 容器线程耗光。
 */
@Configuration
public class AsyncConfig {

    public static final String RAG_STREAM_EXECUTOR = "ragStreamExecutor";
    public static final String DOC_INGEST_EXECUTOR = "documentIngestExecutor";
    public static final String AGENT_EXECUTOR = "agentExecutor";
    public static final String AGENT_WATCHDOG = "agentWatchdog";

    @Bean(RAG_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor ragStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-sse-");
        // 队列满时明确拒绝：调用方立即给前端回「请求过多」，绝不能用 CallerRunsPolicy
        // 把一次几十秒的模型流式调用压回 Tomcat 请求线程——那会连带拖垮所有普通接口。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 文档解析 / 分块 / 向量化的后台线程池。
     *
     * <p>与 SSE 池刻意分开：问答线程池被长连接占满时，上传与入库不能被一起拖死；
     * 反过来，几篇大 PDF 同时解析也只允许固定并发，避免把堆内存打满。
     *
     * <p>队列满时用 {@link ThreadPoolExecutor.AbortPolicy} 明确拒绝：
     * 调用方会把文档标成 FAILED 并返回业务错误，让用户稍后重试，
     * 而不是用 CallerRunsPolicy 把解析压回 Tomcat 请求线程。
     */
    @Bean(DOC_INGEST_EXECUTOR)
    public ThreadPoolTaskExecutor documentIngestExecutor(RagProperties props) {
        int threads = Math.max(1, props.getIngestThreads());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Math.max(0, props.getIngestQueueCapacity()));
        executor.setThreadNamePrefix("doc-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机：正在解析的文档最多等 30 秒，中断的任务由启动恢复重新入队
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Agent 工具循环专用线程池。
     *
     * <p>与 SSE、异步入库再隔离一层：一次 Agent 运行可能连续调用多次模型与检索，
     * 不能占用问答流式线程，也不能挤掉后台文档入库。线程数与信号量一致，
     * 队列满时明确拒绝（由服务返回 4290），绝不把任务甩回 Tomcat 请求线程。
     */
    @Bean(AGENT_EXECUTOR)
    public ThreadPoolTaskExecutor agentExecutor(RagProperties props) {
        int threads = Math.max(1, props.getAgentThreads());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Math.max(0, props.getAgentQueueCapacity()));
        executor.setThreadNamePrefix("kb-agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * Agent 硬超时看门狗：只负责在截止时间强制收尾，不执行模型或工具。
     *
     * <p>单线程足够：每个任务只注册一次「到点回调」，回调本身只是取消 Future、
     * 组装已有步骤并关闭 SSE，不做任何阻塞 I/O。同步接口用 Future.get 超时，
     * 只有流式接口需要这个调度器。
     */
    @Bean(AGENT_WATCHDOG)
    public ThreadPoolTaskScheduler agentWatchdog() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-agent-watch-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
