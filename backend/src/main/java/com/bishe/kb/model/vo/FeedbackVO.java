package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * BadCase 工单的一行：反馈本身 + 当时那次问答的原文 + 提交人。
 *
 * <p>{@code question} / {@code answer} 由服务层按 {@code messageId} 反查消息表补齐（提问取该回答
 * 之前最近的一条 user 消息），管理员不必再跳去审计日志对照；消息已被清理时两者为 null，
 * 前端展示「消息已清理」，工单本身仍然保留——它是检索质量的历史证据。
 */
public record FeedbackVO(
        Long id,
        Long messageId,
        Long sessionId,
        Long userId,
        String username,
        String nickname,
        Integer rating,
        String issueType,
        String comment,
        Integer status,
        String adminNote,
        String question,
        String answer,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    /** 消息随会话被物理清理后的兜底文案，工单不因反查失败而丢行 */
    public static final String MISSING_MESSAGE = "消息已清理";
}
