package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 问答审计日志的一行：提问用户 + 会话 + 问题 + 回答摘要 + 引用切片数。
 *
 * <p>{@code answerSummary} 只做列表展示（截断），完整回答走详情接口，
 * 避免一页 20 条各自几 KB 的回答把审计列表撑得很大。
 */
public record AuditMessageVO(
        Long messageId,
        Long sessionId,
        String sessionTitle,
        Long userId,
        String username,
        String nickname,
        String question,
        String answerSummary,
        Integer citationCount,
        LocalDateTime askTime
) {
}
