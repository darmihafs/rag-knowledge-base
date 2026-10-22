package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.FeedbackCreateRequest;
import com.bishe.kb.model.dto.FeedbackPageQuery;
import com.bishe.kb.model.dto.FeedbackResolveRequest;
import com.bishe.kb.model.vo.FeedbackBriefVO;
import com.bishe.kb.model.vo.FeedbackStatsVO;
import com.bishe.kb.model.vo.FeedbackVO;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.ChatFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问答质量反馈与 BadCase 纠偏工单接口。
 *
 * <p>一个类里跨了两个鉴权域，因为它们是同一业务闭环的两端：
 * <ul>
 *   <li>{@code POST /api/feedback}                     —— 点赞/点踩，登录用户皆可（普通用户的本职工作）</li>
 *   <li>{@code GET  /api/feedback/mine}                —— 回显本人既有评价，登录用户皆可</li>
 *   <li>{@code GET  /api/admin/feedback/page}          —— 工单列表，含当时的提问、回答与提交人（仅管理员）</li>
 *   <li>{@code PUT  /api/admin/feedback/{id}/resolve}  —— 处置工单：改状态 + 写处理备注（仅管理员）</li>
 *   <li>{@code GET  /api/admin/feedback/stats}         —— 质量指标卡与问题类型占比（仅管理员）</li>
 * </ul>
 *
 * <p>鉴权靠 {@code AuthInterceptor} 的 {@code /api/admin} 前缀收敛，本类不再重复判角色；
 * 提交人身份一律取 JWT，前端无法指定 userId。
 */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final ChatFeedbackService feedbackService;

    @PostMapping("/api/feedback")
    public Result<FeedbackBriefVO> submit(@Valid @RequestBody FeedbackCreateRequest request) {
        return Result.ok(feedbackService.submit(request, UserContext.require()));
    }

    /** 问答页切换会话时调用一次，用于把已评价过的回答高亮出来；sessionId 为空表示取本人全部 */
    @GetMapping("/api/feedback/mine")
    public Result<List<FeedbackBriefVO>> mine(@RequestParam(required = false) Long sessionId) {
        return Result.ok(feedbackService.mine(sessionId));
    }

    @GetMapping("/api/admin/feedback/page")
    public Result<Page<FeedbackVO>> page(@Valid FeedbackPageQuery query) {
        return Result.ok(feedbackService.pageForAdmin(query));
    }

    @PutMapping("/api/admin/feedback/{id}/resolve")
    public Result<FeedbackVO> resolve(@PathVariable Long id, @Valid @RequestBody FeedbackResolveRequest request) {
        return Result.ok(feedbackService.resolve(id, request, UserContext.require()));
    }

    @GetMapping("/api/admin/feedback/stats")
    public Result<FeedbackStatsVO> stats() {
        return Result.ok(feedbackService.stats());
    }
}
