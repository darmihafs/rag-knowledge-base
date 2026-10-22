package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.ChunkBatchStatusRequest;
import com.bishe.kb.model.dto.ChunkCorpusQuery;
import com.bishe.kb.model.vo.ChunkCorpusStatsVO;
import com.bishe.kb.model.vo.CorpusChunkVO;
import com.bishe.kb.model.dto.ChunkUpdateRequest;
import com.bishe.kb.service.KbChunkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分段人工干预与全局语料治理接口（{@code /api/chunks} 前缀在 AuthInterceptor 里已锁为仅管理员）。
 *
 * <ul>
 *   <li>{@code PUT  /api/chunks/{id}}               —— 修改分段正文（同步刷新向量）</li>
 *   <li>{@code PUT  /api/chunks/{id}/toggle-status} —— 启用/禁用该分段，禁用后检索不再召回</li>
 *   <li>{@code GET  /api/chunks/page}               —— 跨文档全局分页，可按空间/文档/状态/正文联合过滤</li>
 *   <li>{@code GET  /api/chunks/corpus-stats}       —— 语料库规模统计（顶部统计条）</li>
 *   <li>{@code POST /api/chunks/batch-status}       —— 批量启用/禁用，同步维护向量库</li>
 * </ul>
 *
 * <p>只看某一篇文档下的分段仍走 {@code GET /api/documents/{id}/chunks/page}（见 DocumentController）。
 */
@RestController
@RequestMapping("/api/chunks")
@RequiredArgsConstructor
public class ChunkController {

    private final KbChunkService chunkService;

    /**
     * 全局切片语料中心：一次查出跨文档的切片，每行带所属文档标题与空间名称。
     *
     * <p>正文可能很长，前端表格默认只展示摘要、点开再看全文；这里不做截断，
     * 由分页 pageSize（上限 100）控制单次数据量。
     */
    @GetMapping("/page")
    public Result<Page<CorpusChunkVO>> page(@Valid ChunkCorpusQuery query) {
        return Result.ok(chunkService.pageCorpus(query));
    }

    /** 统计口径为全库，与筛选无关，前端只在进入页面和批量操作后各取一次 */
    @GetMapping("/corpus-stats")
    public Result<ChunkCorpusStatsVO> corpusStats() {
        return Result.ok(chunkService.corpusStats());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ChunkUpdateRequest request) {
        chunkService.updateContent(id, request.content());
        return Result.ok();
    }

    /** 返回切换后的启用状态：1=启用，0=禁用 */
    @PutMapping("/{id}/toggle-status")
    public Result<Integer> toggleStatus(@PathVariable Long id) {
        return Result.ok(chunkService.toggleStatus(id));
    }

    /** 批量启用 / 禁用，返回实际被改动的条数（已处于目标状态的条目不计） */
    @PostMapping("/batch-status")
    public Result<Integer> batchStatus(@Valid @RequestBody ChunkBatchStatusRequest request) {
        return Result.ok(chunkService.batchStatus(request.ids(), request.enabled()));
    }
}
