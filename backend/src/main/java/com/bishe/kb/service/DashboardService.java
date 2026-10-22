package com.bishe.kb.service;

import com.bishe.kb.model.vo.ActivityVO;
import com.bishe.kb.model.vo.DashboardStatsVO;

/**
 * 首页数据大盘统计服务。
 */
public interface DashboardService {

    /** 汇总核心指标：空间/文档/分段总数、今日提问数、近 7 日提问走势、文档格式分布 */
    DashboardStatsVO stats();

    /** 第二排的动态流：最近入库的文档与最近的问答记录（含提问人），让首页能看出系统此刻在被使用 */
    ActivityVO activity();
}
