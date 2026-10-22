package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话入参：title 可空，为空时用「新对话」，首次提问后自动改成问题前 15 字。
 */
public record SessionCreateRequest(

        @Size(max = 64, message = "标题长度不能超过 64 字")
        String title
) {
}
