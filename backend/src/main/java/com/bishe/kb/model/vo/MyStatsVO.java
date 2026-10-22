package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 个人中心的使用统计：本人维度，不含他人数据。
 *
 * @param sessionCount  本人创建的会话数
 * @param questionCount 本人在这些会话里发出的提问数
 * @param answerCount   系统给出的回答数（含未落库失败轮次的差值，以消息表为准）
 * @param feedbackCount 本人提交过的回答评价数（点赞 + 点踩）
 * @param registerTime  账号注册时间
 */
public record MyStatsVO(
        long sessionCount,
        long questionCount,
        long answerCount,
        long feedbackCount,
        long badCaseCount,
        LocalDateTime registerTime
) {
}
