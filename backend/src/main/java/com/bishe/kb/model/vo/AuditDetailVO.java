package com.bishe.kb.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计详情：一次问答的完整问题、完整回答与引用来源（结构与 SSE 的 citations 事件一致）。
 */
public record AuditDetailVO(
        Long messageId,
        Long sessionId,
        String sessionTitle,
        Long userId,
        String username,
        String nickname,
        String question,
        String answer,
        LocalDateTime askTime,
        List<AskResponse.Citation> citations
) {
}
