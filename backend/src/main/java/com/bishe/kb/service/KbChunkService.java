package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.ChunkCorpusQuery;
import com.bishe.kb.model.dto.ChunkPageQuery;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.vo.ChunkCorpusStatsVO;
import com.bishe.kb.model.vo.ChunkVO;
import com.bishe.kb.model.vo.CorpusChunkVO;

import java.util.List;

/**
 * 文档分段服务：分页查看、人工纠偏（编辑正文 / 启停）、跨文档语料治理。
 *
 * <p>体现「人工干预」能力：管理员可以修正解析错误的切片正文，或临时禁用低质量切片，
 * 被禁用的切片在检索时不再召回。编辑与启停都会同步维护向量库，保证词法路与语义路一致。
 */
public interface KbChunkService extends IService<KbChunk> {

    /** 分页查询某文档下的分段（按 chunkIndex 升序，keyword 可选） */
    Page<ChunkVO> pageChunks(Long documentId, ChunkPageQuery query);

    /** 人工编辑分段正文，同步刷新字符数与向量（启用状态下重嵌入） */
    void updateContent(Long id, String content);

    /**
     * 切换分段启用状态：启用 <-> 禁用。
     * 禁用时从向量库移除，启用时重新写入向量库，保证语义召回与词法召回口径一致。
     *
     * @return 切换后的启用状态（1=启用，0=禁用）
     */
    Integer toggleStatus(Long id);

    /**
     * 跨文档全局分页查询切片（知识运营 / 语料治理用），结果按切片 ID 倒序。
     *
     * <p>支持 spaceId / documentId / enabled / keyword 任意组合；spaceId 先解析成该空间下的
     * 文档 ID 集合再过滤，返回行内补齐所属文档标题与空间名称。
     */
    Page<CorpusChunkVO> pageCorpus(ChunkCorpusQuery query);

    /** 语料中心顶部统计条：全库切片总量与启停分布 */
    ChunkCorpusStatsVO corpusStats();

    /**
     * 批量启用 / 禁用切片，并按目标状态同步向量库（启用写回、禁用移除）。
     *
     * @param ids     要改的切片 ID，忽略其中不存在或已删除的 ID
     * @param enabled 目标状态：1=启用，0=禁用
     * @return 实际被改动的条数
     */
    int batchStatus(List<Long> ids, Integer enabled);
}
