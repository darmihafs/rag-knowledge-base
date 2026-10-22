package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.DocumentPageQuery;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.vo.ChunkVO;
import com.bishe.kb.model.vo.DocumentVO;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档服务：上传 -> 解析 -> 分块 -> 落库（向量化开关控制）。
 */
public interface KbDocumentService extends IService<KbDocument> {

    /** 分页查询文档列表 */
    Page<DocumentVO> pageDocuments(DocumentPageQuery query);

    /**
     * 接收上传文件并提交后台解析，返回文档 ID。文档归入默认空间（id=1）。
     *
     * <p>返回时文件已落盘、状态为 {@code PENDING}，解析/分块/向量化完成后才会变成
     * {@code INDEXED}；失败会变成 {@code FAILED} 并写入 errorMsg，可调用 {@link #reindex(Long)} 重试。
     * 入参校验或落盘失败仍会抛 {@link com.bishe.kb.common.BizException}。
     */
    Long ingest(MultipartFile file);

    /**
     * 接收上传文件并归入指定知识库空间后提交后台解析，返回文档 ID。
     * spaceId 为空时归入默认空间。
     */
    Long ingest(MultipartFile file, Long spaceId);

    /**
     * 重新解析一篇已入库或已失败的文档：清空旧切片与向量，重置为 PENDING 后重新入队。
     *
     * <p>PENDING / PARSING 状态拒绝重入；原始文件已丢失时拒绝，避免删掉旧切片却再也建不回来。
     */
    void reindex(Long documentId);

    /**
     * 把上次进程退出时停在 PENDING / PARSING 的文档重新入队（启动恢复）。
     *
     * <p>PARSING 会先原子退回 PENDING；原文缺失的文档标记 FAILED。
     *
     * @return 实际重新入队的文档数
     */
    int requeueInterrupted();

    /** 逻辑删除文档，同时清理其分段 */
    void removeDocument(Long documentId);

    /** 查看某文档的全部分段（按 chunkIndex 升序），用于前端核对解析与切片效果 */
    List<ChunkVO> listChunks(Long documentId);

    /**
     * 组装文档原始文件的预览资源，供 {@code GET /api/documents/{id}/preview} 产出带 Range 的文件流。
     * 文档不存在、无落盘路径或磁盘文件缺失时抛 {@link com.bishe.kb.common.BizException}。
     */
    PreviewResource preparePreview(Long documentId);

    /**
     * 预览资源打包：磁盘文件 {@link Resource} + 建议文件名 + MIME 类型 + 字节数。
     * contentLength 提前算好，控制器据此设置 Content-Length 并让 Spring 自动响应 Range 分段请求。
     */
    record PreviewResource(Resource resource, String fileName, String contentType, long contentLength) {
    }
}
