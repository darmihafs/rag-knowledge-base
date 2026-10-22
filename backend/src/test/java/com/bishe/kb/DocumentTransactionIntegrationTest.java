package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.KbDocumentService;
import com.bishe.kb.service.impl.VectorIndexService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bishe.kb.model.entity.KbDocument;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档删除与失败入库的一致性测试：数据库能回滚，磁盘文件与向量库不能。
 *
 * <p>因此副作用必须排在事务提交之后。回滚用例里同时断言三件事——
 * 库记录还在、原文文件还在、向量条目还在——只要有一件被提前做掉，就会出现
 * 「文档列表里还有这篇，点开却预览不了、语义还能召回」的破窗状态。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentTransactionIntegrationTest {

    private static final long DOC_ID = 900L;
    private static final long SPACE_ID = 901L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KbDocumentService documentService;

    @Autowired
    private KbChunkMapper chunkMapper;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private RagProperties props;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    /** 这篇文档的“原文文件”，用于验证删库与删文件的先后顺序 */
    private Path physicalFile;

    @BeforeEach
    void seed() throws IOException {
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");

        jdbcTemplate.update("insert into kb_space (id, name, description, doc_count, chunk_count, deleted) "
                + "values (?, '事务测试库', '仅用于一致性用例', 1, 2, 0)", SPACE_ID);
        Path relative = Paths.get(String.valueOf(DOC_ID)).resolve("20261019").resolve("sample.txt");
        physicalFile = Paths.get(props.getUploadDir()).toAbsolutePath().normalize().resolve(relative);
        Files.createDirectories(physicalFile.getParent());
        Files.writeString(physicalFile, "进程与线程：进程是资源分配的最小单位，线程是 CPU 调度的最小单位。");

        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, file_path, file_size, status, "
                + "chunk_count, deleted) values (?, ?, '事务示例', 'txt', ?, 60, 'INDEXED', 2, 0)",
                DOC_ID, SPACE_ID, relative.toString().replace('\\', '/'));
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (1, ?, 0, '进程是资源分配的最小单位，线程是 CPU 调度的最小单位', 24, 1)", DOC_ID);
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                + "values (2, ?, 1, '线程共享进程的地址空间，切换开销比进程小', 18, 1)", DOC_ID);

        // 让向量库先有这篇文档的两条向量，删除时才看得出「有没有被提前清掉」
        props.setEmbeddingEnabled(true);
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1f, 0f})).toList());
        });
        assertThat(vectorIndexService.indexChunks(chunkMapper.selectList(null))).isEqualTo(2);
        assertThat(vectorCount()).isEqualTo(2);
    }

    @AfterEach
    void cleanup() throws IOException {
        props.setEmbeddingEnabled(false);
        embeddingStore.removeAll();
        Files.deleteIfExists(physicalFile);
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("delete from kb_space");
    }

    @Test
    @DisplayName("事务提交后：库记录、原文文件、向量条目一起消失，空间计数归零")
    void committedRemovalCleansEverything() {
        documentService.removeDocument(DOC_ID);

        assertThat(documentService.getById(DOC_ID)).isNull();
        assertThat(chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, DOC_ID))).isZero();
        assertThat(vectorCount()).isZero();
        assertThat(physicalFile).doesNotExist();
        assertThat(spaceChunkCount()).isZero();
    }

    @Test
    @DisplayName("事务回滚时：库记录、原文文件、向量条目都还在，不会留下“文档在、内容没了”的破窗")
    void rolledBackRemovalTouchesNothingIrreversible() {
        new TransactionTemplate(transactionManager).execute(status -> {
            documentService.removeDocument(DOC_ID);
            // 模拟删除链路后半段出错（例如审计写入失败），前面的删除必须整体作废
            status.setRollbackOnly();
            return null;
        });

        assertThat(documentService.getById(DOC_ID)).isNotNull();
        assertThat(chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, DOC_ID))).isEqualTo(2L);
        assertThat(vectorCount()).isEqualTo(2);
        assertThat(physicalFile).exists();
    }

    @Test
    @DisplayName("解析失败的文档不留切片：检索不看文档状态，FAILED 文档的内容绝不能留在语料里")
    void failedIngestLeavesNoSearchableChunks() {
        // 只有空白的 txt：能落盘、能“解析”，但切不出正文，后台任务走 fail() 收尾分支。
        Long failedDocId = documentService.ingest(new MockMultipartFile(
                "file", "空文档.txt", "text/plain", "   \n\t  ".getBytes(StandardCharsets.UTF_8)), SPACE_ID);
        awaitStatus(failedDocId, KbDocument.Status.FAILED.name());

        KbDocument failed = documentService.getById(failedDocId);
        assertThat(failed.getStatus()).isEqualTo(KbDocument.Status.FAILED.name());
        assertThat(failed.getChunkCount()).isZero();
        assertThat(failed.getErrorMsg()).contains("未解析到文本");
        assertThat(chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, failedDocId))).isZero();
        // 计数与真实文档数保持一致：这篇虽然建了行，却不该给空间贡献任何分段
        assertThat(spaceChunkCount()).isEqualTo(2);
    }

    private void awaitStatus(long documentId, String expected) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbcTemplate.queryForObject(
                    "select status from kb_document where id = ?", String.class, documentId);
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(status).as("文档 %s 应在 10 秒内进入 %s", documentId, expected).isEqualTo(expected);
    }

    /** 向量库条目数：Bean 声明为接口，实现是内存版，只有它带 size() */
    private int vectorCount() {
        return embeddingStore instanceof InMemoryEmbeddingStore<?> store ? store.size() : -1;
    }

    private int spaceChunkCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select chunk_count from kb_space where id = " + SPACE_ID, Integer.class);
        return count == null ? -1 : count;
    }
}
