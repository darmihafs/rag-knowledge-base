package com.bishe.kb.model.vo;

import java.util.Map;

/**
 * 引擎参数配置视图。
 *
 * <ul>
 *   <li>前面的具名字段 —— 运行时可改、且改完立刻对下一次请求生效的参数；</li>
 *   <li>{@code defaults} —— 进程启动时从 yaml 载入的出厂快照，供「恢复默认配置」比对与展示；</li>
 *   <li>{@code runtime} —— 只读装配信息（模型名、baseUrl、是否启用向量、目录路径等），改了要重启；</li>
 *   <li>{@code hints} —— 每个参数对应的作用范围说明，例如切分粒度只影响之后上传的文档，
 *       免得管理员以为改了参数老切片也会变。</li>
 * </ul>
 */
public record SystemConfigVO(
        int topK,
        int rrfK,
        double semanticMinScore,
        int chunkMaxChars,
        int chunkOverlapChars,
        int historyMessages,
        int historyMaxChars,
        String systemPrompt,
        boolean embeddingEnabled,
        boolean mockEnabled,
        Map<String, Object> defaults,
        Map<String, Object> runtime,
        Map<String, String> hints
) {
}
