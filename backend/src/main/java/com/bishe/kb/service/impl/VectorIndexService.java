package com.bishe.kb.service.impl;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.VectorStoreEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * 旧名兼容门面：把历史调用方与存量测试用到的 {@code VectorIndexService} 类型，
 * 原样转发给 {@link VectorStoreEngine} 的当前装配实现（默认 {@link LocalAtomicFileVectorStore}）。
 *
 * <p>架构解耦后的正主是 {@link VectorStoreEngine} 接口 + 按 {@code rag.vector-store.type}
 * 条件注入的引擎实现。本类仍实现该接口，只为兼容存量测试的两处硬依赖：
 * {@code @Autowired VectorIndexService}、{@code new VectorIndexService(provider, store, props, objectMapper)}，
 * 以及 {@code LexicalRetrievalRoutingTest} 把 {@code Mockito.mock(VectorIndexService.class)}
 * 传进已改为面向接口的 {@code RetrievalServiceImpl} 构造器。
 * 由此容器里会有两个 {@link VectorStoreEngine} 候选（引擎 + 本门面），
 * 由 {@link LocalAtomicFileVectorStore} 上的 {@code @Primary} 保证按接口注入时始终选中真正的引擎，
 * 本门面的委托构造器同样注入到引擎而不是自己，不会形成循环。
 *
 * <p>新代码一律注入 {@link VectorStoreEngine}；待测试迁移完成后删除本类。
 *
 * @deprecated 使用 {@link VectorStoreEngine} 代替
 */
@Deprecated
@Service
public class VectorIndexService implements VectorStoreEngine {

    private final VectorStoreEngine delegate;

    /** Spring 装配入口：注入当前条件选中的向量引擎 */
    @Autowired
    public VectorIndexService(VectorStoreEngine delegate) {
        this.delegate = delegate;
    }

    /**
     * 存量测试用的四参构造：脱离 Spring 容器直接包一个本地原子文件引擎，
     * 行为与解耦前的 {@code VectorIndexService} 完全一致（含问题向量 LRU 缓存）。
     */
    public VectorIndexService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                              EmbeddingStore<TextSegment> embeddingStore,
                              RagProperties props,
                              ObjectMapper objectMapper) {
        this(new LocalAtomicFileVectorStore(embeddingModelProvider, embeddingStore, props, objectMapper));
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public Path storePath() {
        return delegate.storePath();
    }

    @Override
    public VectorStoreEngine.VectorStoreInfo inspectStore() {
        return delegate.inspectStore();
    }

    @Override
    public Integer resolveDimension() {
        return delegate.resolveDimension();
    }

    @Override
    public int indexChunks(List<KbChunk> chunks) {
        return delegate.indexChunks(chunks);
    }

    @Override
    public void removeDocument(Long documentId) {
        delegate.removeDocument(documentId);
    }

    @Override
    public void removeChunk(Long chunkId) {
        delegate.removeChunk(chunkId);
    }

    @Override
    public void removeChunks(Collection<Long> chunkIds) {
        delegate.removeChunks(chunkIds);
    }

    @Override
    public Embedding embedQuestion(String question) {
        return delegate.embedQuestion(question);
    }

    @Override
    public String warmUp() {
        return delegate.warmUp();
    }

    @Override
    public List<Long> searchChunkIds(String question, int maxResults) {
        return delegate.searchChunkIds(question, maxResults);
    }

    @Override
    public List<Long> searchChunkIds(String question, int maxResults, RetrievalScope scope) {
        return delegate.searchChunkIds(question, maxResults, scope);
    }
}