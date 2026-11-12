package com.bishe.kb.benchmark;

import com.bishe.kb.benchmark.BenchmarkReportWriter.ChunkConfigRow;
import com.bishe.kb.benchmark.BenchmarkReportWriter.MetricRow;
import com.bishe.kb.benchmark.BenchmarkReportWriter.QueryRow;
import com.bishe.kb.benchmark.BenchmarkReportWriter.RunSettings;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalService;
import com.bishe.kb.service.RetrievalService.Route;
import com.bishe.kb.service.impl.DashScopeRerankClient;
import com.bishe.kb.service.impl.RerankRetrievalService;
import com.bishe.kb.service.impl.VectorIndexService;
import com.bishe.kb.util.TextChunker;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索评测跑批：实验一「检索策略消融」（含 RRF + gte-rerank 精排附加臂）+ 实验二「切片窗口敏感度」，
 * 输出 Hit Rate@1/3/5、MRR、平均/P95 耗时与论文表格、CSV。
 *
 * <p>三条设计约束，直接决定这些数据能不能写进论文：
 * <ol>
 *   <li><b>跑在生产同一段召回代码上</b>：只调 {@link RetrievalService}，词法打分、RRF 融合、
 *       回表与禁用过滤都与线上完全一致，不存在「评测专用实现」；</li>
 *   <li><b>真实向量模型</b>：注入真 API Key 的 {@link OpenAiEmbeddingModel}，B 类同义改写题
 *       的收益必须来自真实语义相似，而不是测试里那种手工设定的正交向量；</li>
 *   <li><b>不污染任何真实数据</b>：语料与切片全部写进 H2 内存库（MODE=MySQL 复刻生产表结构），
 *       向量库另用 target 下的独立文件，跑完即弃。</li>
 * </ol>
 *
 * <p>默认不随 {@code mvn test} 执行（pom 的 benchmark profile 才把它纳入），
 * 因为它要真实调用 embedding 接口、耗时数分钟且依赖密钥。复现命令：
 * <pre>{@code mvn test -P benchmark -Dtest=RetrievalBenchmarkTest }</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "kb.benchmark", matches = "true",
        disabledReason = "评测跑批需要真实 embedding 调用，请用 mvn test -P benchmark 运行")
class RetrievalBenchmarkTest {

    private static final String EXPERIMENT_1 = "experiment1_ablation";
    private static final String EXPERIMENT_2 = "experiment2_chunk_sensitivity";
    /** 精排附加臂的策略标签：与基线 HYBRID 区分，报告 writer 按显式策略名过滤，互不污染 */
    private static final String STRATEGY_RERANK = "HYBRID_RERANK";
    /** 评测窗口：三档 Hit@K 与 MRR 都在这段候选内计算 */
    private static final int EVAL_WINDOW = 10;
    /** Top-N 平均字数统计口径，N 取生产召回名额 */
    private static final int PROMPT_COST_K = 5;
    /** 精排臂逐题间隔：真实 rerank 接口有 RPM 限流，轻量 pacing 避免连发触发 429（不计入检索耗时） */
    private static final long RERANK_PACING_MILLIS = 300;
    private static final int DOC_ID_BASE = 9000;
    private static final int CHUNK_ID_BASE = 90000;
    private static final List<Route> ROUTES = List.of(Route.LEXICAL, Route.SEMANTIC, Route.HYBRID);
    /** 四档切片粒度，重叠固定为上限的 15%，与生产 800/120 的比例一致 */
    private static final List<int[]> CHUNK_CONFIGS =
            List.of(new int[]{200, 30}, new int[]{500, 75}, new int[]{800, 120}, new int[]{1200, 180});

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RagProperties props;
    /** 注入的是 @Primary 的 RerankRetrievalService 装饰器：精排关闭时透传 RetrievalServiceImpl，与旧链路一致 */
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private DashScopeRerankClient rerankClient;
    /** 与 retrievalService 是同一个 @Primary Bean，按具体类型注入只为读降级计数器 */
    @Autowired
    private RerankRetrievalService rerankPipeline;
    @Autowired
    private VectorIndexService vectorIndexService;
    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 当前粒度下 kb_chunk 全表正文快照。
     *
     * <p>两个用途：一是「证据可判定性」——证据串被切分边界劈开时该题在本粒度下无法判定，
     * 必须从分母里剔除，否则敏感度实验会把「切碎了」误报成「检索变差了」；
     * 二是计算字面重合度时以真正入库的文本为准，而不是原始语料。
     */
    private List<String> currentChunkContents = List.of();

    /**
     * 精排臂「降级题数」：本批精排调用中触发 catch 回退粗排的次数。
     * 用装饰器降级计数器在精排臂前后做差得到，写进报告环境表，
     * 让「精排收益」结论有据可查——全降级时不能据此论证精排有效。
     */
    private int rerankDegradedCount = 0;

    /**
     * 真实向量模型 Bean：{@code rag.embedding-enabled=false} 时主配置不注册该 Bean，
     * 这里补一个用于跑批。密钥从环境变量或 gitignored 的 application-local.yml 取，取不到时
     * 用占位值保证上下文能启动，由用例开头的 Assumption 决定跳过。
     */
    @TestConfiguration
    static class RealEmbeddingModelConfig {

        @Bean
        EmbeddingModel benchmarkEmbeddingModel(RagProperties props) {
            String apiKey = BenchmarkSecrets.resolveApiKey();
            return OpenAiEmbeddingModel.builder()
                    // 占位值不带 sk- 前缀：CI 密钥体检对 sk- 字面量零容忍，占位串也不能撞正则
                    .apiKey(apiKey.isBlank() ? "benchmark-key-not-configured" : apiKey)
                    .baseUrl(props.getBaseUrl())
                    .modelName(props.getEmbeddingModel())
                    .timeout(Duration.ofSeconds(60))
                    .build();
        }
    }

    // ------------------------------------------------------------------ 数据结构

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CorpusFile(List<Section> sections) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Section(String id, String topic, String title, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryFile(List<Query> queries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Query(String id, String category, String question, String targetSection, String evidence) {
    }

    /** 单次检索的观测结果：命中名次（1 基，未命中为 null）、端到端耗时、Top-N 字数、字面重合度 */
    record Case(Query query, Integer rank, long latencyMillis, int topChars,
                double overlap, boolean evidencePresent) {
    }

    @Test
    @DisplayName("消融实验与切片敏感度实验跑批，产出 docs/evaluation 下的报告与 CSV")
    void runAblationAndSensitivityBenchmarks() throws IOException {
        String apiKey = BenchmarkSecrets.resolveApiKey();
        Assumptions.assumeTrue(!apiKey.isBlank(),
                "未找到 rag.api-key（RAG_API_KEY 环境变量或 application-local.yml），跳过真实向量评测");

        CorpusFile corpus = loadCorpus();
        QueryFile queryFile = loadQueries();
        validateDataset(corpus, queryFile);

        props.setApiKey(apiKey);
        props.setEmbeddingEnabled(true);
        props.setMockEnabled(false);
        props.setEmbeddingStorePath("./target/benchmark-embedding-store.json");
        // 基线臂统一关闭精排：LEXICAL / SEMANTIC / HYBRID 三条策略必须保持「纯」，
        // 带精排的生产流水线在下面附加臂单独测，两组数据互不污染
        props.getRerank().setEnabled(false);

        long startedAt = System.currentTimeMillis();
        List<QueryRow> rawRows = new ArrayList<>();

        // ---------------- 实验一：三档检索策略在天然切片上的消融
        seedNaturalCorpus(corpus);
        int naturalChunks = reindex();
        System.out.printf("[benchmark] 实验一语料：%d 段切片，向量化 %d 段%n", naturalChunks, naturalChunks);
        warmUp(queryFile.queries());

        List<MetricRow> ablation = new ArrayList<>();
        for (Route route : ROUTES) {
            List<Case> cases = runRound(EXPERIMENT_1, "natural", route, queryFile.queries(), corpus);
            rawRows.addAll(toQueryRows(EXPERIMENT_1, "natural", route, cases));
            for (String category : List.of("ALL", "A", "B", "C")) {
                ablation.add(aggregate(EXPERIMENT_1, "natural", route, cases, category));
            }
        }

        // ---------------- 实验一附加臂：RRF 粗排 + gte-rerank 精排（生产流水线）
        // top-n 设为评测窗口：精排臂与基线臂同样返回 Top-10，Hit@K / MRR 才是同窗口公平对比
        props.getRerank().setEnabled(true);
        props.getRerank().setTopN(EVAL_WINDOW);
        assertThat(rerankClient.isAvailable())
                .as("Rerank 消融臂需要真实 API Key 且 rag.rerank.enabled=true")
                .isTrue();
        warmUpRerank(queryFile.queries());
        // 预热也可能触发降级，基线计数必须在预热之后取，差值才只统计正式跑批的题
        long fallbackBefore = rerankPipeline.fallbackCount();
        List<Case> rerankCases = runRound(EXPERIMENT_1, "natural", STRATEGY_RERANK, Route.HYBRID,
                queryFile.queries(), corpus, RERANK_PACING_MILLIS);
        rerankDegradedCount = (int) (rerankPipeline.fallbackCount() - fallbackBefore);
        System.out.printf("[benchmark] 精排臂降级题数：%d / %d%n", rerankDegradedCount, queryFile.queries().size());
        rawRows.addAll(toQueryRows(EXPERIMENT_1, "natural", STRATEGY_RERANK, rerankCases));
        for (String category : List.of("ALL", "A", "B", "C")) {
            ablation.add(aggregate(EXPERIMENT_1, "natural", STRATEGY_RERANK, rerankStrategyName(),
                    rerankCases, category));
        }
        // 实验二（切片敏感度）保持基线流水线，隔离「粒度」这一个变量
        props.getRerank().setEnabled(false);

        // ---------------- 实验二：切片窗口大小敏感度
        List<MetricRow> sensitivity = new ArrayList<>();
        List<ChunkConfigRow> configs = new ArrayList<>();
        for (int i = 0; i < CHUNK_CONFIGS.size(); i++) {
            int maxChars = CHUNK_CONFIGS.get(i)[0];
            int overlap = CHUNK_CONFIGS.get(i)[1];
            String config = String.valueOf(maxChars);
            ChunkStatistics stats = seedChunkedCorpus(corpus, maxChars, overlap, i);
            int indexed = reindex();
            if (indexed != stats.chunkCount()) {
                throw new IllegalStateException("向量化写入不完整: 期望 " + stats.chunkCount() + " 实际 " + indexed);
            }
            System.out.printf("[benchmark] 实验二 %d/%d：chunk-max-chars=%d 切出 %d 段（平均 %.0f 字）%n",
                    i + 1, CHUNK_CONFIGS.size(), maxChars, stats.chunkCount(), stats.avgChars());

            MetricRow hybridRow = null;
            for (Route route : ROUTES) {
                List<Case> cases = runRound(EXPERIMENT_2, config, route, queryFile.queries(), corpus);
                rawRows.addAll(toQueryRows(EXPERIMENT_2, config, route, cases));
                MetricRow row = aggregate(EXPERIMENT_2, config, route, cases, "ALL");
                sensitivity.add(row);
                if (route == Route.HYBRID) {
                    hybridRow = row;
                }
            }
            configs.add(new ChunkConfigRow(maxChars, overlap, stats.chunkCount(), stats.avgChars(),
                    hybridRow.answerable(), hybridRow.hitAt1(), hybridRow.hitAt5(), hybridRow.mrr(),
                    hybridRow.avgLatencyMillis(), hybridRow.avgTop5Chars()));
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        // ---------------- 落盘 + 打印
        RunSettings settings = runSettings();
        Path outputDir = Path.of(System.getProperty("kb.benchmark.out", "../docs/evaluation"));
        BenchmarkReportWriter writer = new BenchmarkReportWriter(outputDir, settings);
        writer.write(ablation, sensitivity, configs, rawRows,
                environment(elapsed, settings), dataset(corpus, queryFile));

        printTables(ablation, sensitivity, configs);
        props.setEmbeddingEnabled(false);
    }

    // ------------------------------------------------------------------ 跑批内核

    /** 一轮实验：同一份语料 + 一个检索路由，把全部问题各检索一次（基线臂无 pacing） */
    private List<Case> runRound(String experiment, String config, Route route,
                               List<Query> queries, CorpusFile corpus) {
        return runRound(experiment, config, route.name(), route, queries, corpus, 0);
    }

    /**
     * 带策略标签的一轮：精排附加臂复用 HYBRID 路由，但报告里要用不同标签与基线区分。
     * pacingMillis 是逐题之间的额外间隔（精排臂防限流用），在计时窗口之外，不污染耗时指标。
     */
    private List<Case> runRound(String experiment, String config, String label, Route route,
                               List<Query> queries, CorpusFile corpus, long pacingMillis) {
        System.out.printf("[benchmark] %s / %s / %s 开始%n", experiment, config, label);
        List<Case> cases = new ArrayList<>(queries.size());
        for (Query query : queries) {
            if (pacingMillis > 0 && !cases.isEmpty()) {
                sleepBetweenQueries(pacingMillis);
            }
            cases.add(retrieveOnce(query, route, corpus));
        }
        return cases;
    }

    private static void sleepBetweenQueries(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("评测跑批被中断", e);
        }
    }

    private Case retrieveOnce(Query query, Route route, CorpusFile corpus) {
        long start = System.nanoTime();
        List<KbChunk> hits = retrievalService.retrieve(query.question(), EVAL_WINDOW, route);
        long millis = (System.nanoTime() - start) / 1_000_000;

        Integer rank = null;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).getContent().contains(query.evidence())) {
                rank = i + 1;
                break;
            }
        }
        int topChars = hits.stream().limit(PROMPT_COST_K).mapToInt(c -> c.getContent().length()).sum();
        // 证据是否仍能被单段切片整段包住：切分边界劈开证据的题，在本粒度下无法判定，需从分母剔除
        boolean evidencePresent = currentChunkContents.stream().anyMatch(c -> c.contains(query.evidence()));
        String overlapText = currentChunkContents.stream()
                .filter(c -> c.contains(query.evidence()))
                .findFirst()
                .orElseGet(() -> sectionText(corpus, query.targetSection()));
        double overlap = literalOverlap(query.question(), overlapText);
        return new Case(query, rank, millis, topChars, overlap, evidencePresent);
    }

    /** 预热：先跑几轮丢弃计时，让 HTTP 连接池、JIT 与数据库缓冲池进入稳定状态 */
    private void warmUp(List<Query> queries) {
        List<Query> sample = queries.subList(0, Math.min(3, queries.size()));
        for (Route route : ROUTES) {
            for (Query query : sample) {
                retrievalService.retrieve(query.question(), EVAL_WINDOW, route);
            }
        }
    }

    /** 精排臂预热：rerank 的 TLS 握手与 JIT 需要几次真实调用才稳定，否则首题耗时会被计进 P95 */
    private void warmUpRerank(List<Query> queries) {
        for (Query query : queries.subList(0, Math.min(2, queries.size()))) {
            retrievalService.retrieve(query.question(), EVAL_WINDOW, Route.HYBRID);
        }
    }

    /** 指标聚合：Hit@K 与 MRR 的分母只算「证据在该粒度下仍可判定」的题数 */
    private MetricRow aggregate(String experiment, String config, Route route,
                                List<Case> cases, String category) {
        return aggregate(experiment, config, route.name(), strategyName(route), cases, category);
    }

    /** 带策略标签的聚合：精排附加臂与基线臂共用同一套口径，只有标签不同 */
    private MetricRow aggregate(String experiment, String config, String strategy, String strategyCn,
                                List<Case> cases, String category) {
        List<Case> scoped = "ALL".equals(category)
                ? cases
                : cases.stream().filter(c -> c.query().category().equals(category)).toList();
        List<Case> answerable = scoped.stream().filter(c -> c.evidencePresent()).toList();

        double hit1 = rate(answerable, 1);
        double hit3 = rate(answerable, 3);
        double hit5 = rate(answerable, 5);
        double mrr = answerable.isEmpty() ? 0 : answerable.stream()
                .mapToDouble(c -> c.rank() == null ? 0 : 1.0 / c.rank()).average().orElse(0);
        List<Long> latencies = scoped.stream().map(Case::latencyMillis).sorted().toList();
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = percentile(latencies, 0.95);
        double avgTopChars = answerable.isEmpty() ? 0 : answerable.stream()
                .mapToInt(Case::topChars).average().orElse(0);
        double avgOverlap = scoped.stream().mapToDouble(Case::overlap).average().orElse(0);

        return new MetricRow(experiment, config, strategy, strategyCn, category,
                scoped.size(), answerable.size(), hit1, hit3, hit5, mrr,
                avgLatency, p95, avgTopChars, avgOverlap);
    }

    private static double rate(List<Case> cases, int k) {
        if (cases.isEmpty()) {
            return 0;
        }
        long hits = cases.stream().filter(c -> c.rank() != null && c.rank() <= k).count();
        return (double) hits / cases.size();
    }

    private static double percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String strategyName(Route route) {
        return switch (route) {
            case LEXICAL -> "纯词法（2-gram LIKE）";
            case SEMANTIC -> "纯语义向量";
            case HYBRID -> "混合双路 + RRF(k=60)";
        };
    }

    /** 精排臂标签：模型名与 RRF 常数都取运行时配置，报告与配置永远一致 */
    private String rerankStrategyName() {
        return "混合双路 + RRF(k=" + props.getRrfK() + ") + " + props.getRerank().getModel() + " 精排";
    }

    // ------------------------------------------------------------------ 语料装载

    private CorpusFile loadCorpus() throws IOException {
        try (InputStream in = new ClassPathResource("benchmark/corpus.json").getInputStream()) {
            return objectMapper.readValue(in, CorpusFile.class);
        }
    }

    private QueryFile loadQueries() throws IOException {
        try (InputStream in = new ClassPathResource("benchmark/queries.json").getInputStream()) {
            return objectMapper.readValue(in, QueryFile.class);
        }
    }

    /** 数据集自检：标注必须与语料对得上，否则整轮实验的命中率会失真 */
    private void validateDataset(CorpusFile corpus, QueryFile queries) {
        Map<String, Section> byId = new LinkedHashMap<>();
        corpus.sections().forEach(s -> byId.put(s.id(), s));
        List<String> problems = new ArrayList<>();
        for (Query query : queries.queries()) {
            Section section = byId.get(query.targetSection());
            if (section == null) {
                problems.add(query.id() + " 指向了不存在的切片 " + query.targetSection());
            } else if (!section.text().contains(query.evidence())) {
                problems.add(query.id() + " 的证据串「" + query.evidence() + "」不在 " + section.id() + " 正文里");
            }
        }
        long uncovered = byId.keySet().stream()
                .filter(id -> queries.queries().stream().noneMatch(q -> q.targetSection().equals(id)))
                .count();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("评测数据集标注有误：\n" + String.join("\n", problems));
        }
        System.out.printf("[benchmark] 数据集校验通过：%d 段切片 / %d 题，未被覆盖的切片 %d 段%n",
                byId.size(), queries.queries().size(), uncovered);
    }

    /** 实验一：一段知识 = 一个切片，完全绕开分块器，考察「检索策略」本身 */
    private void seedNaturalCorpus(CorpusFile corpus) {
        clearKbTables();
        int docId = DOC_ID_BASE;
        int chunkId = CHUNK_ID_BASE;
        for (String topic : topics(corpus)) {
            docId++;
            jdbc.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                            + "values (?, 1, ?, 'txt', 'INDEXED', 0, 0)",
                    docId, topic + "评测语料");
            int index = 0;
            for (Section section : corpus.sections()) {
                if (!section.topic().equals(topic)) {
                    continue;
                }
                chunkId++;
                jdbc.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                                + "values (?, ?, ?, ?, ?, 1)",
                        (long) chunkId, (long) docId, index++, section.text(), section.text().length());
            }
        }
        jdbc.update("update kb_document set chunk_count = "
                + "(select count(*) from kb_chunk where kb_chunk.document_id = kb_document.id)");
    }

    /** 实验二：把同主题切片拼成整篇文档，交给生产分块器按指定粒度重切 */
    private ChunkStatistics seedChunkedCorpus(CorpusFile corpus, int maxChars, int overlap, int configIndex) {
        clearKbTables();
        int docId = DOC_ID_BASE;
        int chunkId = CHUNK_ID_BASE + (configIndex + 1) * 1000;
        int total = 0;
        long chars = 0;
        for (String topic : topics(corpus)) {
            docId++;
            StringBuilder full = new StringBuilder("【" + topic + "】");
            for (Section section : corpus.sections()) {
                if (section.topic().equals(topic)) {
                    full.append("\n\n").append(section.title()).append("。").append(section.text());
                }
            }
            List<String> pieces = TextChunker.chunk(full.toString(), maxChars, overlap);
            jdbc.update("insert into kb_document (id, space_id, title, file_type, status, chunk_count, deleted) "
                            + "values (?, 1, ?, 'txt', 'INDEXED', ?, 0)",
                    docId, topic + "评测语料", pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                chunkId++;
                String piece = pieces.get(i);
                jdbc.update("insert into kb_chunk (id, document_id, chunk_index, content, char_count, enabled) "
                                + "values (?, ?, ?, ?, ?, 1)",
                        (long) chunkId, (long) docId, i, piece, piece.length());
                total++;
                chars += piece.length();
            }
        }
        return new ChunkStatistics(total, total == 0 ? 0 : (double) chars / total);
    }

    private record ChunkStatistics(int chunkCount, double avgChars) {
    }

    private void clearKbTables() {
        embeddingStore.removeAll();
        jdbc.update("delete from kb_chunk");
        jdbc.update("delete from kb_document");
    }

    /** 清空后重新向量化当前 kb_chunk 全表，返回实际写入条数 */
    private int reindex() {
        embeddingStore.removeAll();
        List<KbChunk> chunks = chunkMapper.selectList(null);
        this.currentChunkContents = chunks.stream().map(KbChunk::getContent).toList();
        int indexed = vectorIndexService.indexChunks(chunks);
        if (indexed == 0) {
            throw new IllegalStateException("向量化写入 0 条，请检查 embedding 接口密钥与网络");
        }
        return indexed;
    }

    private static List<String> topics(CorpusFile corpus) {
        return new ArrayList<>(new TreeSet<>(corpus.sections().stream().map(Section::topic).toList()));
    }

    private static String sectionText(CorpusFile corpus, String sectionId) {
        return corpus.sections().stream()
                .filter(s -> s.id().equals(sectionId))
                .map(Section::text)
                .findFirst()
                .orElse("");
    }

    // ------------------------------------------------------------------ 指标辅助

    /**
     * 字面重合度：问题归一化后，中文按字符 2-gram、英文数字按整词，统计其中有多少出现在目标切片里。
     *
     * <p>它是 B 类问题的构造证据——重合度越低，说明该题越不可能被词法路命中，
     * 从而把「语义检索有效」这个结论从主观判断变成可量化事实。
     */
    static double literalOverlap(String question, String targetText) {
        List<String> terms = literalTerms(question);
        if (terms.isEmpty() || targetText.isEmpty()) {
            return 0;
        }
        String normalizedTarget = normalize(targetText);
        long matched = terms.stream().filter(normalizedTarget::contains).count();
        return (double) matched / terms.size();
    }

    private static List<String> literalTerms(String question) {
        String normalized = normalize(question);
        List<String> terms = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                flushLatin(latin, terms);
                if (i + 1 < normalized.length()) {
                    terms.add(normalized.substring(i, i + 2));
                }
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else {
                flushLatin(latin, terms);
            }
        }
        flushLatin(latin, terms);
        return terms.stream().distinct().toList();
    }

    private static void flushLatin(StringBuilder latin, List<String> terms) {
        if (latin.length() > 0) {
            terms.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 0x4E00 && c <= 0x9FFF) || Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<QueryRow> toQueryRows(String experiment, String config, Route route, List<Case> cases) {
        return toQueryRows(experiment, config, route.name(), cases);
    }

    private List<QueryRow> toQueryRows(String experiment, String config, String strategy, List<Case> cases) {
        return cases.stream()
                .map(c -> new QueryRow(experiment, config, strategy, c.query().id(), c.query().category(),
                        c.query().question(), c.query().targetSection(), c.query().evidence(),
                        c.rank(), c.rank() != null && c.rank() <= PROMPT_COST_K, c.latencyMillis(),
                        c.overlap(), c.evidencePresent()))
                .toList();
    }

    // ------------------------------------------------------------------ 报告元信息

    private Map<String, String> environment(long elapsedMillis, RunSettings settings) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("跑批时间", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        map.put("总耗时", String.format(Locale.ROOT, "%.1f 秒", elapsedMillis / 1000.0));
        map.put("JDK", System.getProperty("java.version") + " / " + System.getProperty("os.name"));
        map.put("生成侧配置", "未调用大模型，本实验只评测召回阶段");
        map.put("向量模型", settings.embeddingModel() + "（" + props.getBaseUrl() + "）");
        map.put("向量库实现", "InMemoryEmbeddingStore + 本地 JSON 序列化（一期方案）");
        map.put("召回代码", "RerankRetrievalService 装饰器 → RetrievalServiceImpl，与线上问答完全一致");
        map.put("RRF 常数 k", String.valueOf(props.getRrfK()));
        map.put("Rerank 精排", props.getRerank().getModel() + "（百炼原生 text-rerank 端点；粗排候选 "
                + props.getRerank().getCandidates() + " 条、超时 " + props.getRerank().getTimeoutSeconds()
                + "s；消融臂 top-n=" + EVAL_WINDOW + " 与基线同窗口，基线臂关闭精排）");
        // 纯数字：writer 的 intOf 会剥掉非数字字符，"3 / 92" 会被解析成 392
        map.put("精排臂降级题数", String.valueOf(rerankDegradedCount));
        map.put("评测窗口 K", String.valueOf(EVAL_WINDOW));
        map.put("Prompt 成本口径", "Top-" + settings.productionTopK() + " 平均字符数"
                + "（取自 application.yml 的生产召回名额 top-k=" + settings.productionTopK()
                + "，测试 profile 里的 top-k=" + props.getTopK() + " 不用于本实验）");
        map.put("生产切分默认", "chunk-max-chars=" + settings.productionChunkMaxChars()
                + " / chunk-overlap-chars=" + settings.productionChunkOverlapChars()
                + "（application.yml；测试 profile 的 " + props.getChunkMaxChars() + "/"
                + props.getChunkOverlapChars() + " 不生效）");
        map.put("语义最低相似度", String.valueOf(props.getSemanticMinScore()));
        map.put("词法最大检索词数", "12");
        map.put("向量批量条数", String.valueOf(props.getEmbeddingBatchSize()));
        map.put("数据库", "H2 内存库 MODE=MySQL，复刻生产 kb_document / kb_chunk 表结构，不接触 kb_rag");
        map.put("词法召回实现", "rag.lexical-mode=" + props.getLexicalMode()
                + "（H2 不支持 MATCH ... AGAINST，评测固定用 like 分支；生产默认 fulltext，"
                + "两种实现的性能对照见 docs/bench/lexical_ab_results.csv）");
        map.put("命中判定", "召回切片正文包含标注证据串（evidence），粒度变化时标注仍然有效");
        map.put("指标口径", "Hit@K = 前 K 名内出现证据的题占比；MRR 为证据首个名次的倒数均值，窗口外记 0");
        return map;
    }

    /**
     * 生产参数快照：从 {@code application.yml} 读默认切分粒度与召回名额，而不是测试 profile 改小过的值。
     *
     * <p>Top-K 口径直接决定报告里的「Top-5」是否等价于线上进入 Prompt 的片段数，
     * 因此这里用断言拦住漂移——改了生产 topK 却忘了同步评测常量时会立刻失败，
     * 而不是产出一份与线上配置对不上号的报告。
     */
    private RunSettings runSettings() {
        Properties main = BenchmarkSecrets.productionRagConfig();
        int chunkMaxChars = intOf(main, "rag.chunk-max-chars", 800);
        int chunkOverlapChars = intOf(main, "rag.chunk-overlap-chars", 120);
        int topK = intOf(main, "rag.top-k", 5);
        assertThat(topK)
                .as("评测的 Prompt 成本口径必须与生产召回名额一致，请同步修改 PROMPT_COST_K 或 application.yml")
                .isEqualTo(PROMPT_COST_K);
        return new RunSettings(chunkMaxChars, chunkOverlapChars, topK, props.getEmbeddingModel(),
                "mvn -o -Dmaven.repo.local=<本地仓库> test -P benchmark -Dtest=RetrievalBenchmarkTest");
    }

    private static int intOf(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, String> dataset(CorpusFile corpus, QueryFile queries) {
        int totalChars = corpus.sections().stream().mapToInt(s -> s.text().length()).sum();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("知识切片数", String.valueOf(corpus.sections().size()));
        map.put("主题分布", String.join("、", topics(corpus).stream()
                .map(t -> t + " " + corpus.sections().stream().filter(s -> s.topic().equals(t)).count() + " 段")
                .toList()));
        map.put("语料总字数", String.valueOf(totalChars));
        map.put("平均切片字数", String.format(Locale.ROOT, "%.1f", (double) totalChars / corpus.sections().size()));
        map.put("问题总数", String.valueOf(queries.queries().size()));
        for (String category : List.of("A", "B", "C")) {
            map.put(category + " 类题数", String.valueOf(queries.queries().stream()
                    .filter(q -> q.category().equals(category)).count()));
        }
        long covered = corpus.sections().stream()
                .filter(s -> queries.queries().stream().anyMatch(q -> q.targetSection().equals(s.id())))
                .count();
        map.put("每题目标切片", "唯一（人工标注），" + covered + " / " + corpus.sections().size()
                + " 段切片被至少一题覆盖");
        map.put("证据串唯一性", "每条 evidence 在全部语料中只出现一次，避免跨切片假命中");
        map.put("字面重合度定义", "问题中文 2-gram + 英数整词中，出现在目标切片里的比例");
        return map;
    }

    /** 把两张核心表同时打到控制台，跑完直接能在构建日志里看到结果 */
    private void printTables(List<MetricRow> ablation, List<MetricRow> sensitivity,
                             List<ChunkConfigRow> configs) {
        System.out.println("\n================ 实验一：检索策略消融（总体 Top-10 窗口） ================");
        System.out.printf("%-22s %-8s %-6s %-6s %-6s %-7s %-8s%n",
                "策略", "类别", "Hit@1", "Hit@3", "Hit@5", "MRR", "均耗时ms");
        for (MetricRow r : ablation) {
            System.out.printf(Locale.ROOT, "%-22s %-8s %-6.2f %-6.2f %-6.2f %-7.3f %-8.1f%n",
                    r.strategyCn(), r.category(), r.hitAt1(), r.hitAt3(), r.hitAt5(), r.mrr(),
                    r.avgLatencyMillis());
        }
        System.out.println("\n================ 实验二：切片窗口敏感度（混合检索） ================");
        System.out.printf("%-10s %-8s %-10s %-7s %-7s %-7s %-9s %-10s%n",
                "粒度", "切片数", "Hit@1", "Hit@5", "MRR", "均耗时", "Top5字数", "可判定题数");
        for (ChunkConfigRow c : configs) {
            System.out.printf(Locale.ROOT, "%-10d %-8d %-10.2f %-7.2f %-7.3f %-9.1f %-10.0f %-10d%n",
                    c.chunkMaxChars(), c.chunkCount(), c.hybridHitAt1(), c.hybridHitAt5(), c.hybridMrr(),
                    c.hybridAvgLatencyMillis(), c.hybridAvgTop5Chars(), c.answerableQueries());
        }
        System.out.println("\n sensitivity rows（含三策略）共 " + sensitivity.size() + " 行，明细见 query_level_results.csv");
    }
}
