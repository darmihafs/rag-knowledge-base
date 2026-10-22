package com.bishe.kb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bishe.kb.common.Result;
import com.bishe.kb.model.dto.ChunkPageQuery;
import com.bishe.kb.model.dto.DocumentPageQuery;
import com.bishe.kb.model.vo.ChunkVO;
import com.bishe.kb.model.vo.DocumentVO;
import com.bishe.kb.service.KbChunkService;
import com.bishe.kb.service.KbDocumentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库文档接口：上传入库、分页列表、删除。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final KbDocumentService documentService;
    private final KbChunkService chunkService;

    /**
     * 上传单个文档：请求线程只负责落盘与入队，立即返回文档 ID（状态 PENDING）。
     * PDFBox/Tika 抽取、滑动窗口分块、切片落库与向量化都在独立线程池完成，状态最终变为
     * INDEXED 或 FAILED；前端刷新文档列表即可看到进度。
     *
     * <p>同时保留 POST /api/documents 作为等价入口。队列满时返回业务码 5005，文档保留为 FAILED
     * 并可通过 {@code POST /api/documents/{id}/reindex} 重试。
     */
    @PostMapping({"/upload", ""})
    public Result<Long> upload(@RequestPart("file") MultipartFile file,
                               @RequestParam(required = false) Long spaceId) {
        return Result.ok(documentService.ingest(file, spaceId));
    }

    /** 批量上传，按顺序返回每个文件的文档 ID；spaceId 可选，为空归入默认空间 */
    @PostMapping("/batch")
    public Result<List<Long>> uploadBatch(@RequestPart("files") MultipartFile[] files,
                                          @RequestParam(required = false) Long spaceId) {
        return Result.ok(Arrays.stream(files).map(f -> documentService.ingest(f, spaceId)).toList());
    }

    /**
     * 重新解析：清空旧切片与向量后重新入队。
     *
     * <p>用于 FAILED 文档的恢复，也允许 INDEXED 文档在调整分块参数后重建索引。
     * 文档正在解析中返回 4090；原文已丢失返回 4041。
     */
    @PostMapping("/{id}/reindex")
    public Result<Void> reindex(@PathVariable Long id) {
        documentService.reindex(id);
        return Result.ok();
    }

    @GetMapping
    public Result<Page<DocumentVO>> page(@Valid DocumentPageQuery query) {
        return Result.ok(documentService.pageDocuments(query));
    }

    /** 查看某文档的分段明细，用于核对解析与切片效果 */
    @GetMapping("/{id}/chunks")
    public Result<List<ChunkVO>> chunks(@PathVariable Long id) {
        return Result.ok(documentService.listChunks(id));
    }

    /** 分页查看某文档下的分段，供前端「查看切片」抽屉使用（支持编辑 / 启停） */
    @GetMapping("/{id}/chunks/page")
    public Result<Page<ChunkVO>> chunksPage(@PathVariable Long id, @Valid ChunkPageQuery query) {
        return Result.ok(chunkService.pageChunks(id, query));
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        documentService.removeDocument(id);
        return Result.ok();
    }

    /**
     * 原文预览：按文档 ID 读取本地磁盘原件并以文件流返回，供前端「原文溯源」抽屉内嵌渲染。
     *
     * <p>PDF 以 {@code application/pdf} 返回，浏览器原生查看器可直接渲染并支持 {@code #page} 跳页；
     * 支持 HTTP {@code Range} 分段请求（大 PDF 按需分块加载，命中返回 206 + {@code Content-Range}），
     * 无 Range 时整文件 200 返回；两种情况都带 {@code Accept-Ranges: bytes}。
     *
     * <p>这里直接写 {@link HttpServletResponse} 而不走 HttpMessageConverter：Spring 6.2 下
     * {@code ResponseEntity} 的响应头交给输出消息后是只读的，
     * {@code ResourceRegionHttpMessageConverter} 内部仍会 {@code headers.add(...)} 补 Content-Length，
     * 于是 206 分支必然抛 {@code UnsupportedOperationException}；自己按偏移量拷贝字节流最稳，
     * 也不会把整篇 PDF 读进内存。
     *
     * <p>权限：受 AuthInterceptor 保护，登录用户均可调用——普通用户对回答引用的原文有查看诉求，
     * 故该路径在 RBAC 中被豁免于「文档管理仅管理员」的限制。服务层再按数据可见性收紧一次：
     * 普通用户只能预览已完成索引（{@code INDEXED}）的文档，草稿与解析失败文档仅管理员可看。
     */
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id, @RequestHeader HttpHeaders headers,
                        HttpServletResponse response) throws IOException {
        KbDocumentService.PreviewResource preview = documentService.preparePreview(id);
        long total = preview.contentLength();
        // 解析/鉴权阶段抛出的 BizException 都发生在这里之前，响应尚未提交，全局异常处理仍可回 JSON
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(preview.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(preview.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        // 明确告知浏览器「按我给的 MIME 处理，别猜」：配合服务层不再返回 text/html，
        // 同源预览就不存在「把上传文件当页面执行脚本」的可能。
        // 没有在这里加 CSP sandbox，是为了不干扰 Chrome 内置 PDF 查看器的 #page 跳页。
        response.setHeader("X-Content-Type-Options", "nosniff");

        long start = 0;
        long end = Math.max(0, total - 1);
        List<HttpRange> ranges = headers.getRange();
        if (!ranges.isEmpty() && total > 0) {
            HttpRange range = ranges.get(0);
            start = range.getRangeStart(total);
            end = range.getRangeEnd(total);
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }

        long length = Math.max(0, end - start + 1);
        response.setContentLengthLong(length);
        try (InputStream in = preview.resource().getInputStream();
             OutputStream out = response.getOutputStream()) {
            in.skipNBytes(start);
            writeRange(in, out, length);
            out.flush();
        }
    }

    /** 从流中精确拷贝 length 个字节，忽略底层流提前 EOF */
    private static void writeRange(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
