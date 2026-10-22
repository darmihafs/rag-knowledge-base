package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 编辑知识库空间入参。新增与编辑复用同一结构。
 */
public record SpaceSaveRequest(

        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128 字")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512 字")
        String description
) {
}
