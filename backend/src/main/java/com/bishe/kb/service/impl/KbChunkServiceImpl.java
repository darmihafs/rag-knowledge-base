package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.mapper.KbSpaceMapper;
import com.bishe.kb.model.dto.ChunkCorpusQuery;
import com.bishe.kb.model.dto.ChunkPageQuery;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.vo.ChunkCorpusStatsVO;
import com.bishe.kb.model.vo.ChunkVO;
import com.bishe.kb.model.vo.CorpusChunkVO;
import com.bishe.kb.service.KbChunkService;
import com.bishe.kb.service.VectorStoreEngine;
import com.bishe.kb.util.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档分段服务实现：分页查看 + 人工纠偏（编辑正文 / 启停）+ 跨文档语料治理，并同步维护向量库。
 *
 * <p>向量一致性策略：
 * <ul>
 *   <li>禁用分段 —— 从向量库移除该 chunkId，语义路不再召回；词法路由 {@code enabled=1} 过滤兜底。</li>
 *   <li>启用分段 —— 重新写入向量库，语义路恢复召回。</li>
 *   <li>编辑正文 —— 先移除旧向量，若处于启用态再按新正文重新嵌入，避免旧语义残留。</li>
 * </ul>
 * 所有向量操作都由 {@link VectorStoreEngine} 内部降级保护，未开启向量或接口异常都不会阻断本次数据库改动。
 *
 * <p>写路径都带事务，向量同步一律排在 {@code afterCommit}（见
 * {@link com.bishe.kb.util.AfterCommitRunner}）。理由是这三处都是「数据库改完再改向量库」，
 * 没有事务时 UPDATE 逐条自动提交、向量库却可能在后半段失败，500 条的批量启停会留下
 * 「一半切片已提交、向量库整批没同步」的半态；排在提交之后至少保证 DB 侧要么全成要么全废，
 * 而向量侧的缺失只影响语义召回，词法路的 {@code enabled=1} 过滤仍然兜得住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbChunkServiceImpl extends ServiceImpl<KbChunkMapper, KbChunk> implements KbChunkService {

    /** 单次批量启停允许的最大条数：再大就该按文档整篇重跑，而不是逐条点 */
    private static final int BATCH_STATUS_LIMIT = 500;

    private final VectorStoreEngine vectorStoreEngine;
    private final KbDocumentMapper documentMapper;
    private final KbSpaceMapper spaceMapper;

    @Override
    public Page<ChunkVO> pageChunks(Long documentId, ChunkPageQuery query) {
        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId)
                .like(StringUtils.isNotBlank(query.keyword()), KbChunk::getContent, query.keyword())
                .orderByAsc(KbChunk::getChunkIndex);

        Page<KbChunk> page = page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<ChunkVO> records = page.getRecords().stream().map(this::toVo).toList();

        Page<ChunkVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContent(Long id, String content) {
        KbChunk chunk = getById(id);
        if (chunk == null) {
            throw new BizException(4040, "分段不存在: " + id);
        }
        if (StringUtils.isBlank(content)) {
            throw new BizException("分段内容不能为空");
        }
        chunk.setContent(content);
        chunk.setCharCount(content.length());
        updateById(chunk);

        // 正文变了，旧向量已失效：先移除，启用态下再按新正文重新嵌入。
        // 排到提交之后：新正文还没落库时不能先把旧向量删掉，否则这段内容会在两条召回路上同时消失。
        boolean enabled = isEnabled(chunk);
        chunk.setSpaceId(spaceIdOf(chunk.getDocumentId()));
        AfterCommitRunner.run(() -> {
            vectorStoreEngine.removeChunk(id);
            if (enabled) {
                vectorStoreEngine.indexChunks(List.of(chunk));
            }
        });
        log.info("人工编辑分段完成 id={} 新字符数={} enabled={}", id, content.length(), chunk.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer toggleStatus(Long id) {
        KbChunk chunk = getById(id);
        if (chunk == null) {
            throw new BizException(4040, "分段不存在: " + id);
        }
        // 历史数据 enabled 可能为 null，按启用处理
        boolean currentlyEnabled = isEnabled(chunk);
        int next = currentlyEnabled ? 0 : 1;
        chunk.setEnabled(next);
        updateById(chunk);

        // 与批量启停同一套顺序：enabled 先落库并提交，再动向量库
        chunk.setSpaceId(spaceIdOf(chunk.getDocumentId()));
        AfterCommitRunner.run(() -> {
            if (next == 1) {
                // 重新启用：把分段写回向量库，语义召回恢复
                vectorStoreEngine.indexChunks(List.of(chunk));
            } else {
                // 禁用：从向量库移除，语义召回不再命中
                vectorStoreEngine.removeChunk(id);
            }
        });
        log.info("切换分段状态 id={} enabled={}", id, next);
        return next;
    }

    private boolean isEnabled(KbChunk chunk) {
        return chunk.getEnabled() == null || chunk.getEnabled() == 1;
    }

    /** 单条切片所属空间：向量元数据要在重新嵌入时带上它，语义召回才能做空间前置过滤 */
    private Long spaceIdOf(Long documentId) {
        if (documentId == null) {
            return null;
        }
        KbDocument doc = documentMapper.selectById(documentId);
        return doc == null ? null : doc.getSpaceId();
    }

    /** 批量补齐切片所属空间；一次反查文档，避免逐条查询 */
    private void attachSpaceIds(List<KbChunk> chunks) {
        Set<Long> documentIds = chunks.stream().map(KbChunk::getDocumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return;
        }
        Map<Long, Long> spaceByDocument = new HashMap<>();
        // 手工遍历而不是 Collectors.toMap：历史文档的 space_id 可能为 null，toMap 遇 null value 会抛 NPE
        documentMapper.selectBatchIds(documentIds)
                .forEach(doc -> spaceByDocument.put(doc.getId(), doc.getSpaceId()));
        chunks.forEach(chunk -> chunk.setSpaceId(spaceByDocument.get(chunk.getDocumentId())));
    }

    // ------------------------------------------------------------------ 全局语料中心

    @Override
    public Page<CorpusChunkVO> pageCorpus(ChunkCorpusQuery query) {
        // 空间维度先解析成文档 ID 集合：kb_chunk 上没有 spaceId，跨表过滤只能在内存里换算
        List<Long> spaceDocIds = Collections.emptyList();
        if (query.spaceId() != null) {
            spaceDocIds = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                            .select(KbDocument::getId)
                            .eq(KbDocument::getSpaceId, query.spaceId()))
                    .stream().map(KbDocument::getId).toList();
            if (spaceDocIds.isEmpty()) {
                // 该空间下还没有文档，直接给空页，省掉一次必然为空的切片查询
                return new Page<>(query.pageNum(), query.pageSize(), 0);
            }
        }

        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<KbChunk>()
                .in(!spaceDocIds.isEmpty(), KbChunk::getDocumentId, spaceDocIds)
                .eq(query.documentId() != null, KbChunk::getDocumentId, query.documentId())
                .like(StringUtils.isNotBlank(query.keyword()), KbChunk::getContent, StringUtils.trimToNull(query.keyword()));
        applyEnabledFilter(wrapper, query.enabled());
        wrapper.orderByDesc(KbChunk::getId);

        Page<KbChunk> page = page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        Page<CorpusChunkVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(withOwnership(page.getRecords()));
        return result;
    }

    @Override
    public ChunkCorpusStatsVO corpusStats() {
        long total = count();
        long enabled = count(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getEnabled, 1).or().isNull(KbChunk::getEnabled));
        long indexedDocuments = documentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .gt(KbDocument::getChunkCount, 0));
        long activeSpaces = spaceMapper.selectCount(new LambdaQueryWrapper<KbSpace>()
                .gt(KbSpace::getDocCount, 0));
        return new ChunkCorpusStatsVO(total, enabled, total - enabled, indexedDocuments, activeSpaces);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchStatus(List<Long> ids, Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BizException(4004, "enabled 只能取 1（启用）或 0（禁用）");
        }
        List<Long> distinctIds = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new BizException(4001, "请至少选择一条切片");
        }
        if (distinctIds.size() > BATCH_STATUS_LIMIT) {
            throw new BizException(4001, "单次批量操作最多 " + BATCH_STATUS_LIMIT + " 条，请分批处理");
        }

        List<KbChunk> chunks = listByIds(distinctIds);
        if (chunks.isEmpty()) {
            throw new BizException(4040, "所选切片不存在或已被清理");
        }
        List<KbChunk> changed = new ArrayList<>(chunks.size());
        List<Long> changedIds = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            // 已处于目标状态的条目跳过：既少写一次 UPDATE，也避免无谓地重嵌向量
            if (!Objects.equals(chunk.getEnabled() == null ? 1 : chunk.getEnabled(), enabled)) {
                chunk.setEnabled(enabled);
                changed.add(chunk);
                changedIds.add(chunk.getId());
            }
        }
        if (changed.isEmpty()) {
            log.info("批量启停无实际变更 请求={} 条，目标状态={}（所选切片已处于该状态）", distinctIds.size(), enabled);
            return 0;
        }
        updateBatchById(changed);
        // 先把 id、实体快照与空间归属定下来：提交后才执行向量同步，不能依赖之后还可能被改的集合
        attachSpaceIds(changed);
        List<KbChunk> toSync = List.copyOf(changed);
        List<Long> idsToUnindex = List.copyOf(changedIds);

        // 与单条启停同一套策略与顺序：enabled 批量落库并提交，之后才动向量库。
        // 禁用走批量入口——删完只序列化一次，避免 N 条切片触发 N 次整库重写 JSON。
        // 排在提交后还有个附带好处：最长的那段公网 embedding 调用不再占着数据库连接。
        AfterCommitRunner.run(() -> {
            if (enabled == 1) {
                vectorStoreEngine.indexChunks(toSync);
            } else {
                vectorStoreEngine.removeChunks(idsToUnindex);
            }
        });
        log.info("批量切换切片状态完成 目标enabled={} 请求={} 实际变更={}", enabled, distinctIds.size(), changed.size());
        return changed.size();
    }

    /**
     * 启用状态过滤：历史数据 {@code enabled} 可能为 null（语义等同启用），
     * 因此「看启用中」必须写成 {@code enabled = 1 OR enabled IS NULL}，否则会漏掉老切片。
     */
    private void applyEnabledFilter(LambdaQueryWrapper<KbChunk> wrapper, Integer enabled) {
        if (enabled == null) {
            return;
        }
        if (enabled == 1) {
            wrapper.and(w -> w.eq(KbChunk::getEnabled, 1).or().isNull(KbChunk::getEnabled));
        } else {
            wrapper.eq(KbChunk::getEnabled, 0);
        }
    }

    /**
     * 给当页切片补齐「所属文档 / 所属空间」：两次批量查询（文档、空间）在内存里建索引，
     * 不逐行回查，避免一页 20 条产生 40 次查询的 N+1。
     */
    private List<CorpusChunkVO> withOwnership(List<KbChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        Set<Long> documentIds = chunks.stream().map(KbChunk::getDocumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, KbDocument> documents = documentIds.isEmpty() ? Map.of()
                : documentMapper.selectBatchIds(documentIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, d -> d, (a, b) -> a));

        Set<Long> spaceIds = documents.values().stream().map(KbDocument::getSpaceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> spaceNames = new HashMap<>();
        if (!spaceIds.isEmpty()) {
            spaceMapper.selectBatchIds(spaceIds).forEach(s -> spaceNames.put(s.getId(), s.getName()));
        }

        return chunks.stream().map(c -> {
            KbDocument doc = c.getDocumentId() == null ? null : documents.get(c.getDocumentId());
            Long spaceId = doc == null ? null : doc.getSpaceId();
            String spaceName = spaceId == null ? null : spaceNames.get(spaceId);
            return new CorpusChunkVO(c.getId(), c.getDocumentId(),
                    doc == null ? CorpusChunkVO.MISSING_DOCUMENT : doc.getTitle(),
                    spaceId,
                    spaceName == null ? CorpusChunkVO.MISSING_SPACE : spaceName,
                    doc == null ? null : doc.getFileType(),
                    c.getChunkIndex(), c.getPageNumber(), c.getCharCount(),
                    c.getEnabled() == null ? 1 : c.getEnabled(), c.getContent(), c.getCreateTime());
        }).toList();
    }

    private ChunkVO toVo(KbChunk c) {
        return new ChunkVO(c.getId(), c.getDocumentId(), c.getChunkIndex(), c.getPageNumber(),
                c.getCharCount(), c.getEnabled() == null ? 1 : c.getEnabled(), c.getContent());
    }
}
