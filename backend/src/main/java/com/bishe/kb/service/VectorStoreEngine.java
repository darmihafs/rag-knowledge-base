package com.bishe.kb.service;

import com.bishe.kb.model.entity.KbChunk;
import dev.langchain4j.data.embedding.Embedding;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * 向量存储引擎抽象：把「向量怎么写、怎么删、怎么召回」与具体存储介质解耦。
 *
 * <p>核心方法（存储三件套）：
 * <ul>
 *   <li>{@link #indexChunks(List)} —— 向量批量写入/更新（分段向量化后入库，重复 ID 覆盖即更新）；</li>
 *   <li>{@link #removeDocument(Long)} / {@link #removeChunk(Long)} / {@link #removeChunks(Collection)}
 *       —— 按文档或切片删除；</li>
 *   <li>{@link #searchChunkIds(String, int, RetrievalScope)} —— 带 {@code spaceId} 前置过滤
 *       （Pre-filtering）的 Top-K 余弦相似度检索。</li>
 * </ul>
 *
 * <p>辅助方法：可用性开关（{@link #isEnabled()}）、问题向量化（{@link #embedQuestion(String)}，
 * 含 LRU 缓存语义）、启动预热（{@link #warmUp()}）与存储自省
 * （{@link #inspectStore()} / {@link #resolveDimension()} / {@link #storePath()}，供运维大屏体检）。
 *
 * <p><b>降级安全契约</b>：写入/删除/检索类方法在任何一步失败（无密钥、接口超时、序列化失败）时
 * 只记日志并返回空结果或 0，绝不把异常抛回上传链路或问答链路——向量化不可用时系统退化为纯词法检索。
 * 唯一例外是 {@link #embedQuestion(String)}：远程异常照原样回抛，由调用方决定兜底。
 *
 * <p><b>前置过滤契约</b>：空间收敛必须下推到存储层执行，而不是召回后再筛——后者会让未授权空间的
 * 高分切片占满 Top-K。实现方需兼容没有 {@code spaceId} 元数据的旧向量（前置过滤落空时退回放大搜索，
 * 安全性仍由调用方回表阶段的后置过滤保证）。
 *
 * <p>装配方式：实现类用 {@code @ConditionalOnProperty(name = "rag.vector-store.type", ...)} 条件注入，
 * 同一时刻只有一个引擎 Bean 生效。一期默认 {@code local}
 * （{@code LocalAtomicFileVectorStore}：内存索引 + 临时文件 ATOMIC_MOVE 原子落盘）；
 * {@code pgvector} 为生产分布式演进预留的骨架（{@code PgVectorStoreEngine}）。
 * 调用方一律只依赖本接口，不感知具体介质。
 */
public interface VectorStoreEngine {

    /** 向量检索是否可用：配置开关打开 且 向量模型已装配 */
    boolean isEnabled();

    /**
     * 把一批分段向量化并批量写入/更新向量库，成功后按实现自身策略持久化。
     *
     * @return 实际写入的分段条数；未启用或发生异常时返回 0
     */
    int indexChunks(List<KbChunk> chunks);

    /**
     * 删除某文档在向量库中的全部分段（按元数据 documentId 过滤），并持久化。
     * 未启用或异常时静默返回，不阻断数据库侧的删除。
     */
    void removeDocument(Long documentId);

    /**
     * 删除单个分段在向量库中的条目（按元数据 chunkId 过滤），并持久化。
     * 用于人工禁用切片或编辑切片正文后先移除旧向量。未启用或异常时静默返回。
     */
    void removeChunk(Long chunkId);

    /**
     * 批量移除分段向量：内存里逐条删完，<b>只持久化一次</b>，避免整库重写与条数成平方关系。
     * 语义与单条版本一致：任何异常都只告警，不回抛、不阻断数据库侧的启停。
     */
    void removeChunks(Collection<Long> chunkIds);

    /**
     * 问题向量化（实现方应提供同模型同问题的缓存语义，命中缓存不出网）。
     *
     * <p>本方法<b>不做降级</b>：远程异常照原样回抛，由调用方决定怎么兜底。
     *
     * @throws IllegalStateException 向量检索未启用
     * @throws IllegalArgumentException 问题为空
     */
    Embedding embedQuestion(String question);

    /**
     * 启动预热：对向量模型发一次轻量请求，把 TLS 握手、连接池建立、鉴权校验这些一次性成本
     * 付在启动阶段。失败只返回一句说明、不回抛。
     */
    String warmUp();

    /**
     * 语义召回：把问题向量化后做余弦相似搜索，返回命中的 chunkId 列表（按相似度降序），不做空间过滤。
     *
     * @return 命中的 kb_chunk 主键；未启用或异常时返回空列表
     */
    List<Long> searchChunkIds(String question, int maxResults);

    /**
     * 带空间前置过滤（Pre-filtering）的 Top-K 余弦相似度检索。
     *
     * <p>过滤条件交给存储层执行；兼容旧数据：升级前写入的向量没有 {@code spaceId} 元数据导致
     * 前置过滤全部落空时，退回一次不带过滤的放大搜索，由回表阶段按 {@link RetrievalScope}
     * 做后置过滤——安全性仍由后置过滤保证。
     *
     * @param scope 检索范围；null / unrestricted 表示不做空间过滤
     * @return 命中的 kb_chunk 主键；未启用或异常时返回空列表
     */
    List<Long> searchChunkIds(String question, int maxResults, RetrievalScope scope);

    /**
     * 向量维度：优先取存储侧现成值（零网络开销），缺失时才发一次探测请求。
     *
     * @return 维度；向量模型不可用或调用失败返回 null
     */
    Integer resolveDimension();

    /** 存储体检结果（路径/体积/条目数/维度），供运维大屏展示；entries/dimension 为 -1/0 表示未统计出来 */
    VectorStoreInfo inspectStore();

    /** 持久化介质的绝对路径（本地实现为 JSON 文件路径；分布式实现可返回连接描述），供大屏展示 */
    Path storePath();

    /** 存储体检结果；entries/dimension 为 -1/0 表示未统计出来 */
    record VectorStoreInfo(String path, boolean exists, long bytes, int entries, int dimension, String note) {
    }
}
