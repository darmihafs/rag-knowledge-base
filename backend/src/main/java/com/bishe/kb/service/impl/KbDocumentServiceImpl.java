package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.config.AsyncConfig;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.model.dto.DocumentPageQuery;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.vo.ChunkVO;
import com.bishe.kb.model.vo.DocumentVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.KbDocumentService;
import com.bishe.kb.service.KbSpaceService;
import com.bishe.kb.service.SpaceAccessService;
import com.bishe.kb.util.AfterCommitRunner;
import com.bishe.kb.util.DocumentTextExtractor;
import com.bishe.kb.util.TextChunker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文档入库实现：上传落盘后立即返回，解析 / 分块 / 落库 / 向量化在独立线程池中完成。
 *
 * <p>状态机：{@code PENDING}（已落盘，等后台线程）→ {@code PARSING}（解析中）→
 * {@code INDEXED}（可检索）或 {@code FAILED}（带 errorMsg，可重试）。
 * 状态切换全部用「WHERE status = 期望值」的条件更新完成，天然挡住重复任务与「解析途中被删除」的竞态。
 *
 * <p>向量化由 {@link VectorIndexService} 承担，内部按 {@code rag.embedding-enabled} 决定是否真正调接口，
 * 且失败只告警不抛异常——即便向量服务不可用，文档仍会正常入库并支持词法检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument> implements KbDocumentService {

    /**
     * 上传白名单：只收「会被解析成纯文本入库」的文档格式，<b>不含 html/htm</b>。
     *
     * <p>原因是预览接口以同源身份把原始文件直接回给浏览器，HTML 里内嵌的脚本会在当前站点
     * 上下文执行（读到 {@code localStorage} 里的 JWT），等于把上传入口变成存储型 XSS 的入口。
     * 历史遗留的 HTML 文档仍能预览，但 {@link #contentTypeOf} 会按纯文本返回，只展示源码不执行。
     */
    private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "md", "txt", "csv");
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 批量插入分段的批大小：一次 JDBC 往返写 500 行，兼顾内存与网络包大小 */
    private static final int CHUNK_BATCH_SIZE = 500;

    private final KbChunkMapper chunkMapper;
    private final RagProperties props;
    private final VectorIndexService vectorIndexService;
    private final KbSpaceService spaceService;
    private final SpaceAccessService spaceAccessService;
    private final PlatformTransactionManager transactionManager;
    /** 字段名与 {@link AsyncConfig#DOC_INGEST_EXECUTOR} 对齐，Spring 按参数名在多个线程池 Bean 中选定 */
    private final ThreadPoolTaskExecutor documentIngestExecutor;

    /** 上传根目录的绝对路径，启动时创建并打印，避免出现“文件到底存哪了”的困惑 */
    @PostConstruct
    void initUploadRoot() {
        Path root = uploadRoot();
        try {
            Files.createDirectories(root);
            log.info("文档上传根目录: {}", root);
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", root, e);
        }
    }

    private Path uploadRoot() {
        return Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
    }

    @Override
    public Page<DocumentVO> pageDocuments(DocumentPageQuery query) {
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(query.spaceId() != null, KbDocument::getSpaceId, query.spaceId())
                .like(StringUtils.isNotBlank(query.keyword()), KbDocument::getTitle, query.keyword())
                .orderByDesc(KbDocument::getId);

        Page<KbDocument> page = page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<DocumentVO> records = page.getRecords().stream().map(this::toVo).toList();

        Page<DocumentVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public Long ingest(MultipartFile file) {
        return ingest(file, KbSpaceService.DEFAULT_SPACE_ID);
    }

    @Override
    public Long ingest(MultipartFile file, Long spaceId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        long targetSpaceId = spaceId == null ? KbSpaceService.DEFAULT_SPACE_ID : spaceId;
        String originalName = StringUtils.defaultIfBlank(file.getOriginalFilename(), "unnamed");
        String fileType = StringUtils.lowerCase(StringUtils.substringAfterLast(originalName, "."), Locale.ROOT);
        if (StringUtils.isBlank(fileType) || !ALLOWED_TYPES.contains(fileType)) {
            throw new BizException("不支持的文件类型: " + fileType);
        }
        String title = StringUtils.substringBeforeLast(originalName, ".");
        // 内容哈希在落盘前算：同一文件双击 / 刷新上传时，别再生成一条重复文档和整套重复切片。
        // 键包含标题与类型，允许用户把同一份内容以不同名称归档（例如中英文标题各留一份）。
        String fileHash = sha256Hex(file);
        KbDocument duplicate = getOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSpaceId, targetSpaceId)
                .eq(KbDocument::getTitle, title)
                .eq(KbDocument::getFileType, fileType)
                .eq(KbDocument::getFileHash, fileHash)
                .last("limit 1"));
        if (duplicate != null) {
            throw new BizException(4091, "相同文件已入库（文档 id=" + duplicate.getId()
                    + "），如需重建请使用「重新解析」");
        }

        KbDocument doc = new KbDocument();
        doc.setSpaceId(targetSpaceId);
        doc.setTitle(title);
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setFileHash(fileHash);
        doc.setStatus(KbDocument.Status.PENDING.name());
        doc.setChunkCount(0);
        save(doc);
        // 建行即计入空间文档数；失败文档也保留记录，便于用户看到原因后重试
        spaceService.recount(targetSpaceId);

        Path target = storagePath(doc.getId(), fileType);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException e) {
            fail(doc, "文件落盘失败: " + e.getMessage());
            throw new BizException(5002, "文件保存失败");
        }

        // 库里只存相对 upload-dir 的路径，换机器 / 换部署目录不用改数据
        doc.setFilePath(uploadRoot().relativize(target).toString().replace('\\', '/'));
        if (!updateById(doc)) {
            fail(doc, "文档记录已失效，入库终止");
            throw new BizException(5002, "文件保存失败");
        }

        if (!tryEnqueue(doc.getId())) {
            fail(doc, "解析队列已满，任务未开始执行");
            throw new BizException(5005, "解析队列已满，请稍后重试");
        }
        log.info("文档已提交后台解析 id={} spaceId={} type={} size={}B",
                doc.getId(), targetSpaceId, fileType, file.getSize());
        return doc.getId();
    }

    @Override
    public void reindex(Long documentId) {
        KbDocument doc = getById(documentId);
        if (doc == null) {
            throw new BizException(4040, "文档不存在: " + documentId);
        }
        String status = doc.getStatus();
        if (KbDocument.Status.PENDING.name().equals(status) || KbDocument.Status.PARSING.name().equals(status)) {
            throw new BizException(4090, "文档正在解析中，请稍后再试");
        }
        if (resolveStoredFile(doc) == null) {
            throw new BizException(4041, "原始文件已丢失，无法重新解析");
        }

        // 条件更新 + 删除旧切片放在同一事务：并发重复点击只有一个能成功，且不会留下半套切片
        Boolean reset = new TransactionTemplate(transactionManager).execute(tx -> {
            KbDocument patch = new KbDocument();
            patch.setStatus(KbDocument.Status.PENDING.name());
            patch.setChunkCount(0);
            int updated = baseMapper.update(patch, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId)
                    .in(KbDocument::getStatus,
                            KbDocument.Status.INDEXED.name(), KbDocument.Status.FAILED.name())
                    .set(KbDocument::getErrorMsg, null));
            if (updated == 0) {
                tx.setRollbackOnly();
                return false;
            }
            chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
            spaceService.recount(doc.getSpaceId());
            return true;
        });
        if (!Boolean.TRUE.equals(reset)) {
            KbDocument current = getById(documentId);
            if (current == null) {
                throw new BizException(4040, "文档不存在: " + documentId);
            }
            if (KbDocument.Status.PENDING.name().equals(current.getStatus())
                    || KbDocument.Status.PARSING.name().equals(current.getStatus())) {
                throw new BizException(4090, "文档正在解析中，请稍后再试");
            }
            throw new BizException(4091, "当前状态不允许重新解析: " + current.getStatus());
        }

        // 旧切片已从库里删除，向量库里的旧条目也要清掉；失败只告警，不阻断重新解析
        vectorIndexService.removeDocument(documentId);
        doc.setStatus(KbDocument.Status.PENDING.name());
        if (!tryEnqueue(documentId)) {
            fail(doc, "解析队列已满，任务未开始执行");
            throw new BizException(5005, "解析队列已满，请稍后重试");
        }
        log.info("文档已重新提交解析 id={}", documentId);
    }

    @Override
    public int requeueInterrupted() {
        List<KbDocument> interrupted = list(new LambdaQueryWrapper<KbDocument>()
                .in(KbDocument::getStatus,
                        KbDocument.Status.PENDING.name(), KbDocument.Status.PARSING.name())
                .orderByAsc(KbDocument::getId));
        int requeued = 0;
        for (KbDocument doc : interrupted) {
            if (KbDocument.Status.PARSING.name().equals(doc.getStatus())) {
                // 中断的 PARSING 先退回 PENDING，才能被条件更新原子领取
                KbDocument patch = new KbDocument();
                patch.setStatus(KbDocument.Status.PENDING.name());
                int updated = baseMapper.update(patch, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, doc.getId())
                        .eq(KbDocument::getStatus, KbDocument.Status.PARSING.name()));
                if (updated == 0) {
                    continue;
                }
            }
            if (resolveStoredFile(doc) == null) {
                fail(doc, "服务重启后原文文件缺失，无法继续解析");
                continue;
            }
            if (tryEnqueue(doc.getId())) {
                requeued++;
            } else {
                fail(doc, "重启恢复时解析队列已满，任务未开始执行");
            }
        }
        if (requeued > 0) {
            log.info("启动恢复：重新入队 {} 篇未完成的文档", requeued);
        }
        return requeued;
    }

    /**
     * 后台解析任务。只有把 PENDING 条件更新成 PARSING 成功的线程才真正执行，
     * 重复投递（重启恢复与多实例同时启动）会在这一步被挡住。
     */
    private void process(Long documentId) {
        KbDocument doc = getById(documentId);
        if (doc == null || !KbDocument.Status.PENDING.name().equals(doc.getStatus())) {
            log.info("跳过失效的解析任务 id={} 当前状态={}", documentId, doc == null ? "已删除" : doc.getStatus());
            return;
        }
        if (!claim(doc)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Path file = resolveStoredFile(doc);
            if (file == null) {
                fail(doc, "原文文件缺失，无法解析");
                return;
            }
            // PDF 只遍历一次：既拿到整篇正文用于空内容判定，也拿到逐页文本用于页码定位。
            // 之前「整篇 extract + 逐页 extractPdfPages」会把同一份 PDF 解析两遍，
            // 大文件时 CPU 与堆内存都翻倍。
            boolean pdf = "pdf".equalsIgnoreCase(doc.getFileType());
            List<String> pdfPages = pdf ? DocumentTextExtractor.extractPdfPages(file) : List.of();
            String text = pdf
                    ? String.join("\n\n", pdfPages.stream().map(TextChunker::normalize).toList())
                    : DocumentTextExtractor.extract(file, doc.getFileType());
            if (StringUtils.isBlank(text)) {
                fail(doc, "未解析到文本内容，可能是扫描件或空文件");
                return;
            }

            // PDF 按整篇全局分块再按偏移映射页码：跨页段落能保持连续，不会被页边界硬切断；
            // 其余格式整篇切块、页码为空。
            List<PagedChunk> paged = chunkWithPages(pdf, pdfPages, text,
                    props.getChunkMaxChars(), props.getChunkOverlapChars());
            if (paged.isEmpty()) {
                fail(doc, "分块结果为空");
                return;
            }
            List<KbChunk> savedChunks = publishIndexed(doc, paged);
            if (savedChunks.isEmpty()) {
                log.info("文档在解析期间被删除，分段写入已回滚 id={}", documentId);
                return;
            }

            // 状态与切片已经提交，向量化只是可降级的加速路径：这里单独兜底，
            // 即使实现意外抛出也不能走 fail() 去删已提交的切片。
            // spaceId 随分段带进向量元数据，语义召回才能做空间前置过滤（旧数据靠回退兼容）。
            savedChunks.forEach(chunk -> chunk.setSpaceId(doc.getSpaceId()));
            int indexed;
            try {
                indexed = vectorIndexService.indexChunks(savedChunks);
            } catch (RuntimeException e) {
                indexed = 0;
                log.warn("向量化阶段异常，文档保持 INDEXED 并按词法检索 id={}: {}",
                        documentId, rootMessage(e));
            }
            // 向量路部分失败不能假装成功：文档仍算 INDEXED（词法可查），但把缺口写进 errorMsg，
            // 前端状态标签的 tooltip 会提示“语义召回可能缺失”，管理员据此决定是否重新解析。
            if (vectorIndexService.isEnabled() && indexed < savedChunks.size()) {
                markVectorizationIncomplete(documentId, indexed, savedChunks.size());
            }
            int pageTagged = (int) paged.stream().filter(c -> c.pageNumber() != null).count();
            log.info("文档入库完成 id={} spaceId={} 分段数={} 带页码分段={} 向量化={} 正文字符={} 耗时={}ms",
                    documentId, doc.getSpaceId(), paged.size(), pageTagged, indexed, text.length(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("文档后台解析异常 id={}", documentId, e);
            fail(doc, "入库中断: " + rootMessage(e));
        }
    }

    /** 原子领取任务：PENDING -> PARSING，返回 false 表示已被其它线程领走或文档已删除 */
    private boolean claim(KbDocument doc) {
        KbDocument patch = new KbDocument();
        patch.setStatus(KbDocument.Status.PARSING.name());
        int updated = baseMapper.update(patch, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, doc.getId())
                .eq(KbDocument::getStatus, KbDocument.Status.PENDING.name()));
        if (updated == 0) {
            log.info("解析任务已被其它线程领取 id={}", doc.getId());
            return false;
        }
        doc.setStatus(KbDocument.Status.PARSING.name());
        return true;
    }

    /** 提交后台任务；队列满 / 线程池已关闭时返回 false，由调用方把文档收成 FAILED */
    private boolean tryEnqueue(Long documentId) {
        try {
            documentIngestExecutor.execute(() -> process(documentId));
            return true;
        } catch (TaskRejectedException e) {
            log.warn("解析队列已满，文档未入队 id={}", documentId);
            return false;
        }
    }

    /** 解析失败时给用户看的根因，避免只显示一层包装异常 */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.defaultIfBlank(current.getMessage(), current.getClass().getSimpleName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocument(Long documentId) {
        KbDocument doc = getById(documentId);
        if (doc == null) {
            throw new BizException(4040, "文档不存在: " + documentId);
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, documentId));
        removeById(documentId);
        // 文档已删，重算所属空间计数（同一事务内，读得到上面未提交的删除）
        spaceService.recount(doc.getSpaceId());
        // 磁盘文件与向量库都不是事务资源：留到 afterCommit，事务回滚时不会把还在库里的文档原文删没
        AfterCommitRunner.run(() -> {
            vectorIndexService.removeDocument(documentId);
            deletePhysicalFile(doc);
        });
    }

    /** 删记录的同时清掉磁盘文件，避免 data/uploads 越长越大 */
    private void deletePhysicalFile(KbDocument doc) {
        if (StringUtils.isBlank(doc.getFilePath())) {
            return;
        }
        Path file = uploadRoot().resolve(doc.getFilePath());
        try {
            if (Files.deleteIfExists(file)) {
                log.info("已删除本地文件: {}", file);
            }
        } catch (IOException e) {
            log.warn("本地文件删除失败（不影响库记录）: {}", file, e);
        }
    }

    @Override
    public List<ChunkVO> listChunks(Long documentId) {
        if (getById(documentId) == null) {
            throw new BizException(4040, "文档不存在: " + documentId);
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, documentId)
                        .orderByAsc(KbChunk::getChunkIndex))
                .stream()
                .map(c -> new ChunkVO(c.getId(), c.getDocumentId(), c.getChunkIndex(), c.getPageNumber(),
                        c.getCharCount(), c.getEnabled() == null ? 1 : c.getEnabled(), c.getContent()))
                .toList();
    }

    @Override
    public PreviewResource preparePreview(Long documentId) {
        KbDocument doc = getById(documentId);
        if (doc == null) {
            throw new BizException(4040, "文档不存在: " + documentId);
        }
        requirePreviewVisible(doc);
        if (StringUtils.isBlank(doc.getFilePath())) {
            throw new BizException("该文档没有可预览的原始文件");
        }
        Path root = uploadRoot();
        Path file = root.resolve(doc.getFilePath()).normalize();
        // 防目录穿越：拼接并规范化后必须仍落在上传根目录之内
        if (!file.startsWith(root)) {
            throw new BizException("非法的文件路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new BizException(4040, "原始文件已丢失，无法预览: " + documentId);
        }
        String ext = StringUtils.lowerCase(StringUtils.defaultString(doc.getFileType()), Locale.ROOT);
        String fileName = StringUtils.defaultIfBlank(doc.getTitle(), "document")
                + (StringUtils.isBlank(ext) ? "" : "." + ext);
        long length;
        try {
            length = Files.size(file);
        } catch (IOException e) {
            throw new BizException(5004, "读取文件大小失败");
        }
        return new PreviewResource(new FileSystemResource(file), fileName, contentTypeOf(ext), length);
    }

    /**
     * 预览可见性：先过空间授权，再要求文档「已进入检索语料」。
     *
     * <p>两层各挡一类越权：
     * <ol>
     *   <li>空间授权（{@link SpaceAccessService#requireReadable}）——不属于自己可见空间的文档，
     *       即使是 INDEXED 也不能下载原文。V8 之前问答能跨空间召回，预览自然也能，
     *       所以这一层是随批次四的权限边界一起补上的；</li>
     *   <li>索引状态——挡掉 PENDING / PARSING / FAILED 这些<b>还没进语料的草稿</b>：
     *       它们的正文本来就检索不到，却可以靠枚举自增 id 直接下载原文。</li>
     * </ol>
     *
     * <p>管理员两层都不受限制。
     */
    private void requirePreviewVisible(KbDocument doc) {
        LoginUser current = UserContext.get();
        if (current != null && current.isAdmin()) {
            return;
        }
        spaceAccessService.requireReadable(
                doc.getSpaceId() == null ? KbSpaceService.DEFAULT_SPACE_ID : doc.getSpaceId());
        if (!KbDocument.Status.INDEXED.name().equals(doc.getStatus())) {
            throw new BizException(4030, "该文档尚未完成索引，暂不支持预览");
        }
    }

    /** 按后缀给出预览用的 MIME；PDF 才能被浏览器原生查看器渲染并支持 #page 跳页 */
    private String contentTypeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "txt", "md", "csv" -> "text/plain; charset=UTF-8";
            // 历史遗留的 HTML 文档按纯文本返回：同源 text/html 会被浏览器当页面执行，等于存储型 XSS
            case "html", "htm" -> "text/plain; charset=UTF-8";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }

    /**
     * 批量写入分段：用 MyBatis-Plus 的 {@link Db#saveBatch} 走 JDBC batch，
     * 一篇 100 段的文档由 100 次往返压缩成 1 次，配合连接串上的 rewriteBatchedStatements=true 生效。
     *
     * @return 已落库、带自增主键的分段实体，供后续向量化写入按 id 建立向量库条目
     */
    private List<KbChunk> persistChunks(Long documentId, List<PagedChunk> chunks) {
        List<KbChunk> entities = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            PagedChunk paged = chunks.get(i);
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setPageNumber(paged.pageNumber());
            chunk.setContent(paged.content());
            chunk.setCharCount(paged.content().length());
            chunk.setEnabled(1);
            entities.add(chunk);
        }
        Db.saveBatch(entities, CHUNK_BATCH_SIZE);
        return entities;
    }

    /**
     * 一个事务里提交「切片批量落库 + 文档状态 = INDEXED + 空间计数」。
     *
     * <p>三件事必须同进同退：切片已经能被检索命中、文档状态却还停在 PARSING，用户会看见
     * 「一条查得到内容、却提示尚未完成索引」的文档；文档 chunk_count 已变、空间计数还是旧值，
     * 侧栏的切片总数就会对不上。
     *
     * <p>更新带 {@code status = PARSING} 条件：解析途中文档被删除时更新行数为 0，
     * 事务回滚把刚插入的切片一并撤掉，避免留下一批「文档已删、切片仍可检索」的孤儿数据。
     *
     * <p>这里刻意用 {@link TransactionTemplate} 而不是给整条入库链路加
     * {@code @Transactional}：解析与向量化动辄几秒到几十秒，把整个方法包进事务会长时间占住连接。
     *
     * @return 已落库、带自增主键的分段；文档已被删除时返回空列表
     */
    private List<KbChunk> publishIndexed(KbDocument doc, List<PagedChunk> paged) {
        List<KbChunk> savedChunks = new ArrayList<>(paged.size());
        Boolean committed = new TransactionTemplate(transactionManager).execute(status -> {
            KbDocument patch = new KbDocument();
            patch.setStatus(KbDocument.Status.INDEXED.name());
            patch.setChunkCount(paged.size());
            int updated = baseMapper.update(patch, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, doc.getId())
                    .eq(KbDocument::getStatus, KbDocument.Status.PARSING.name())
                    .set(KbDocument::getErrorMsg, null));
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            savedChunks.addAll(persistChunks(doc.getId(), paged));
            spaceService.recount(doc.getSpaceId());
            return true;
        });
        return Boolean.TRUE.equals(committed) ? savedChunks : List.of();
    }

    /**
     * 页感知分块。
     *
     * <p>PDF 先把逐页文本拼成整篇、<b>全局</b>分块，再按每个切片在全文中的起始偏移映射到页码。
     * 这样跨页的段落能自然延续（不再被页边界硬切断成两个语义半截），
     * 同时每个切片仍带 1 基起始页码，前端点击引用可以精准 {@code #page} 跳转。
     *
     * <p>页码映射依赖「拼接口径」与「分块口径」一致：两边都先做 {@link TextChunker#normalize}，
     * 再用 {@code "\n\n"} 连接。若直接拼原始页文本，空白压缩会让偏移整体错位，页码就会漂。
     *
     * <p>PDF 没切出内容（如扫描件）或非 PDF 时，退回整篇分块、页码留空。
     */
    private List<PagedChunk> chunkWithPages(boolean pdf, List<String> pdfPages, String fullText,
                                            int maxChars, int overlap) {
        if (pdf && !pdfPages.isEmpty()) {
            List<String> pages = pdfPages.stream().map(TextChunker::normalize).toList();
            String joined = String.join("\n\n", pages);
            List<Integer> pageStarts = new ArrayList<>(pages.size());
            int offset = 0;
            for (int i = 0; i < pages.size(); i++) {
                pageStarts.add(offset);
                offset += pages.get(i).length();
                if (i < pages.size() - 1) {
                    offset += 2;
                }
            }
            List<PagedChunk> result = new ArrayList<>();
            for (TextChunker.ChunkSlice slice : TextChunker.chunkWithOffsets(joined, maxChars, overlap)) {
                result.add(new PagedChunk(slice.content(), pageAt(pageStarts, slice.startOffset())));
            }
            if (!result.isEmpty()) {
                return result;
            }
            // 逐页没切出内容，走整篇兜底
        }
        List<PagedChunk> result = new ArrayList<>();
        for (String chunk : TextChunker.chunk(fullText, maxChars, overlap)) {
            result.add(new PagedChunk(chunk, null));
        }
        return result;
    }

    /** 起始偏移落在哪一页（pageStarts 升序，返回 1 基页码） */
    private static int pageAt(List<Integer> pageStarts, int offset) {
        int page = 1;
        for (int i = 0; i < pageStarts.size(); i++) {
            if (pageStarts.get(i) <= offset) {
                page = i + 1;
            } else {
                break;
            }
        }
        return page;
    }

    /** 带页码的分段：pageNumber 为 null 表示该来源无稳定页码（非 PDF / 兜底路径） */
    private record PagedChunk(String content, Integer pageNumber) {
    }

    /**
     * 标记文档失败并清掉这篇文档已落库的分段。
     *
     * <p>删除切片不是可选的收尾：检索只按 {@code enabled=1} 过滤，<b>不看文档状态</b>，
     * 失败文档留下的半截切片照样会进 Prompt——用户看到的答案来自一篇列表里写着「失败」的文档。
     *
     * <p>但「失败」不能覆盖已经提交的成功：状态更新带 {@code status != INDEXED} 条件，
     * 更新 0 行时说明文档已删除或已完成索引（例如切片已提交、向量化阶段才出异常），
     * 此时<b>不能再删切片</b>——否则会留下「状态 INDEXED、正文却为空」的文档。
     *
     * <p>失败标记本身也必须尽量成功：后台线程抛出的异常没有 HTTP 调用方，
     * 这里再失败就只能记日志，绝不让异常逃出线程把状态永久卡在 PARSING。
     */
    private void fail(KbDocument doc, String reason) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                KbDocument patch = new KbDocument();
                patch.setStatus(KbDocument.Status.FAILED.name());
                patch.setChunkCount(0);
                int updated = baseMapper.update(patch, new LambdaUpdateWrapper<KbDocument>()
                        .eq(KbDocument::getId, doc.getId())
                        .ne(KbDocument::getStatus, KbDocument.Status.INDEXED.name())
                        .set(KbDocument::getErrorMsg, StringUtils.abbreviate(reason, 500)));
                if (updated == 0) {
                    log.info("跳过失败标记 id={}：文档已删除或已完成索引，原因={}", doc.getId(), reason);
                    return;
                }
                int purged = chunkMapper.delete(
                        new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, doc.getId()));
                spaceService.recount(doc.getSpaceId());
                log.warn("文档入库失败 id={} 原因={} 已清理分段={} 条", doc.getId(), reason, purged);
            });
        } catch (RuntimeException e) {
            log.error("标记文档失败状态时出错 id={} 原因={}", doc.getId(), reason, e);
        }
    }

    /**
     * 向量化只完成了一部分：文档仍是 INDEXED（词法检索可用），但把缺口写进 errorMsg，
     * 让「已入库」不再是一个假成功。条件带 status=INDEXED，避免覆盖并发删除/失败状态。
     */
    private void markVectorizationIncomplete(Long documentId, int indexed, int total) {
        try {
            baseMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, documentId)
                    .eq(KbDocument::getStatus, KbDocument.Status.INDEXED.name())
                    .set(KbDocument::getErrorMsg, StringUtils.abbreviate(
                            "向量化仅完成 " + indexed + "/" + total
                                    + " 条，语义召回可能缺失；词法检索不受影响，可重新解析", 500)));
            log.warn("向量化未完成 id={} {}/{}，已写入提示并保持 INDEXED", documentId, indexed, total);
        } catch (RuntimeException e) {
            log.warn("写入向量化缺口提示失败 id={}: {}", documentId, e.getMessage());
        }
    }

    private DocumentVO toVo(KbDocument doc) {
        return new DocumentVO(doc.getId(), doc.getSpaceId(), doc.getTitle(), doc.getFileType(), doc.getFileSize(),
                doc.getStatus(), doc.getChunkCount(),
                doc.getErrorMsg(), doc.getCreateTime());
    }

    /**
     * 解析原始文件位置：相对路径拼接后必须仍落在 upload-dir 内（防目录穿越），
     * 且确实是普通文件。返回 null 表示记录存在但原文已经丢了。
     */
    private Path resolveStoredFile(KbDocument doc) {
        if (StringUtils.isBlank(doc.getFilePath())) {
            return null;
        }
        Path root = uploadRoot();
        Path file = root.resolve(doc.getFilePath()).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? file : null;
    }

    /** {upload-dir}/{文档ID}/{yyyyMMdd}/{uuid}.{ext} */
    private Path storagePath(Long documentId, String fileType) {
        return uploadRoot()
                .resolve(String.valueOf(documentId))
                .resolve(LocalDate.now().format(DIR_FORMAT))
                .resolve(UUID.randomUUID().toString().replace("-", "") + "." + fileType);
    }

    /** 流式计算 SHA-256，不把整份文件读进堆内存 */
    private static String sha256Hex(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("计算上传文件摘要失败: {}", file.getOriginalFilename(), e);
            throw new BizException(5002, "计算文件摘要失败，请重新上传");
        }
    }
}
