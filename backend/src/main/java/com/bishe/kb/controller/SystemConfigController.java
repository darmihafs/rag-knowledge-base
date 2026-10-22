package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.SystemConfigRequest;
import com.bishe.kb.model.vo.SystemConfigVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.SystemConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 引擎参数与 Prompt 配置接口（仅管理员）。
 *
 * <ul>
 *   <li>{@code GET  /api/admin/config}        —— 当前生效值 + 出厂默认值 + 装配期只读项 + 各参数说明</li>
 *   <li>{@code PUT  /api/admin/config}        —— 部分更新，null 字段保持原值，保存后下一次问答即生效</li>
 *   <li>{@code POST /api/admin/config/reset}  —— 恢复出厂配置</li>
 * </ul>
 *
 * <p>改的是容器内的 {@code RagProperties} 单例，进程重启后回到 {@code application.yml}，
 * 前端「需重启才生效」的提示口径来自 {@link SystemConfigVO#hints()}。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping
    public Result<SystemConfigVO> get() {
        return Result.ok(systemConfigService.current());
    }

    @PutMapping
    public Result<SystemConfigVO> update(@Valid @RequestBody SystemConfigRequest request) {
        LoginUser operator = UserContext.require();
        log.info("管理员更新引擎参数 operator={}", operator.username());
        return Result.ok(systemConfigService.update(request));
    }

    /** 恢复默认走 POST：语义是「执行一次重置动作」，且不带请求体，避免 PUT 空体的歧义 */
    @PostMapping("/reset")
    public Result<SystemConfigVO> reset() {
        LoginUser operator = UserContext.require();
        log.info("管理员恢复出厂配置 operator={}", operator.username());
        return Result.ok(systemConfigService.resetDefaults());
    }
}
