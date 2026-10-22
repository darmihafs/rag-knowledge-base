package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 检索对比实验室入参：同一个问题一次性走词法 / 语义 / 混合三条召回路。
 *
 * <p>topK 为空时回落到 {@code rag.top-k} 的生产默认值，前端只想看默认效果时不必传。
 */
public record RetrieveCompareRequest(

        @NotBlank(message = "question 不能为空")
        String question,

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 20, message = "topK 最多 20")
        Integer topK,

        /** 限定只在该知识库空间内召回，为空（或非正数）则全库召回；三路共用同一个空间 */
        Long spaceId
) {
}
