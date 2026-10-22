package com.bishe.kb.model.vo;

/**
 * 近 N 日提问走势上的一个数据点。date 为 yyyy-MM-dd，count 为当日提问（user 消息）条数。
 */
public record TrendPoint(
        String date,
        long count
) {
}
