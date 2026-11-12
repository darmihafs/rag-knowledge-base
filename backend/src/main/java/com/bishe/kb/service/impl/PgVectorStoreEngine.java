package com.bishe.kb.service.impl;

import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.VectorStoreEngine;
import dev.langchain4j.data.embedding.Embedding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * PgVector 向量存储引擎骨架：{@link VectorStoreEngine} 的分布式演进预留扩展点，当前<b>未实现</b>。
 *
 * <p>条件注入：{@code rag.vector-store.type=pgvector} 时装配，与 {@code local}
 * （{@link LocalAtomicFileVectorStore}）互斥。切换前必须先完成本类实现，否则核心操作会抛
 * {@link UnsupportedOperationException} 明确失败，而不是静默丢向量。
 *
 * <p><b>生产环境分布式向量演进路线</b>（实现本类时按此展开）：
 * <ul>
 *   <li>存储介质：用 LangChain4j 的 {@code PgVectorEmbeddingStore} 替换内存索引，
 *       向量落 PostgreSQL + pgvector 扩展，多实例共享同一份向量数据，去掉本地 JSON 单点；</li>
 *   <li>索引与召回：按维度与数据量选 HNSW / IVFFlat 索引，余弦距离用 {@code vector_cosine_ops}，
 *       Top-K 检索下推到 SQL {@code ORDER BY embedding <=> ? LIMIT ?}；</li>
 *   <li>前置过滤：把 {@code spaceId} 元数据过滤下推为 SQL WHERE 条件（pgvector 支持
 *       filtering + 索引的混合查询），替代本地实现的元数据 Filter，旧数据兼容策略保持一致；</li>
 *   <li>写入/删除：批量 upsert 按 chunkId 主键覆盖即「更新」，按 documentId/chunkId 条件 DELETE，
 *       不再需要整库序列化与 ATOMIC_MOVE，持久化由数据库 WAL 保证；</li>
 *   <li>运维演进：连接池与读写分离、向量表分区、定期 VACUUM/REINDEX；存量本地 JSON 用离线
 *       迁移工具一次性导入，迁移期可双写对账。</li>
 * </ul>
 *
 * <p>骨架阶段 {@link #isEnabled()} 恒返回 false：即便误切到 pgvector，问答链路也会按
 * 「向量不可用」退化为纯词法检索，与全局降级安全契约一致。
 */
@Service
@ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "pgvector")
public class PgVectorStoreEngine implements VectorStoreEngine {

    private static final String NOT_IMPLEMENTED =
            "pgvector 引擎尚未实现：请先完成 PgVectorStoreEngine（见类注释演进路线），或切回 rag.vector-store.type=local";

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public int indexChunks(List<KbChunk> chunks) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void removeDocument(Long documentId) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void removeChunk(Long chunkId) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void removeChunks(Collection<Long> chunkIds) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public Embedding embedQuestion(String question) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public String warmUp() {
        return "已跳过：pgvector 引擎尚未实现";
    }

    @Override
    public List<Long> searchChunkIds(String question, int maxResults) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public List<Long> searchChunkIds(String question, int maxResults, RetrievalScope scope) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public Integer resolveDimension() {
        return null;
    }

    @Override
    public VectorStoreInfo inspectStore() {
        return new VectorStoreInfo("pgvector（未实现）", false, 0, 0, 0, NOT_IMPLEMENTED);
    }

    @Override
    public Path storePath() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
