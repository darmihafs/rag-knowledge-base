package com.bishe.kb.service.impl;

import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.VectorStoreEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 本地向量存储引擎：内存索引 + 临时文件 {@code ATOMIC_MOVE} 原子落盘，{@link VectorStoreEngine} 的一期默认实现。
 *
 * <p>条件注入：{@code rag.vector-store.type=local}（缺省即 local，{@code matchIfMissing=true}），
 * 与 {@code pgvector} 骨架互斥，同一时刻只有一个引擎 Bean 生效。{@code @Primary} 用于和
 * 旧名兼容门面 {@link VectorIndexService}（同样实现接口、仅为存量测试保留）消歧：
 * 按 {@link VectorStoreEngine} 接口注入时永远选中本引擎，门面只是转发壳。
 *
 * <p>设计原则——<b>降级安全</b>：任何一步（无密钥、接口超时、序列化失败）都只记日志并返回空结果，
 * 绝不把异常抛回上传链路或问答链路。这样即使向量化不可用，系统仍能退化成纯词法检索继续工作。
 *
 * <p>用 {@link InMemoryEmbeddingStore} 做内存索引，每次写入后序列化到 {@code rag.embedding-store-path}
 * 指向的 JSON 文件，进程重启由 {@code LangChain4jConfig#embeddingStore} 从文件加载回来。
 *
 * <p>{@link EmbeddingModel} 用 {@link ObjectProvider} 延迟获取：只有
 * {@code rag.embedding-enabled=true} 时该 Bean 才存在，关闭时 {@link #isEnabled()} 返回 false。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "local", matchIfMissing = true)
public class LocalAtomicFileVectorStore implements VectorStoreEngine {

    /** 超过这个体积就不解析向量文件内容，防止大屏刷新被一次几十 MB 的读盘拖住 */
    private static final long MAX_INSPECT_BYTES = 32L * 1024 * 1024;

    /** 向量化耗时超过这个阈值就记一条 warn，慢在哪里至少留在日志里 */
    private static final long SLOW_EMBED_MILLIS = 3_000L;

    /** 前置过滤没命中时，为兼容没有 spaceId 元数据的旧向量而退回全库搜索的候选放大倍数 */
    private static final int LEGACY_FALLBACK_FACTOR = 3;

    /** 预热探针句子：只走一次公网建立连接与鉴权，不进缓存 */
    private static final String WARM_UP_PROBE = "私有知识库问答系统启动预热";

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    /** 维度探测结果缓存（含失败标记 0），远程调用最多发生一次 */
    private volatile Integer probedDimension;

    /**
     * 序列化落盘的互斥锁：整库重写 JSON 是「读内存 -> 写文件」，两个并发持久化交错就可能
     * 写出半新半旧的损坏文件，而文件一旦坏掉，重启后整个语义召回路就哑了。
     */
    private final Object persistLock = new Object();

    /**
     * 问题向量一级缓存（Caffeine L1）：同一个模型对同一句问题的向量是确定性的，重复提问没有新鲜度问题，
     * 只有「要不要再花一次公网往返」。答辩演示与审计复看都是同一批问题反复问，命中率很高。
     *
     * <p>容量 {@code rag.embedding-query-cache-size}（默认 1000）、写后 {@code rag.embedding-query-cache-expire-minutes}
     * （默认 5 分钟）过期。命中直接返回<b>同一个</b> {@link Embedding} 实例，不做值拷贝。
     *
     * <p>刻意不做「缓存未命中时在锁内远程调用」：那会让并发提问在缓存上串行排队。
     * 这里允许极端情况下同一问题被并发向量化两次，后写覆盖前写，结果一样。
     */
    private final Cache<String, Embedding> queryEmbeddingCache;

    /**
     * 问题向量二级缓存（Redis L2）入口，可为 null：{@code kb.cache.enabled=false}（测试）或
     * 未装配 Redis 时退化为仅 L1。L2 跨实例共享向量，Key 形如 {@code rag:emb:{sha256}}，
     * 读写失败都由 {@link RedisCacheService} 内部降级，绝不阻断问答主链路。
     */
    private final RedisCacheService redisCache;

    /** Spring 装配入口：L2 经 {@link ObjectProvider} 可选注入，关闭缓存时拿到 null 走仅 L1 */
    @Autowired
    public LocalAtomicFileVectorStore(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      EmbeddingStore<TextSegment> embeddingStore,
                                      RagProperties props,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<RedisCacheService> redisCacheProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingStore = embeddingStore;
        this.props = props;
        this.objectMapper = objectMapper;
        this.redisCache = redisCacheProvider == null ? null : redisCacheProvider.getIfAvailable();
        this.queryEmbeddingCache = newQueryCache(props);
    }

    /**
     * 存量测试与旧名门面用的四参构造：不接 Redis，等价于仅 Caffeine L1 缓存，
     * 行为与升级前的本地 LRU 完全一致（同实例命中、容量上限、模型名隔离）。
     */
    public LocalAtomicFileVectorStore(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      EmbeddingStore<TextSegment> embeddingStore,
                                      RagProperties props,
                                      ObjectMapper objectMapper) {
        this(embeddingModelProvider, embeddingStore, props, objectMapper, (ObjectProvider<RedisCacheService>) null);
    }

    /**
     * 构造 Caffeine L1：{@code maximumSize} 做容量淘汰、{@code expireAfterWrite} 做时间过期。
     * {@code executor(Runnable::run)} 让维护操作在调用线程同步执行，容量淘汰因此是确定的
     * （存量单测断言容量 1 时写入第三条立刻挤掉最老的一条，不能依赖异步淘汰的时机）。
     */
    private static Cache<String, Embedding> newQueryCache(RagProperties props) {
        int cap = Math.max(0, props.getEmbeddingQueryCacheSize());
        long expireMinutes = Math.max(1L, props.getEmbeddingQueryCacheExpireMinutes());
        return Caffeine.newBuilder()
                .maximumSize(cap)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .executor(Runnable::run)
                .build();
    }

    /** 向量检索是否可用：配置开关打开 且 向量模型 Bean 已注册 */
    @Override
    public boolean isEnabled() {
        return props.isEmbeddingEnabled() && embeddingModelProvider.getIfAvailable() != null;
    }

    // ------------------------------------------------------------------ 大盘健康检查用的自省接口

    /** 持久化文件的绝对路径，供大屏展示 */
    @Override
    public Path storePath() {
        return Paths.get(props.getEmbeddingStorePath()).toAbsolutePath().normalize();
    }

    /**
     * 读持久化文件统计「已落盘的向量条目数」与「向量维度」。
     *
     * <p>刻意读文件而不是猜内存状态：文件才是「重启后还剩什么」的真相，
     * 而条目数与库里启用切片数是否一致，正好能暴露「向量化悄悄失败过」这种情况。
     * 文件过大（&gt; {@value #MAX_INSPECT_BYTES} 字节）时不解析，只报体积。
     */
    @Override
    public VectorStoreInfo inspectStore() {
        Path path = storePath();
        if (!Files.isRegularFile(path)) {
            return new VectorStoreInfo(path.toString(), false, 0, 0, 0, "持久化文件尚未生成");
        }
        long bytes;
        try {
            bytes = Files.size(path);
        } catch (IOException e) {
            return new VectorStoreInfo(path.toString(), true, -1, -1, 0, "读取文件大小失败: " + e.getMessage());
        }
        if (bytes > MAX_INSPECT_BYTES) {
            return new VectorStoreInfo(path.toString(), true, bytes, -1, 0,
                    "文件超过 " + (MAX_INSPECT_BYTES / 1024 / 1024) + "MB，为避免拖慢大屏未解析条目数");
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode entries = root.path("entries");
            int count = entries.isArray() ? entries.size() : 0;
            int dimension = 0;
            if (count > 0) {
                JsonNode vector = entries.get(0).path("embedding").path("vector");
                if (vector.isArray()) {
                    dimension = vector.size();
                }
            }
            return new VectorStoreInfo(path.toString(), true, bytes, count, dimension, null);
        } catch (IOException | RuntimeException e) {
            return new VectorStoreInfo(path.toString(), true, bytes, -1, 0, "解析向量文件失败: " + e.getMessage());
        }
    }

    /**
     * 向量维度：优先取持久化文件里的现成值（零网络开销），文件为空或缺维度时才发一次探测请求。
     *
     * @return 维度；向量模型不可用或调用失败返回 null
     */
    @Override
    public Integer resolveDimension() {
        VectorStoreInfo info = inspectStore();
        if (info.dimension() > 0) {
            return info.dimension();
        }
        if (!isEnabled()) {
            return null;
        }
        Integer cached = probedDimension;
        if (cached != null) {
            return cached > 0 ? cached : null;
        }
        try {
            Embedding probe = embeddingModelProvider.getObject().embed("维度探测").content();
            int dimension = probe == null || probe.vector() == null ? 0 : probe.vector().length;
            // 失败结果也缓存下来，避免大屏每次刷新都重试一次远程调用
            probedDimension = dimension;
            return dimension > 0 ? dimension : null;
        } catch (RuntimeException e) {
            log.warn("向量维度探测失败: {}", e.getMessage());
            probedDimension = 0;
            return null;
        }
    }

    /**
     * 把一批分段向量化并写入向量库，成功后持久化到本地文件。
     *
     * @return 实际写入的分段条数；未启用或发生异常时返回 0
     */
    @Override
    public int indexChunks(List<KbChunk> chunks) {
        if (!isEnabled() || chunks == null || chunks.isEmpty()) {
            return 0;
        }
        EmbeddingModel model = embeddingModelProvider.getObject();
        int batch = Math.max(1, props.getEmbeddingBatchSize());
        int written = 0;
        try {
            for (int from = 0; from < chunks.size(); from += batch) {
                List<KbChunk> slice = chunks.subList(from, Math.min(from + batch, chunks.size()));

                List<String> ids = new ArrayList<>(slice.size());
                List<TextSegment> segments = new ArrayList<>(slice.size());
                for (KbChunk chunk : slice) {
                    ids.add(String.valueOf(chunk.getId()));
                    segments.add(toSegment(chunk));
                }
                // text-embedding-v3 批量上限较小，这里已按 embeddingBatchSize 切好
                Response<List<Embedding>> response = model.embedAll(segments);
                List<Embedding> embeddings = response.content();
                if (embeddings == null || embeddings.size() != segments.size()) {
                    log.warn("向量化返回数量不匹配，跳过本批: 期望={} 实际={}",
                            segments.size(), embeddings == null ? 0 : embeddings.size());
                    continue;
                }
                embeddingStore.addAll(ids, embeddings, segments);
                written += slice.size();
            }
            if (written > 0) {
                persist();
            }
            log.info("向量化写入完成: {} 条分段", written);
        } catch (RuntimeException e) {
            // 向量化失败不影响文档已入库的事实，仅记录，问答侧会自动退化为词法检索
            log.warn("向量化写入失败，文档仍可按词法检索（{} 条已写入）: {}", written, e.getMessage());
        }
        return written;
    }

    /**
     * 删除某文档在向量库中的全部分段（按元数据 documentId 过滤），并持久化。
     * 未启用或异常时静默返回，不阻断数据库侧的删除。
     */
    @Override
    public void removeDocument(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            embeddingStore.removeAll(metadataKey("documentId").isEqualTo(documentId));
            persist();
            log.info("已从向量库删除文档分段: documentId={}", documentId);
        } catch (RuntimeException e) {
            log.warn("向量库删除失败（不影响数据库记录）: documentId={} —— {}", documentId, e.getMessage());
        }
    }

    /**
     * 删除单个分段在向量库中的条目（按元数据 chunkId 过滤），并持久化。
     * 用于人工禁用切片或编辑切片正文后先移除旧向量。未启用或异常时静默返回，不阻断数据库侧操作。
     */
    @Override
    public void removeChunk(Long chunkId) {
        if (chunkId == null) {
            return;
        }
        try {
            embeddingStore.removeAll(metadataKey("chunkId").isEqualTo(chunkId));
            persist();
            log.info("已从向量库删除分段: chunkId={}", chunkId);
        } catch (RuntimeException e) {
            log.warn("向量库删除分段失败（不影响数据库记录）: chunkId={} —— {}", chunkId, e.getMessage());
        }
    }

    /**
     * 批量移除分段向量：内存里逐条删完，<b>只序列化一次</b>。
     *
     * <p>批量禁用几百条切片如果逐条走 {@link #removeChunk}，就是几百次「整库重写 JSON」，
     * 磁盘写入量与条数成平方关系；删完再落一次盘才是对的。语义与单条版本一致：
     * 任何异常都只告警，不回抛、不阻断数据库侧的启停。
     */
    @Override
    public void removeChunks(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long chunkId : chunkIds) {
            if (chunkId != null) {
                distinct.add(chunkId);
            }
        }
        if (distinct.isEmpty()) {
            return;
        }
        int removed = 0;
        try {
            for (Long chunkId : distinct) {
                embeddingStore.removeAll(metadataKey("chunkId").isEqualTo(chunkId));
                removed++;
            }
            persist();
            log.info("已从向量库批量删除分段: {} 条", removed);
        } catch (RuntimeException e) {
            log.warn("向量库批量删除失败（不影响数据库记录）: 已处理={} 条 —— {}", removed, e.getMessage());
        }
    }

    /**
     * 问题向量化（多级缓存命中就不出网）：Caffeine L1 → Redis L2 → 远程 embedding。
     *
     * <p>两点和裸 {@code embed} 不一样：
     * <ul>
     *   <li>先 {@code trim}——句首句尾空白对语义没有贡献，却要参与缓存 key，
     *       不归一化就会出现「同一句话两种写法各占一次远程调用」；</li>
     *   <li>key 带上模型名——换 embedding 模型后向量维度与语义空间都变了，复用旧向量会让
     *       {@code EmbeddingStore.search} 抛维度不匹配。链路虽然会降级成词法检索不至于报错，
     *       但那是一次没人会发现的静默劣化。</li>
     * </ul>
     *
     * <p>L1 命中返回同一个实例；L1 未命中先查 L2（跨实例共享），命中则回填 L1；
     * 都未命中才远程向量化，成功后同时写回 L1 与 L2。Redis 不可用时 L2 读写自动降级，仅用 L1。
     *
     * <p>本方法<b>不做降级</b>：远程异常照原样回抛，由调用方决定怎么兜底
     * （{@link #searchChunkIds} 捕获后退回词法检索）。缓存层的异常则内部消化，绝不外抛。
     *
     * @throws IllegalStateException 向量检索未启用
     * @throws IllegalArgumentException 问题为空
     */
    @Override
    public Embedding embedQuestion(String question) {
        if (!isEnabled()) {
            throw new IllegalStateException("向量检索未启用，无法对问题做向量化");
        }
        String normalized = question == null ? "" : question.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("问题为空，无法向量化");
        }
        String model = props.getEmbeddingModel();
        String key = model + '\0' + normalized;
        // L1：本地 Caffeine，命中返回同一实例
        Embedding cached = queryEmbeddingCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // L2：Redis 分布式缓存，命中回填 L1 后返回
        Embedding fromL2 = readL2(model, normalized);
        if (fromL2 != null) {
            putL1(key, fromL2);
            return fromL2;
        }
        long start = System.nanoTime();
        Embedding embedding = embeddingModelProvider.getObject().embed(normalized).content();
        long costMillis = (System.nanoTime() - start) / 1_000_000L;
        if (costMillis > SLOW_EMBED_MILLIS) {
            log.warn("问题向量化耗时 {}ms，超过 {}ms 告警阈值：语义召回路受公网往返或模型排队影响，"
                    + "同一个问题第二次问会走缓存（当前 L1 约 {} 条）", costMillis, SLOW_EMBED_MILLIS,
                    queryEmbeddingCache.estimatedSize());
        }
        if (embedding != null && props.getEmbeddingQueryCacheSize() > 0) {
            putL1(key, embedding);
            writeL2(model, normalized, embedding);
        }
        return embedding;
    }

    /** 写 L1 并立即触发容量淘汰，与旧 LRU「写时淘汰」语义一致（容量 0 时不写） */
    private void putL1(String key, Embedding embedding) {
        if (props.getEmbeddingQueryCacheSize() > 0) {
            queryEmbeddingCache.put(key, embedding);
            queryEmbeddingCache.cleanUp();
        }
    }

    /** 读 L2：未装配 Redis / 未命中 / 反序列化失败都返回 null，由调用方回源远程向量化 */
    private Embedding readL2(String model, String normalized) {
        if (redisCache == null) {
            return null;
        }
        try {
            String json = redisCache.get(embeddingRedisKey(model, normalized));
            if (json == null || json.isEmpty()) {
                return null;
            }
            return Embedding.from(objectMapper.readValue(json, float[].class));
        } catch (IOException | RuntimeException e) {
            log.warn("问题向量 L2 缓存读取失败，回退远程向量化: {}", e.getMessage());
            return null;
        }
    }

    /** 写 L2：序列化或 Redis 失败只告警，L1 与主链路不受影响 */
    private void writeL2(String model, String normalized, Embedding embedding) {
        if (redisCache == null || embedding.vector() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(embedding.vector());
            redisCache.set(embeddingRedisKey(model, normalized), json,
                    Duration.ofHours(Math.max(1L, props.getEmbeddingL2TtlHours())));
        } catch (IOException | RuntimeException e) {
            log.warn("问题向量 L2 缓存写入失败（不影响 L1 与问答链路）: {}", e.getMessage());
        }
    }

    /** L2 Key：{@code rag:emb:{sha256(模型名 + '\0' + 归一化问题)}}，与 L1 同样按模型名隔离 */
    private static String embeddingRedisKey(String model, String normalized) {
        return "rag:emb:" + sha256Hex(model + '\0' + normalized);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会走到这里
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 启动预热：对向量模型发一次轻量请求，把 TLS 握手、连接池建立、鉴权校验这些一次性成本
     * 付在启动阶段，而不是让第一位提问的用户承担。
     *
     * <p>预热用的探针句子刻意不进缓存（永远不会有人问它）；顺手把探测到的维度填进
     * {@code probedDimension}，大屏的「向量维度」就不用再发第二次远程请求。
     *
     * <p>失败只返回一句说明、不回抛：预热不成功说明语义路暂时不可用，问答会自动退化成词法检索，
     * 这比让应用起不来好得多。
     */
    @Override
    public String warmUp() {
        if (!isEnabled()) {
            return "已跳过：向量检索未启用";
        }
        long start = System.nanoTime();
        try {
            Embedding probe = embeddingModelProvider.getObject().embed(WARM_UP_PROBE).content();
            long costMillis = (System.nanoTime() - start) / 1_000_000L;
            int dimension = probe == null || probe.vector() == null ? 0 : probe.vector().length;
            if (dimension > 0) {
                this.probedDimension = dimension;
            }
            String summary = "完成：模型=" + props.getEmbeddingModel() + " 维度=" + dimension + " 耗时=" + costMillis + "ms";
            if (costMillis > SLOW_EMBED_MILLIS) {
                log.warn("向量链路首帧偏慢，{} —— 冷启动成本已付，后续同一问题的向量召回复用缓存", summary);
            }
            return summary;
        } catch (RuntimeException e) {
            long costMillis = (System.nanoTime() - start) / 1_000_000L;
            return "失败（耗时 " + costMillis + "ms，问答自动退化为词法检索）: " + e.getMessage();
        }
    }

    /**
     * 语义召回：把问题向量化后在向量库做余弦相似搜索，返回命中的 chunkId 列表（按相似度降序）。
     *
     * <p>只做向量相似，不做空间过滤；需要空间收敛时用
     * {@link #searchChunkIds(String, int, RetrievalScope)}。
     *
     * @return 命中的 kb_chunk 主键；未启用或异常时返回空列表
     */
    @Override
    public List<Long> searchChunkIds(String question, int maxResults) {
        return searchChunkIds(question, maxResults, null);
    }

    /**
     * 带空间前置过滤的语义召回。
     *
     * <p>过滤条件交给向量库，而不是召回后再用 SQL/内存筛：后者会让未授权空间的高分切片
     * 占满 {@code maxResults}，收敛空间后 topK 反而变少，隔离也只是「偶尔生效」。
     *
     * <p>兼容旧数据：升级前写入的向量没有 {@code spaceId} 元数据，前置过滤会全部落空。
     * 此时退回一次不带过滤的放大搜索，由回表阶段按 {@code RetrievalScope} 做后置过滤——
     * 安全性仍由后置过滤保证，代价只是这些旧条目还没享受前置收敛。
     *
     * @param scope 检索范围；null / unrestricted 表示不做空间过滤
     * @return 命中的 kb_chunk 主键；未启用或异常时返回空列表
     */
    @Override
    public List<Long> searchChunkIds(String question, int maxResults, RetrievalScope scope) {
        if (!isEnabled() || maxResults <= 0 || question == null || question.isBlank()) {
            return List.of();
        }
        try {
            Embedding query = embedQuestion(question);
            if (query == null) {
                log.warn("问题向量化返回空向量，本次退回纯词法检索: {}", question);
                return List.of();
            }
            Filter filter = spaceFilter(scope);
            List<Long> ids = search(query, maxResults, filter);
            if (ids.isEmpty() && filter != null) {
                int fallbackLimit = maxResults * LEGACY_FALLBACK_FACTOR;
                ids = search(query, fallbackLimit, null);
                if (!ids.isEmpty()) {
                    log.debug("空间前置过滤无命中，已退回全库搜索以兼容旧向量条目（候选 {} 条）", ids.size());
                }
            }
            return ids;
        } catch (RuntimeException e) {
            // 含 NumberFormatException（embeddingId 非数字）等，一律退回纯词法检索
            log.warn("语义召回失败，本次退回纯词法检索: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Long> search(Embedding query, int maxResults, Filter filter) {
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(maxResults)
                .minScore(props.getSemanticMinScore());
        if (filter != null) {
            builder.filter(filter);
        }
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(builder.build());
        List<Long> ids = new ArrayList<>(result.matches().size());
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            ids.add(Long.parseLong(match.embeddingId()));
        }
        return ids;
    }

    /**
     * {@link RetrievalScope} -> 向量库元数据过滤器。
     *
     * <p>空授权集合返回 null 是安全的：调用方（{@code RetrievalServiceImpl}）在进入召回前
     * 已用 {@code deniesEverything()} 直接返回空结果，不会把「什么都不许看」送到这里。
     */
    private Filter spaceFilter(RetrievalScope scope) {
        if (scope == null || !scope.restricted()) {
            return null;
        }
        if (scope.spaceId() != null) {
            return metadataKey("spaceId").isEqualTo(scope.spaceId().longValue());
        }
        List<Long> visible = scope.visibleSpaceIds();
        return visible == null || visible.isEmpty() ? null : metadataKey("spaceId").isIn(visible);
    }

    /** 分段 -> 向量库条目：正文用于 embed，元数据带上业务 ID 便于反查、按文档删除与空间前置过滤 */
    private TextSegment toSegment(KbChunk chunk) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata()
                .put("chunkId", chunk.getId())
                .put("documentId", chunk.getDocumentId())
                .put("chunkIndex", chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex());
        if (chunk.getSpaceId() != null) {
            metadata.put("spaceId", chunk.getSpaceId().longValue());
        }
        return TextSegment.from(chunk.getContent(), metadata);
    }

    /**
     * 内存向量库序列化到本地 JSON，失败只告警不影响主流程。
     *
     * <p>先写同目录临时文件再原子替换：直接写目标文件时，进程在序列化中途退出会留下
     * 半截 JSON，重启后 {@code fromFile} 失败，整个语义召回路静默变哑。
     */
    private void persist() {
        if (!(embeddingStore instanceof InMemoryEmbeddingStore<?> store)) {
            return;
        }
        Path path = Paths.get(props.getEmbeddingStorePath()).toAbsolutePath().normalize();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            synchronized (persistLock) {
                store.serializeToFile(temp);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // 跨文件系统挂载等场景不支持原子移动，退化为普通替换（仍好过直接覆写目标文件）
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (RuntimeException | IOException e) {
            log.warn("向量库持久化失败（内存索引仍在，重启后会丢失）: {} —— {}", path, e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程，下次持久化会覆盖它
            }
        }
    }
}