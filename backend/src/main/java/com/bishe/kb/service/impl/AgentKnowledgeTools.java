package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.vo.AskResponse.Citation;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.SpaceAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 每次执行都重查成员关系；模型不能通过参数扩大请求中的空间范围。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentKnowledgeTools {
    private final RetrievalService retrievalService;
    private final SpaceAccessService spaceAccessService;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ObjectMapper mapper;

    public ToolResult execute(ToolExecutionRequest call, Long requestedSpace) {
        return execute(call, requestedSpace, false);
    }

    ToolResult execute(ToolExecutionRequest call, Long requestedSpace, boolean lexicalOnly) {
        UserContext.require(); // 不允许工具直调退化为内部跑批的 unrestricted 语义
        try {
            RetrievalScope scope = spaceAccessService.scopeForCurrentUser(requestedSpace);
            return switch (StringUtils.defaultString(call.name())) {
                case "search_knowledge" -> search(args(call, "query", "limit"), scope, lexicalOnly);
                case "list_documents" -> list(args(call, "keyword", "limit"), scope);
                case "read_chunks" -> read(args(call, "documentId", "startIndex", "limit"), scope);
                default -> error("未知工具，仅支持 search_knowledge、list_documents、read_chunks");
            };
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            // 不把数据库异常、路径、供应商响应等原始细节送回模型或浏览器。
            log.warn("Agent 工具执行失败 tool={} exception={}", call.name(), e.getClass().getSimpleName(), e);
            return error("工具执行失败，请调整参数或稍后重试");
        }
    }

    private AgentToolArguments args(ToolExecutionRequest call, String... allowed) {
        return new AgentToolArguments(mapper, call.arguments(), Set.of(allowed));
    }

    private ToolResult search(AgentToolArguments args, RetrievalScope scope, boolean lexicalOnly) {
        String query = args.text("query", 1000, true);
        int limit = (int) args.number("limit", 3L, 1, 5);
        if (scope.deniesEverything()) {
            return chunks(List.of(), Map.of());
        }
        // 演示模式连 embedding 也不调用；不能只跳过对话模型却暗中产生向量接口费用。
        List<KbChunk> hits = retrievalService.retrieve(query, limit,
                lexicalOnly ? RetrievalService.Route.LEXICAL : RetrievalService.Route.HYBRID, scope);
        if (hits.isEmpty()) {
            return chunks(List.of(), Map.of());
        }
        // 原检索服务的 enabled 过滤之外，再校验 INDEXED/逻辑删除和当前空间范围。
        List<Long> ids = hits.stream().map(KbChunk::getDocumentId).distinct().toList();
        Map<Long, KbDocument> docs = documentMapper.selectList(documents(scope).in(KbDocument::getId, ids))
                .stream().collect(Collectors.toMap(KbDocument::getId, Function.identity()));
        return chunks(hits.stream().filter(c -> Integer.valueOf(1).equals(c.getEnabled()))
                .filter(c -> docs.containsKey(c.getDocumentId())).limit(limit).toList(), docs);
    }

    private ToolResult list(AgentToolArguments args, RetrievalScope scope) {
        String keyword = args.text("keyword", 100, false);
        int limit = (int) args.number("limit", 5L, 1, 10);
        List<DocumentSummary> rows = scope.deniesEverything() ? List.of()
                : documentMapper.selectList(documents(scope)
                        .like(!keyword.isBlank(), KbDocument::getTitle, keyword)
                        .orderByAsc(KbDocument::getId).last("limit " + limit))
                .stream().map(d -> new DocumentSummary(d.getId(), StringUtils.abbreviate(d.getTitle(), 200),
                        d.getChunkCount())).toList();
        return new ToolResult(json(Map.of("documents", rows)), true, !rows.isEmpty(), List.of());
    }

    private ToolResult read(AgentToolArguments args, RetrievalScope scope) {
        long id = args.number("documentId", null, 1, Long.MAX_VALUE);
        int start = (int) args.number("startIndex", 0L, 0, 100000);
        int limit = (int) args.number("limit", 3L, 1, 5);
        KbDocument doc = scope.deniesEverything() ? null
                : documentMapper.selectOne(documents(scope).eq(KbDocument::getId, id));
        if (doc == null) {
            // 不区分不存在与无权，避免枚举文档是否存在。
            return error("文档不可读取（不存在、未索引或不在当前可见范围）");
        }
        List<KbChunk> rows = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, id).eq(KbChunk::getEnabled, 1)
                .ge(KbChunk::getChunkIndex, start).orderByAsc(KbChunk::getChunkIndex)
                .last("limit " + limit));
        return chunks(rows, Map.of(id, doc));
    }

    private LambdaQueryWrapper<KbDocument> documents(RetrievalScope scope) {
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getStatus, KbDocument.Status.INDEXED.name());
        if (scope.spaceId() != null) {
            wrapper.eq(KbDocument::getSpaceId, scope.spaceId());
        }
        if (scope.visibleSpaceIds() != null) {
            // 即便被未来的新调用方绕过空集判断，也不能让 in(empty) 变成全库查询。
            if (scope.visibleSpaceIds().isEmpty()) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.in(KbDocument::getSpaceId, scope.visibleSpaceIds());
            }
        }
        return wrapper;
    }

    private ToolResult chunks(List<KbChunk> rows, Map<Long, KbDocument> docs) {
        List<ChunkEvidence> evidence = rows.stream().map(c -> new ChunkEvidence(c.getDocumentId(),
                StringUtils.abbreviate(docs.get(c.getDocumentId()).getTitle(), 200), c.getChunkIndex(),
                c.getPageNumber(), StringUtils.abbreviate(StringUtils.defaultString(c.getContent()), 1200))).toList();
        List<Citation> citations = evidence.stream().map(c -> new Citation(c.documentId(), c.title(),
                c.chunkIndex(), c.pageNumber(), StringUtils.abbreviate(c.content(), 200), null)).toList();
        return new ToolResult(json(Map.of("chunks", evidence)), true, !evidence.isEmpty(), citations);
    }

    private ToolResult error(String message) {
        return new ToolResult(json(Map.of("error", message)), false, false, List.of());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }

    public record ToolResult(String observation, boolean success, boolean hasEvidence, List<Citation> citations) {
    }

    private record DocumentSummary(Long documentId, String title, Integer chunkCount) {
    }

    private record ChunkEvidence(Long documentId, String title, Integer chunkIndex, Integer pageNumber, String content) {
    }
}
