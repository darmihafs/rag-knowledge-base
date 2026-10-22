package com.bishe.kb.model.vo;

import java.util.List;

/**
 * 问答质量指标：反馈看板顶部四张卡的数据源。
 *
 * <p>好评率的分母是「全部评价数」，包含未处理的；{@code pendingBadCase} 只统计
 * 「点踩且仍未处理」的工单，是管理员真正要清的队列。问题类型占比只在点踩样本内算，
 * 否则点赞记录会把占比稀释掉。
 */
public record FeedbackStatsVO(
        long totalFeedback,
        long thumbsUp,
        long thumbsDown,
        double positiveRate,
        long pendingBadCase,
        long fixedTickets,
        long ignoredTickets,
        List<IssueTypeCount> issueTypes
) {

    /**
     * 某个问题类型的工单数与占比。
     *
     * @param ratio 0~1 的小数占比，由后端算好，前端只负责格式化成百分数
     */
    public record IssueTypeCount(String issueType, long count, double ratio) {
    }
}
