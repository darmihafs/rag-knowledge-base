package com.bishe.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 全链路可调参数，配置前缀 {@code rag}。
 *
 * <p>字段都给了可运行默认值，密钥一律从环境变量注入（见 application.yml），不写死在代码里。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** OpenAI 兼容协议的 API Key */
    private String apiKey = "";

    /** 兼容协议 base-url：阿里云百炼 / DeepSeek / 本地 vLLM / Ollama 均可 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 生成模型名 */
    private String chatModel = "qwen-plus";

    /** 知识库问答建议低温，减少发挥 */
    private Double temperature = 0.2d;

    /** 向量化模型名 */
    private String embeddingModel = "text-embedding-v3";

    /** 关掉时只做“解析+分块+落库”，不调用 embedding 接口（便于无密钥开发） */
    private boolean embeddingEnabled = false;

    /**
     * 演示/联调模式：不调大模型，用检索到的片段拼一段固定回答并按字推送。
     * 用于没有 API Key 时验证 SSE 打字机链路，生产必须为 false。
     */
    private boolean mockEnabled = false;

    /** 单块最大字符数 */
    private int chunkMaxChars = 800;

    /** 相邻块重叠字符数 */
    private int chunkOverlapChars = 120;

    /** 默认召回条数 */
    private int topK = 5;

    /** 相似度阈值，低于它视为“知识库里没有答案” */
    private double minScore = 0.6d;

    /** 多轮上下文携带的历史消息条数，6 = 最近 3 轮问答；设 0 可退化成单轮 */
    private int historyMessages = 6;

    /** 单条历史消息最多带多少字，防止长回答把上下文窗口撑爆 */
    private int historyMaxChars = 1200;

    /** 上传文件根目录 */
    private String uploadDir = "./data/uploads";

    /**
     * 文档后台解析线程数。
     *
     * <p>PDFBox 抽取是 CPU / 内存密集型操作，大 PDF 还会把整页文本读进堆内存，
     * 因此固定一个小池子串行度可控，不能用 SSE 那种弹性线程池。
     */
    private int ingestThreads = 2;

    /**
     * 后台解析等待队列长度。队列满时上传接口明确失败（文档落为 FAILED），
     * 绝不把解析任务甩回 Tomcat 请求线程，否则一次批量上传就能拖垮整个服务。
     */
    private int ingestQueueCapacity = 100;

    /**
     * Agent 单任务硬超时（毫秒）。
     *
     * <p>之前的预算只在模型/工具调用之间检查，供应商 HTTP 调用一旦挂住，任务会无限等下去。
     * 现在同步接口用 {@code Future.get(timeout)}、流式接口用看门狗在截止时间强制收尾，
     * 即使网络调用不响应中断，客户端也不会被永久占住。默认 90 秒。
     */
    private long agentTimeoutMillis = 90_000L;

    /** Agent 工具循环线程数；同时是全局并发闸门的容量。默认 4。 */
    private int agentThreads = 4;

    /** Agent 线程池等待队列；队列满时明确拒绝并返回 4290。默认 32。 */
    private int agentQueueCapacity = 32;

    /**
     * 向量库持久化文件路径。一期用 LangChain4j 的 {@code InMemoryEmbeddingStore}，
     * 进程内检索、退出前序列化到这个 JSON 文件，重启后 {@code fromFile} 直接加载，零外部依赖。
     */
    private String embeddingStorePath = "./data/embeddings/store.json";

    /**
     * 单次向量化请求最多带多少条分段。DashScope 的 text-embedding-v3 批量上限较小，
     * 超过会被服务端拒绝，这里保守设 10，分块入库时按它切批调用。
     */
    private int embeddingBatchSize = 10;

    /**
     * 问题向量化（检索链路里一次 embed 调用）的超时秒数。
     *
     * <p>刻意与对话模型的 60 秒分开：生成慢一点可以接受，检索慢就是纯粹的体验事故——
     * 20 秒还回不来的向量服务应该让词法路顶上，而不是把用户挂在 SSE 连接里干等。
     */
    private int embeddingTimeoutSeconds = 20;

    /**
     * 问题向量一级缓存（Caffeine L1）的条数上限。同一个模型对同一句问题的向量是确定性的，
     * 所以这里没有新鲜度问题，只有内存问题：1024 维（text-embedding-v3 实测）一条约 4KB，1000 条约 4MB。
     * 设 0 即关闭缓存。二级缓存（Redis L2）见 {@link #embeddingL2TtlHours}。
     */
    private int embeddingQueryCacheSize = 1000;

    /**
     * 问题向量一级缓存（Caffeine L1）的写后过期分钟数，默认 5 分钟。
     * L1 只追求「同一批问题反复问」的短时命中，过期后回落 L2 / 远程，避免长期占用堆内存。
     */
    private long embeddingQueryCacheExpireMinutes = 5;

    /**
     * 问题向量二级缓存（Redis L2）的存活小时数，默认 24 小时，Key 形如 {@code rag:emb:{sha256}}。
     * L2 跨实例共享：一个实例算过的问题向量，其他实例直接复用，省去公网往返。
     * {@code kb.cache.enabled=false} 或 Redis 不可达时自动退回仅 L1。
     */
    private long embeddingL2TtlHours = 24;

    /**
     * RRF（倒数排名融合）常数 k，得分 = Σ 1/(k + rank)。k 越大越平滑两路排名的差异，
     * 60 是学界与工程界的通用默认值。
     */
    private int rrfK = 60;

    /** Rerank 精排（RRF 粗排之后的 Cross-Encoder 二次排序），详见 {@link Rerank} */
    private Rerank rerank = new Rerank();

    /**
     * 语义召回的最低相似度阈值。设 0 表示只按排名截断、不做绝对分数过滤，
     * 避免同义词命中因分数略低而被全部丢弃。
     */
    private double semanticMinScore = 0.0d;

    /**
     * 词法召回的实现方式：{@code fulltext}（默认，走 MySQL FULLTEXT ngram 索引）
     * 或 {@code like}（逐词 LIKE 兜底，给不支持 {@code MATCH ... AGAINST} 的数据库用，如 H2 测试库）。
     *
     * <p>LIKE 路每展开一个检索词就是一次 {@code LIKE '%词%'} 全表扫描，语料上万条后延迟线性恶化；
     * 全文索引把分词、打分、排序交给引擎，一条 SQL 解决。即使配成 fulltext，
     * 一旦 SQL 抛异常（索引被删、语法不支持）也会自动退回 LIKE，绝不因检索实现而中断问答。
     */
    private String lexicalMode = "fulltext";

    /**
     * 词法召回是否走全文索引。
     *
     * <p>只把显式写成 {@code like} 的情况当作降级路径，配置缺失或拼错都按 fulltext 处理——
     * 否则线上一个手误就会让检索静默退化成全表扫描。
     */
    public boolean isFulltextLexical() {
        return lexicalMode == null || !"like".equalsIgnoreCase(lexicalMode.trim());
    }

    /**
     * 系统级防幻觉 Prompt（资料区之前的规则说明）。
     *
     * <p>留空时问答链路回落到代码内置的 {@code DEFAULT_SYSTEM_PROMPT}；
     * 管理员可以在「引擎参数配置」页运行时修改，不需要重启。
     */
    private String systemPrompt = "";

    /**
     * DashScope gte-rerank 精排参数。
     *
     * <p>流水线位置：双路召回 → RRF 粗排（取 {@link #candidates} 条候选）→ Cross-Encoder 精排
     * → 保留前 {@link #topN} 条。精排是「加分项」而不是「必选项」：开关关闭、未配 API Key、
     * 超时或接口报错时，检索链路自动回退为 RRF 粗排 Top-K，绝不阻断问答主链路。
     *
     * <p>注意 gte-rerank 走百炼<b>原生</b> text-rerank 端点，不是 {@code rag.base-url}
     * 指向的 OpenAI 兼容协议地址，两者不能混用。
     */
    @Data
    public static class Rerank {

        /** 总开关。关闭时精排装饰器直接透传，等价于旧的「双路 + RRF」链路 */
        private boolean enabled = true;

        /**
         * 精排模型名，默认 gte-rerank-v2。
         *
         * <p>不用 v1 的 {@code gte-rerank}：实测该账号调用 v1 返回 403 AccessDenied
         * （百炼已把文本排序主力切到 v2），v2 报文结构与 v1 完全一致。
         */
        private String model = "gte-rerank-v2";

        /** 精排后最多保留条数；实际返回 min(topK, topN)，不会超过调用方要的 topK */
        private int topN = 5;

        /** 送入精排的 RRF 粗排候选条数。太少体现不出精排收益，太多徒增时延与 token 成本 */
        private int candidates = 20;

        /** 精排 HTTP 调用超时秒数（连接与读取各自计时）。超时立刻降级为 RRF 粗排结果 */
        private int timeoutSeconds = 3;

        /** 百炼文本排序原生端点 */
        private String endpoint = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    }
}
