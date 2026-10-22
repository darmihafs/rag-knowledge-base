package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.AuditPageQuery;
import com.bishe.kb.model.vo.AuditDetailVO;
import com.bishe.kb.model.vo.AuditMessageVO;
import com.bishe.kb.service.ChatAuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全站问答审计接口（{@code /api/admin} 前缀在 AuthInterceptor 里已锁为仅管理员）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/audit/messages}        —— 分页列出所有人的提问，一行一问</li>
 *   <li>{@code GET /api/admin/audit/messages/{id}}   —— 展开该次提问的完整回答与引用来源</li>
 * </ul>
 *
 * <p>普通用户只能通过 {@code /api/sessions} 看到自己的记录；这里是唯一的跨用户读取入口，
 * 因此必须保持管理员专属。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final ChatAuditService chatAuditService;

    @GetMapping("/messages")
    public Result<Page<AuditMessageVO>> page(@Valid AuditPageQuery query) {
        return Result.ok(chatAuditService.pageMessages(query));
    }

    /** 列表页点击展开详情时调用：返回完整问题、完整回答和解析好的引用切片列表 */
    @GetMapping("/messages/{id}")
    public Result<AuditDetailVO> detail(@PathVariable Long id) {
        return Result.ok(chatAuditService.detail(id));
    }
}
