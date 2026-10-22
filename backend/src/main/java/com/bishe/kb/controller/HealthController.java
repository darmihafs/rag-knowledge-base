package com.bishe.kb.controller;

import com.bishe.kb.common.Result;
import com.bishe.kb.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查：确认服务已启动、模型配置是否就绪（不泄露密钥内容）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final RagProperties props;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "chatModel", props.getChatModel(),
                "embeddingEnabled", props.isEmbeddingEnabled(),
                "apiKeyConfigured", props.getApiKey() != null && !props.getApiKey().isBlank()
        ));
    }
}
