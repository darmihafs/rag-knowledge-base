package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改提示词模板。
 *
 * <p>修改时以路径上的 {@code id} 为准，body 不带 id。
 * {@code promptContent} 里的 {@code {topic}} 只是写给用户看的「此处补主题词」提示，
 * 后端不做变量替换，因此也不强制校验它存在——有些通用指令本来就不需要主题词。
 */
public record TemplateSaveRequest(

        @NotBlank(message = "模板名称不能为空")
        @Size(max = 128, message = "模板名称最多 128 字")
        String title,

        @Size(max = 32, message = "分类名最多 32 字")
        String category,

        @NotBlank(message = "模板内容不能为空")
        @Size(max = 2000, message = "模板内容最多 2000 字")
        String promptContent,

        @Size(max = 256, message = "说明最多 256 字")
        String description,

        @Min(value = 0, message = "enabled 只能取 0 或 1")
        @Max(value = 1, message = "enabled 只能取 0 或 1")
        Integer enabled,

        Integer sortOrder
) {
}
