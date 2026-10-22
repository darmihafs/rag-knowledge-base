package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 流式提问入参。
 *
 * <p>与 {@link AskRequest} 分开的原因：多轮问答必须绑定会话才能落库和拼上下文，
 * sessionId 在这里是必填项；而同步 {@code /api/knowledge/ask} 是无状态的调试接口，
 * 不该被强制要求带会话。
 */
public record AskStreamRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题长度不能超过 1000 字")
        String question,

        @NotNull(message = "sessionId 不能为空，请先创建或选择一个会话")
        Long sessionId,

        /** 本次召回的分段条数，为空则用配置默认值 */
        Integer topK,

        /** 限定只在该知识库空间内检索，为空（或非正数）则全库检索 */
        Long spaceId
) {
}
