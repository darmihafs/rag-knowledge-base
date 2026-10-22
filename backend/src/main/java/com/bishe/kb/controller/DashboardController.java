package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.model.vo.ActivityVO;
import com.bishe.kb.model.vo.DashboardHealthVO;
import com.bishe.kb.model.vo.DashboardStatsVO;
import com.bishe.kb.service.DashboardService;
import com.bishe.kb.service.SystemHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页数据大盘接口（{@code /api/dashboard} 前缀在 AuthInterceptor 里已锁为仅管理员）。
 *
 * <ul>
 *   <li>{@code GET /api/dashboard/stats}      —— 核心指标 + 近 7 日提问走势 + 文档格式分布</li>
 *   <li>{@code GET /api/dashboard/components} —— 系统核心组件健康状态（数据库/大模型/向量引擎/向量库文件）</li>
 *   <li>{@code GET /api/dashboard/activity}   —— 最近入库文档与最近问答动态</li>
 * </ul>
 *
 * <p>后两个接口拆成独立请求：指标卡要一眼看完，而健康检查会取一次数据库连接、动态流要多几次批量查询，
 * 分开能让首屏不被体检拖慢，前端也能各自 loading。
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final SystemHealthService systemHealthService;

    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats() {
        return Result.ok(dashboardService.stats());
    }

    @GetMapping("/components")
    public Result<DashboardHealthVO> components() {
        return Result.ok(systemHealthService.health());
    }

    @GetMapping("/activity")
    public Result<ActivityVO> activity() {
        return Result.ok(dashboardService.activity());
    }
}
