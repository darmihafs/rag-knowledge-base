package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 运行时配置更新入参。
 *
 * <p>采用「部分更新」语义：字段为 null 表示保持原值不动，因此前端可以只提交被改过的那几项，
 * 不会因为某个输入框没渲染出来就把参数误重置成 0。
 *
 * <p>模型名、API Key、温度这类装配期参数不在这里——它们已经固化进模型客户端 Bean，
 * 改了也不会生效，必须重启后端，因此由 {@code GET /api/admin/config} 以只读区展示。
 */
public record SystemConfigRequest(

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 20, message = "topK 最多 20")
        Integer topK,

        @Min(value = 1, message = "RRF 常数 k 至少为 1")
        @Max(value = 200, message = "RRF 常数 k 最多 200")
        Integer rrfK,

        @Min(value = 0, message = "语义最低相似度不能为负")
        @Max(value = 1, message = "语义最低相似度最多 1")
        Double semanticMinScore,

        @Min(value = 100, message = "切片上限至少 100 字")
        @Max(value = 5000, message = "切片上限最多 5000 字")
        Integer chunkMaxChars,

        @Min(value = 0, message = "重叠字符不能为负")
        Integer chunkOverlapChars,

        @Min(value = 0, message = "历史条数至少为 0")
        @Max(value = 30, message = "历史条数最多 30")
        Integer historyMessages,

        @Min(value = 100, message = "单条历史上限至少 100 字")
        @Max(value = 8000, message = "单条历史上限最多 8000 字")
        Integer historyMaxChars,

        @Size(max = 4000, message = "系统提示最长 4000 字")
        String systemPrompt
) {
}
