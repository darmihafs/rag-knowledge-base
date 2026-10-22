package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.FeedbackCreateRequest;
import com.bishe.kb.model.dto.FeedbackPageQuery;
import com.bishe.kb.model.dto.FeedbackResolveRequest;
import com.bishe.kb.model.entity.ChatFeedback;
import com.bishe.kb.model.vo.FeedbackBriefVO;
import com.bishe.kb.model.vo.FeedbackStatsVO;
import com.bishe.kb.model.vo.FeedbackVO;
import com.bishe.kb.security.LoginUser;

import java.util.List;

/**
 * 问答质量反馈与 BadCase 纠偏工单。
 *
 * <p>知识运营闭环的一条腿：用户对单条回答点赞/点踩 → 点踩带归因进入工单队列 →
 * 管理员在后台看问答原文、定位是检索问题还是生成问题 → 回填处置结论，
 * 再由提示词模板与切片语料中心完成纠偏。
 */
public interface ChatFeedbackService extends IService<ChatFeedback> {

    /**
     * 提交或改评一条回答：同一用户对同一回答只保留一条记录（赞踩互转，重新进入待处理）。
     *
     * @param current 当前登录用户，工单归属只能取 JWT 身份
     */
    FeedbackBriefVO submit(FeedbackCreateRequest request, LoginUser current);

    /** 当前登录用户的既有评价，用于问答页回显高亮；{@code sessionId} 非空时只取该会话 */
    List<FeedbackBriefVO> mine(Long sessionId);

    /** 管理员工单列表：按 rating / status / issueType 过滤，并补齐当时的提问、回答与提交人 */
    Page<FeedbackVO> pageForAdmin(FeedbackPageQuery query);

    /** 管理员处置工单：改状态并写处理备注（非「退回待处理」时备注必填） */
    FeedbackVO resolve(Long id, FeedbackResolveRequest request, LoginUser operator);

    /** 质量指标：总评价数、好评率、待处理 BadCase、已修复/已忽略，以及点踩样本的类型占比 */
    FeedbackStatsVO stats();
}
