package com.bishe.kb.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提问请求（RAG 问答入口的入参）。
 */
public record AskRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题长度不能超过 1000 字")
        String question,

        /**
         * 限定只在该知识库空间内检索，为空（或非正数）则全库检索。
         *
         * <p>字段原名 {@code knowledgeBaseId}，与 {@code kb_space} 落地后的概念同名不同物，
         * 统一成 {@code spaceId} 后三个问答/检索入口的入参口径一致。
         */
        Long spaceId,

        /** 本次召回的分段条数，为空则用配置默认值 */
        Integer topK
) {
}
