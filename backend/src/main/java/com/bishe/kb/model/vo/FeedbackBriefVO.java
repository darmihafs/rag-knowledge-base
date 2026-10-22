package com.bishe.kb.model.vo;

/**
 * 当前登录用户对某条回答的既有评价：问答页加载历史后一次性拉取，用来回显赞/踩高亮。
 *
 * <p>只回传展示需要的三列，工单状态与管理员备注属于后台信息，不下放到问答页。
 */
public record FeedbackBriefVO(
        Long messageId,
        Integer rating,
        String issueType,
        String comment
) {
}
