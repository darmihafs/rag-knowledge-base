package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.TemplateSaveRequest;
import com.bishe.kb.model.vo.TemplateVO;
import com.bishe.kb.service.PromptTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词模板市场接口。
 *
 * <ul>
 *   <li>{@code GET    /api/templates/active}     —— 聊天页胶囊数据源，所有登录用户可读</li>
 *   <li>{@code GET    /api/admin/templates}      —— 管理端全量列表（含已下架，仅管理员）</li>
 *   <li>{@code POST   /api/admin/templates}      —— 新建模板（仅管理员）</li>
 *   <li>{@code PUT    /api/admin/templates/{id}} —— 修改模板 / 启用下架（仅管理员）</li>
 *   <li>{@code DELETE /api/admin/templates/{id}} —— 删除模板（仅管理员）</li>
 * </ul>
 *
 * <p>问答侧只读、管理侧全权，靠 {@code /api/admin} 前缀在 AuthInterceptor 里天然收敛；
 * {@code /api/templates} 不在管理员前缀内，普通用户才能拿到启用中的模板。
 */
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final PromptTemplateService templateService;

    @GetMapping("/api/templates/active")
    public Result<List<TemplateVO>> active() {
        return Result.ok(templateService.listActive());
    }

    @GetMapping("/api/admin/templates")
    public Result<List<TemplateVO>> list() {
        return Result.ok(templateService.listAll());
    }

    @PostMapping("/api/admin/templates")
    public Result<TemplateVO> create(@Valid @RequestBody TemplateSaveRequest request) {
        return Result.ok(templateService.create(request));
    }

    @PutMapping("/api/admin/templates/{id}")
    public Result<TemplateVO> update(@PathVariable Long id, @Valid @RequestBody TemplateSaveRequest request) {
        return Result.ok(templateService.update(id, request));
    }

    @DeleteMapping("/api/admin/templates/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.ok();
    }
}
