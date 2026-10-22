package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.KbChunkService;
import com.bishe.kb.service.impl.VectorIndexService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片人工纠偏写路径的一致性测试：数据库回滚时，向量库必须一条都没动过。
 *
 * <p>编辑正文、单条启停、批量启停都是「先改库、再同步向量库」两步。没有事务时第一条 UPDATE
 * 已经自动提交，第二步失败就留下「库里 enabled=0、向量库里还挂着这条语义」的半态——
 * 被禁的违规切片照样能被语义路召回。这里用 {@code setRollbackOnly} 制造后半段失败，
 * 同时断言库里的 {@code enabled} 与向量条目数都回到调用前，证明同步确实排在提交之后。
 *
 * <p>向量召回阈值抬到 0.9（同 {@code VectorBatchOperationsIntegrationTest}）：正交向量的
 * 干扰分段被挡掉，于是「召回为空」等价于「向量确实不在库里」，断言才有区分度。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChunkWriteTransactionIntegrationTest {

    private static final float[] TARGET_VECTOR = {1f, 0f, 0f};
    private static final float[] ORTHOGONAL_VECTOR = {0f, 1f, 0f};
    private static final double RECALL_THRESHOLD = 0.9d;

    /** 固定主键区间，避开同 JVM 内其它用例的自增 ID */
    private static final long DOC_ID = 8201L;
    private static final long CHUNK_A = 82011L;
    private static final long CHUNK_B = 82012L;
    private static final long CHUNK_C = 82013L;

    private static final String QUESTION = "怎么控制发送速率";

    @Autowired
    private KbChunkService chunkService;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void seedCorpus() {
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, 1, '切片事务校验', 'txt', 'INDEXED', 3, 0)", DOC_ID);
        insertChunk(CHUNK_A, 0, "TCP 用流量控制限制发送速率，避免接收方缓冲区溢出");
        insertChunk(CHUNK_B, 1, "B+ 树索引适合范围查询与排序");
        insertChunk(CHUNK_C, 2, "窗口大小由接收方在确认帧里回填，这就是流量控制的下发通道");

        props.setApiKey("test-key-not-real");
        props.setEmbeddingEnabled(true);
        props.setSemanticMinScore(RECALL_THRESHOLD);

        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream()
                    .map(s -> s.text().contains("流量控制")
                            ? Embedding.from(TARGET_VECTOR)
                            : Embedding.from(ORTHOGONAL_VECTOR))
                    .toList());
        });
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenReturn(Response.from(Embedding.from(TARGET_VECTOR)));

        assertThat(vectorIndexService.indexChunks(chunks(CHUNK_A, CHUNK_B, CHUNK_C))).isEqualTo(3);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5))
                .containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
    }

    @AfterEach
    void restoreGlobals() {
        props.setEmbeddingEnabled(false);
        props.setSemanticMinScore(0.0d);
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
    }

    @Test
    @DisplayName("提交后批量禁用：enabled 落库、向量同步删掉，语义召回随之清空")
    void committedBatchDisableSyncsVectorsAfterCommit() {
        assertThat(chunkService.batchStatus(List.of(CHUNK_A, CHUNK_C), 0)).isEqualTo(2);

        assertThat(enabledOf(CHUNK_A)).isZero();
        assertThat(enabledOf(CHUNK_C)).isZero();
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).isEmpty();
        assertThat(vectorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("事务回滚时批量禁用：enabled 保持启用，向量库一条都没少，语义召回不受影响")
    void rolledBackBatchDisableLeavesDatabaseAndVectorsUntouched() {
        rollbackWithinTransaction(() -> chunkService.batchStatus(List.of(CHUNK_A, CHUNK_C), 0));

        assertThat(enabledOf(CHUNK_A)).isEqualTo(1);
        assertThat(enabledOf(CHUNK_C)).isEqualTo(1);
        assertThat(vectorCount()).isEqualTo(3);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5))
                .containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
    }

    @Test
    @DisplayName("事务回滚时编辑正文：库里仍是旧正文，旧向量也不会被提前删掉")
    void rolledBackContentEditKeepsOldTextAndItsVectors() {
        String oldContent = "TCP 用流量控制限制发送速率，避免接收方缓冲区溢出";
        rollbackWithinTransaction(() -> chunkService.updateContent(CHUNK_A, "改成与拥塞控制无关的内容"));

        assertThat(contentOf(CHUNK_A)).isEqualTo(oldContent);
        assertThat(vectorCount()).isEqualTo(3);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5))
                .containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
    }

    @Test
    @DisplayName("单条启停：提交后禁用才把向量摘掉，语义召回精确少掉这一条")
    void committedToggleRemovesOnlyThatVector() {
        assertThat(chunkService.toggleStatus(CHUNK_A)).isEqualTo(0);

        assertThat(enabledOf(CHUNK_A)).isZero();
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).containsExactly(CHUNK_C);
        assertThat(vectorCount()).isEqualTo(2);

        // 再点一次恢复启用：写回向量库，召回回到两条
        assertThat(chunkService.toggleStatus(CHUNK_A)).isEqualTo(1);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5))
                .containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 在真实事务里执行给定动作后强制回滚，模拟「库改完了、后半段出错」 */
    private void rollbackWithinTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).execute(status -> {
            action.run();
            status.setRollbackOnly();
            return null;
        });
    }

    private List<KbChunk> chunks(Long... ids) {
        return chunkService.listByIds(List.of(ids));
    }

    private int enabledOf(Long id) {
        Integer enabled = jdbcTemplate.queryForObject(
                "select enabled from kb_chunk where id = " + id, Integer.class);
        return enabled == null ? -1 : enabled;
    }

    private String contentOf(Long id) {
        return jdbcTemplate.queryForObject("select content from kb_chunk where id = " + id, String.class);
    }

    private int vectorCount() {
        assertThat(embeddingStore).isInstanceOf(InMemoryEmbeddingStore.class);
        return ((InMemoryEmbeddingStore<?>) embeddingStore).size();
    }

    private void insertChunk(long id, int chunkIndex, String content) {
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                        + "values (?, ?, ?, ?, ?, 1)",
                id, DOC_ID, chunkIndex, content, content.length());
    }
}
