package com.bishe.kb.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统核心组件健康总览。
 *
 * @param checkedAt  本次体检时间
 * @param components 各组件明细，顺序即前端展示顺序
 * @param online     处于 ONLINE 的组件数
 * @param total      组件总数（含主动关闭的，前端自己按状态分组即可）
 */
public record DashboardHealthVO(
        LocalDateTime checkedAt,
        List<ComponentStatusVO> components,
        long online,
        long total
) {
}
