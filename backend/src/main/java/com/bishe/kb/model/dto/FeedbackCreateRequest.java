package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交回答反馈：{@code messageId} 是 SSE {@code done} 事件回传的 assistant 消息主键。
 *
 * <p>{@code userId} 不在入参里——一律取 JWT 身份，防止冒充他人刷工单。
 * 同一条回答重复提交按「改评」处理（赞踩互转），不会产生第二条工单。
 */
public record FeedbackCreateRequest(

        @NotNull(message = "messageId 不能为空")
        Long messageId,

        @NotNull(message = "rating 不能为空")
        @Min(value = -1, message = "rating 只能取 1（赞）或 -1（踩）")
        @Max(value = 1, message = "rating 只能取 1（赞）或 -1（踩）")
        Integer rating,

        /** 问题类型：检索漏检 / 答案幻觉 / 引用不准 / 其他；点赞时留空 */
        @Size(max = 32, message = "问题类型过长")
        String issueType,

        /** 补充说明，可空 */
        @Size(max = 500, message = "反馈说明最多 500 字")
        String comment
) {
}
