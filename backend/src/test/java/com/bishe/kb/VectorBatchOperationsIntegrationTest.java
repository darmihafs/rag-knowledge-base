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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 向量库批量操作的回归测试（第十二阶段 P0：批量禁用不再逐条整库落盘）。
 *
 * <p>{@code application-test.yml} 里 {@code rag.embedding-enabled=false}，所以全项目原本只有
 * 极少数用例真正走到向量路。这里按 {@code HybridRetrievalIntegrationTest} 的做法用 Mockito
 * 顶掉 {@link EmbeddingModel}，给确定性的向量：含「流量控制」的分段用目标向量、其余用正交向量，
 * 于是「谁能被语义召回」是可预期的，不需要真实密钥。
 *
 * <p>阈值说明：{@code rag.semantic-min-score} 默认 0.0，正交向量（余弦 0）也会被返回，
 * 那样就无法区分「向量被删掉了」和「向量还在但分数低」。所以本类把阈值抬到 0.9，
 * 让召回结果只取决于条目在不在向量库里。
 *
 * <p>断言刻意用 {@link VectorIndexService#inspectStore()} 读<b>持久化文件</b>而不是只看内存：
 * 文件才是「重启后还剩什么」的真相，能证明批量删除后落盘写的是最终完整状态，不是中间态。
 */
@SpringBootTest
@ActiveProfiles("test")
class VectorBatchOperationsIntegrationTest {

    /** 命中的两条分段与查询向量一致 -> 余弦 1.0；干扰分段正交 -> 余弦 0，被 0.9 阈值挡掉 */
    private static final float[] TARGET_VECTOR = {1f, 0f, 0f};
    private static final float[] ORTHOGONAL_VECTOR = {0f, 1f, 0f};
    /** 抬高语义阈值，使「召回为空」等价于「向量确实不在库里」 */
    private static final double RECALL_THRESHOLD = 0.9d;

    /** 固定主键区间，避免与同 JVM 里其它用例的自增 ID 相互干扰 */
    private static final long DOC_ID = 8101L;
    private static final long CHUNK_A = 81011L;
    private static final long CHUNK_B = 81012L;
    private static final long CHUNK_C = 81013L;

    /** 语义上与「流量控制」相关、字面无 2-gram 重叠的提问 */
    private static final String QUESTION = "怎么控制发送速率";

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private KbChunkService chunkService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties props;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void seedCorpus() {
        // 向量库是进程内单例，必须显式清空；配置也逐用例重设，@AfterEach 再复位
        embeddingStore.removeAll();
        jdbcTemplate.update("delete from kb_chunk");
        jdbcTemplate.update("delete from kb_document");
        jdbcTemplate.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                + "values (?, 1, '向量批量校验', 'txt', 'INDEXED', 3, 0)", DOC_ID);
        insertChunk(CHUNK_A, 0, "TCP 用流量控制限制发送速率，避免接收方缓冲区溢出");
        insertChunk(CHUNK_B, 1, "B+ 树索引适合范围查询与排序");
        insertChunk(CHUNK_C, 2, "窗口大小由接收方在确认帧里回填，这就是流量控制的下发通道");

        props.setApiKey("test-key-not-real");
        props.setEmbeddingEnabled(true);
        props.setSemanticMinScore(RECALL_THRESHOLD);

        // embedAll：正文含「流量控制」的分段给目标向量，其余给正交向量
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = segments.stream()
                    .map(s -> s.text().contains("流量控制")
                            ? Embedding.from(TARGET_VECTOR)
                            : Embedding.from(ORTHOGONAL_VECTOR))
                    .toList();
            return Response.from(embeddings);
        });
        // embed(String)：查询向量固定为目标向量
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenReturn(Response.from(Embedding.from(TARGET_VECTOR)));
    }

    @AfterEach
    void restoreGlobals() {
        // props 是单例 Bean，不复位会污染其它测试类的检索阈值
        props.setEmbeddingEnabled(false);
        props.setSemanticMinScore(0.0d);
        embeddingStore.removeAll();
    }

    @Test
    @DisplayName("批量禁用切片：被禁分段从向量库消失，且持久化文件重读后只剩保留的那条")
    void batchDisableShouldRemoveVectorsAndRewriteStoreOnce() {
        assertThat(vectorIndexService.indexChunks(chunks(CHUNK_A, CHUNK_B, CHUNK_C))).isEqualTo(3);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5))
                .containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
        assertThat(snapshotEntries()).isEqualTo(3);

        int disabled = chunkService.batchStatus(List.of(CHUNK_A, CHUNK_C), 0);

        assertThat(disabled).isEqualTo(2);
        // 语义路只剩那条正交向量干扰分段，而它被 0.9 阈值挡掉 -> 召回为空
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).isEmpty();
        // 持久化文件与内存一致，说明「删完只落一次盘」写出的是删除后的最终状态
        assertThat(snapshotEntries()).isEqualTo(1);
        assertThat(memoryStoreSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("批量启用会把分段写回向量库，语义召回随即恢复")
    void batchEnableShouldWriteVectorsBack() {
        vectorIndexService.indexChunks(chunks(CHUNK_A, CHUNK_B, CHUNK_C));
        chunkService.batchStatus(List.of(CHUNK_A, CHUNK_C), 0);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).isEmpty();

        assertThat(chunkService.batchStatus(List.of(CHUNK_C), 1)).isEqualTo(1);

        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).containsExactly(CHUNK_C);
        assertThat(snapshotEntries()).isEqualTo(2);
    }

    @Test
    @DisplayName("空入参与重复 ID：批量删除不抛异常，也不会误删其它分段")
    void batchRemoveShouldTolerateEmptyAndDuplicateIds() {
        vectorIndexService.indexChunks(chunks(CHUNK_A, CHUNK_B, CHUNK_C));

        assertThatCode(() -> {
            vectorIndexService.removeChunks(List.of());
            vectorIndexService.removeChunks(null);
        }).doesNotThrowAnyException();
        assertThat(snapshotEntries()).isEqualTo(3);

        // 重复 ID 去重后只删一次；不存在的 999999 静默跳过
        vectorIndexService.removeChunks(List.of(CHUNK_B, CHUNK_B, 999999L));

        assertThat(memoryStoreSize()).isEqualTo(2);
        assertThat(snapshotEntries()).isEqualTo(2);
        assertThat(vectorIndexService.searchChunkIds(QUESTION, 5)).containsExactlyInAnyOrder(CHUNK_A, CHUNK_C);
    }

    @Test
    @DisplayName("分批向量化中途某批失败：已写入内存的前序批次仍原子落盘，内存与磁盘不分叉")
    void partialBatchFailureStillPersistsWrittenBatches() {
        // 批大小设为 1：3 条分段分 3 批，令第 2 批远程向量化抛异常，第 1 批已写进内存
        props.setEmbeddingBatchSize(1);
        try {
            AtomicInteger calls = new AtomicInteger();
            Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenAnswer(invocation -> {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("第二批远程向量化失败");
                }
                List<TextSegment> segments = invocation.getArgument(0);
                return Response.from(segments.stream().map(s -> Embedding.from(TARGET_VECTOR)).toList());
            });

            int written = vectorIndexService.indexChunks(chunks(CHUNK_A, CHUNK_B, CHUNK_C));

            // 第 1 批成功写入并落盘，第 2 批失败后循环中断：返回 1，磁盘与内存都必须恰好 1 条
            assertThat(written).isEqualTo(1);
            assertThat(snapshotEntries()).isEqualTo(1);
            assertThat(memoryStoreSize()).isEqualTo(1);
        } finally {
            props.setEmbeddingBatchSize(10);
        }
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 用主键把已入库的分段取回成实体，喂给向量服务 */
    private List<KbChunk> chunks(Long... ids) {
        return chunkService.listByIds(List.of(ids));
    }

    /** 持久化文件里的条目数：证明落盘状态，而不是内存状态 */
    private int snapshotEntries() {
        return vectorIndexService.inspectStore().entries();
    }

    /** 内存向量库条目数；{@code size()} 只在内存实现上有，接口没有 */
    private int memoryStoreSize() {
        assertThat(embeddingStore).isInstanceOf(InMemoryEmbeddingStore.class);
        return ((InMemoryEmbeddingStore<?>) embeddingStore).size();
    }

    private void insertChunk(long id, int chunkIndex, String content) {
        jdbcTemplate.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                        + "values (?, ?, ?, ?, ?, 1)",
                id, DOC_ID, chunkIndex, content, content.length());
    }
}
