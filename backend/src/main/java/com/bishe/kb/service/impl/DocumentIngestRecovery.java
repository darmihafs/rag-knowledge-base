package com.bishe.kb.service.impl;

import com.bishe.kb.service.KbDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：进程退出时停在 PENDING / PARSING 的文档不会自己继续，必须重新入队。
 *
 * <p>放在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：此时数据源、Mapper、
 * 线程池都已就绪，恢复任务不会与容器启动过程抢资源。恢复失败只记日志，绝不阻断应用启动——
 * 文档列表里仍能看到这些文档，用户可以手动点「重新解析」。
 *
 * <p>多实例同时启动会各自投递一次，但工作线程用「PENDING → PARSING」条件更新领取任务，
 * 同一篇文档只会被真正解析一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestRecovery {

    private final KbDocumentService documentService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedIngest() {
        try {
            int requeued = documentService.requeueInterrupted();
            if (requeued > 0) {
                log.info("启动恢复完成：{} 篇未完成文档已重新入队", requeued);
            }
        } catch (RuntimeException e) {
            log.error("启动恢复文档解析任务失败，可稍后在文档列表手动重新解析", e);
        }
    }
}
