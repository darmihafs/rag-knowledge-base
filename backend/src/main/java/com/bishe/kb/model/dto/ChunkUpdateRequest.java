package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工编辑分段正文入参。改完后若该分段处于启用状态，会重新向量化以保持一致。
 */
public record ChunkUpdateRequest(

        @NotBlank(message = "分段内容不能为空")
        String content
) {
}
