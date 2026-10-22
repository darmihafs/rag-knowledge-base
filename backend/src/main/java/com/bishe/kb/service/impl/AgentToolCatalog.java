package com.bishe.kb.service.impl;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/** 显式白名单；没有反射调用、SQL、网络访问、文件路径或写操作工具。 */
final class AgentToolCatalog {
    static final List<ToolSpecification> SPECIFICATIONS = List.of(
            ToolSpecification.builder().name("search_knowledge")
                    .description("在当前账号可见且本次选定的知识库内检索正文。可改写 query 多次检索；返回来源与片段。")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("query", "检索文本，1..1000 字符")
                            .addIntegerProperty("limit", "可选，1..5，默认 3")
                            .required("query").build()).build(),
            ToolSpecification.builder().name("list_documents")
                    .description("列出范围内已索引的文档，可按标题查找，取得 documentId 后读取片段。只返回前 limit 条。")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("keyword", "可选，标题关键词，最多 100 字符")
                            .addIntegerProperty("limit", "可选，1..10，默认 5")
                            .build()).build(),
            ToolSpecification.builder().name("read_chunks")
                    .description("读取指定文档的已启用片段。不能读未授权空间、失败文档或被禁用的片段。")
                    .parameters(JsonObjectSchema.builder()
                            .addIntegerProperty("documentId", "必填，正整数，来自检索或文档目录")
                            .addIntegerProperty("startIndex", "可选，0..100000，默认 0；片段序号从 0 开始")
                            .addIntegerProperty("limit", "可选，1..5，默认 3")
                            .required("documentId").build()).build());

    private AgentToolCatalog() {
    }
}
