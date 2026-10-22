package com.bishe.kb.model.vo;

import java.util.List;

/**
 * 首页数据大盘统计视图对象。
 *
 * <ul>
 *   <li>{@code spaceCount}     —— 知识库空间总数</li>
 *   <li>{@code documentCount}  —— 文档总数</li>
 *   <li>{@code chunkCount}     —— 分段总数</li>
 *   <li>{@code todayQuestions} —— 今日提问数（user 消息）</li>
 *   <li>{@code trend}          —— 近 7 日提问走势（含今日，零填充）</li>
 *   <li>{@code formats}        —— 文档格式分布占比</li>
 * </ul>
 */
public record DashboardStatsVO(
        long spaceCount,
        long documentCount,
        long chunkCount,
        long todayQuestions,
        List<TrendPoint> trend,
        List<FormatSlice> formats
) {
}
