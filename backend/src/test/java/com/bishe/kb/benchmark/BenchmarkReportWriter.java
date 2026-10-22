package com.bishe.kb.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评测结果落盘：把跑批数据写成论文可直接引用的 Markdown 三线表 + Excel/ECharts 可导入的 CSV。
 *
 * <p>报告正文的分析句子里全部嵌入实测数值，重跑一次就能得到与表格一致的新结论，
 * 不会出现「表格里 0.75、正文里写 0.8」这种手工誊抄错误。
 */
public final class BenchmarkReportWriter {

    /** 与 {@code RetrievalBenchmarkTest} 中的实验编号保持一致，用于从逐题明细里筛实验一 */
    private static final String EXPERIMENT_1 = "experiment1_ablation";

    /**
     * 生产参数快照（来自 application.yml，而不是测试 profile 里改小过的值）。
     *
     * <p>报告里的「Prompt 成本口径」「粒度是否踩在默认值上」这类论断都必须引用真实默认配置，
     * 否则论文写的 topK=5 与实验表格的 Top-5 会对不上。
     */
    public record RunSettings(int productionChunkMaxChars,
                              int productionChunkOverlapChars,
                              int productionTopK,
                              String embeddingModel,
                              String command) {
    }

    /** 单个「实验 × 策略 × 问题类别」单元的汇总指标 */
    public record MetricRow(String experiment,
                            String config,
                            String strategy,
                            String strategyCn,
                            String category,
                            int queries,
                            int answerable,
                            double hitAt1,
                            double hitAt3,
                            double hitAt5,
                            double mrr,
                            double avgLatencyMillis,
                            double p95LatencyMillis,
                            double avgTop5Chars,
                            double avgOverlap) {
    }

    /** 切片窗口敏感度实验的每档配置概览 */
    public record ChunkConfigRow(int chunkMaxChars,
                                 int chunkOverlapChars,
                                 int chunkCount,
                                 double avgChunkChars,
                                 int answerableQueries,
                                 double hybridHitAt1,
                                 double hybridHitAt5,
                                 double hybridMrr,
                                 double hybridAvgLatencyMillis,
                                 double hybridAvgTop5Chars) {
    }

    /** 逐题明细，便于在 Excel 里做方差分析或画散点图 */
    public record QueryRow(String experiment,
                           String config,
                           String strategy,
                           String queryId,
                           String category,
                           String question,
                           String targetSection,
                           String evidence,
                           Integer evidenceRank,
                           boolean hitAt5,
                           long latencyMillis,
                           double overlap,
                           boolean evidenceInCorpus) {
    }

    private final Path outputDir;
    private final RunSettings settings;

    public BenchmarkReportWriter(Path outputDir, RunSettings settings) {
        this.outputDir = outputDir;
        this.settings = settings;
    }

    /** 一次性输出三份 CSV 与实验报告 */
    public void write(List<MetricRow> ablation,
                      List<MetricRow> sensitivity,
                      List<ChunkConfigRow> configs,
                      List<QueryRow> queryRows,
                      Map<String, String> environment,
                      Map<String, String> dataset) throws IOException {
        Files.createDirectories(outputDir);
        Path ablationCsv = outputDir.resolve("ablation_results.csv");
        Path chunkCsv = outputDir.resolve("chunk_sensitivity.csv");
        Path rawCsv = outputDir.resolve("query_level_results.csv");
        Path report = outputDir.resolve("experiment_report.md");

        writeCsv(ablationCsv, ablation);
        writeChunkCsv(chunkCsv, sensitivity, configs);
        writeQueryCsv(rawCsv, queryRows);
        Files.writeString(report, renderMarkdown(ablation, sensitivity, configs, queryRows,
                environment, dataset), StandardCharsets.UTF_8);

        System.out.println("[benchmark] 结果已写入 " + outputDir.toAbsolutePath().normalize());
        System.out.println("[benchmark]   - " + report.getFileName()
                + " / " + ablationCsv.getFileName()
                + " / " + chunkCsv.getFileName()
                + " / " + rawCsv.getFileName());
    }

    // ------------------------------------------------------------------ CSV

    private void writeCsv(Path file, List<MetricRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder("experiment,config,strategy,category,queries,answerable_queries,"
                + "hit_rate_at_1,hit_rate_at_3,hit_rate_at_5,mrr,avg_latency_ms,p95_latency_ms,"
                + "avg_top5_chars,avg_literal_overlap\n");
        for (MetricRow r : rows) {
            sb.append(r.experiment()).append(',')
                    .append(quote(r.config())).append(',')
                    .append(r.strategy()).append(',')
                    .append(r.category()).append(',')
                    .append(r.queries()).append(',')
                    .append(r.answerable()).append(',')
                    .append(num(r.hitAt1())).append(',')
                    .append(num(r.hitAt3())).append(',')
                    .append(num(r.hitAt5())).append(',')
                    .append(num(r.mrr())).append(',')
                    .append(num(r.avgLatencyMillis())).append(',')
                    .append(num(r.p95LatencyMillis())).append(',')
                    .append(num(r.avgTop5Chars())).append(',')
                    .append(num(r.avgOverlap())).append('\n');
        }
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    private void writeChunkCsv(Path file, List<MetricRow> sensitivity, List<ChunkConfigRow> configs)
            throws IOException {
        StringBuilder sb = new StringBuilder("experiment,chunk_max_chars,chunk_overlap_chars,strategy,"
                + "chunk_count,avg_chunk_chars,queries,answerable_queries,hit_rate_at_1,hit_rate_at_3,"
                + "hit_rate_at_5,mrr,avg_latency_ms,p95_latency_ms,avg_top5_chars\n");
        Map<Integer, ChunkConfigRow> bySize = new java.util.LinkedHashMap<>();
        for (ChunkConfigRow c : configs) {
            bySize.put(c.chunkMaxChars(), c);
        }
        for (MetricRow r : sensitivity) {
            ChunkConfigRow c = bySize.get(parseSize(r.config()));
            sb.append("experiment2_chunk_sensitivity,").append(r.config()).append(',')
                    .append(c == null ? "" : c.chunkOverlapChars()).append(',')
                    .append(r.strategy()).append(',')
                    .append(c == null ? "" : c.chunkCount()).append(',')
                    .append(c == null ? "" : num(c.avgChunkChars())).append(',')
                    .append(r.queries()).append(',')
                    .append(r.answerable()).append(',')
                    .append(num(r.hitAt1())).append(',')
                    .append(num(r.hitAt3())).append(',')
                    .append(num(r.hitAt5())).append(',')
                    .append(num(r.mrr())).append(',')
                    .append(num(r.avgLatencyMillis())).append(',')
                    .append(num(r.p95LatencyMillis())).append(',')
                    .append(num(r.avgTop5Chars())).append('\n');
        }
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    private void writeQueryCsv(Path file, List<QueryRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder("experiment,config,strategy,query_id,category,question,"
                + "target_section,evidence,evidence_rank,hit_at_5,latency_ms,literal_overlap,"
                + "evidence_in_corpus\n");
        for (QueryRow r : rows) {
            sb.append(r.experiment()).append(',')
                    .append(quote(r.config())).append(',')
                    .append(quote(r.strategy())).append(',')
                    .append(quote(r.queryId())).append(',')
                    .append(quote(r.category())).append(',')
                    .append(quote(r.question())).append(',')
                    .append(quote(r.targetSection())).append(',')
                    .append(quote(r.evidence())).append(',')
                    .append(r.evidenceRank() == null ? "" : r.evidenceRank()).append(',')
                    .append(r.hitAt5() ? 1 : 0).append(',')
                    .append(r.latencyMillis()).append(',')
                    .append(num(r.overlap())).append(',')
                    .append(r.evidenceInCorpus() ? 1 : 0).append('\n');
        }
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ Markdown 报告

    private String renderMarkdown(List<MetricRow> ablation,
                                  List<MetricRow> sensitivity,
                                  List<ChunkConfigRow> configs,
                                  List<QueryRow> queryRows,
                                  Map<String, String> environment,
                                  Map<String, String> dataset) {
        StringBuilder md = new StringBuilder();
        int sections = intOf(dataset, "知识切片数", 0);
        int evalWindow = intOf(environment, "评测窗口 K", 10);
        int totalQueries = ablation.stream()
                .filter(r -> "ALL".equals(r.category()) && "HYBRID".equals(r.strategy()))
                .mapToInt(MetricRow::queries).findFirst().orElse(0);
        md.append("# RAG 检索策略消融实验与学术指标评测报告\n\n");
        md.append("> 本报告由 `RetrievalBenchmarkTest` 自动跑批生成，数据真实可复现；"
                + "重跑命令见文末「复现方式」。\n\n");

        md.append("## 1 实验环境\n\n");
        md.append("| 项目 | 取值 |\n| --- | --- |\n");
        environment.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append('\n');

        md.append("## 2 评测数据集\n\n");
        md.append("| 项目 | 取值 |\n| --- | --- |\n");
        dataset.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append('\n');
        md.append("问题集按预期检索优势分三类：A 类含专有名词/参数名/数字（考察词法检索）；"
                + "B 类为同义或近义改写，与原文几乎无字面 2-gram 重叠（考察语义检索）；"
                + "C 类术语与泛化并存（考察混合策略）。"
                + "三类问题与目标切片的平均字面重合度实测为 " + overlapSummary(ablation)
                + overlapCompare(ablation, sections) + "\n\n");

        md.append("## 3 实验一：检索策略消融实验\n\n");
        md.append("统一取 Top-" + evalWindow + " 候选，Hit@K 按前 1/3/5 名截断，MRR 在 Top-" + evalWindow
                + " 窗口内取首个命中名次的倒数"
                + "（窗口内未命中记 0）。耗时为单次召回端到端时间，语义路与混合路包含一次问题向量化网络请求。\n\n");
        md.append("### 3.1 总体指标\n\n");
        md.append("**表 1 三种检索策略的总体召回性能（评测窗口 Top-" + evalWindow + "，n=" + totalQueries + "）**\n\n");
        md.append(tableOf(filter(ablation, "ALL")));
        md.append('\n');
        md.append("### 3.2 分问题类别指标\n\n");
        md.append("**表 2 三种检索策略在 A/B/C 三类问题上的召回性能（" + categorySampleSizes(ablation) + "）**\n\n");
        for (String category : List.of("A", "B", "C")) {
            md.append("**").append(category).append(" 类问题**\n\n");
            md.append(tableOf(filterCategory(ablation, category)));
            md.append('\n');
        }
        md.append(ablationAnalysis(ablation, queryRows, sections, evalWindow));
        md.append("### 3.3 两路互补性的逐题证据\n\n");
        md.append(complementarity(queryRows));

        md.append("## 4 实验二：切片窗口大小敏感度实验\n\n");
        md.append("四档配置保持重叠比例为单块上限的 15%（"
                + String.join("/", configs.stream().map(c -> String.valueOf(c.chunkOverlapChars())).toList())
                + " 字），语料与问题集不变，"
                + "仅改变 `TextChunker` 的切分粒度后重新向量化入库。\n\n");
        md.append("### 4.1 切分结果概览\n\n");
        md.append("**表 3 四档切分粒度在库内产生的切片统计**\n\n");
        md.append(chunkConfigTable(configs, ablation.isEmpty() ? 0 : ablation.get(0).queries()));
        md.append('\n');
        md.append("### 4.2 各策略在四档粒度下的指标\n\n");
        md.append("**表 4 四档切分粒度 × 三种检索策略的召回性能、耗时与 Top-5 平均字数**\n\n");
        md.append(sensitivityTable(sensitivity, configs));
        md.append('\n');
        md.append(sensitivityAnalysis(sensitivity, configs, evalWindow));

        md.append("## 5 结论\n\n")
                .append(conclusions(ablation, sensitivity, configs, intOf(environment, "RRF 常数 k", 60)))
                .append('\n');

        md.append("## 6 局限与注意事项\n\n");
        md.append("- 命中判定采用「证据片段包含」而非切片 ID 比对：切片粒度变化后同一条标注仍然有效，"
                + "但极短的证据串若恰好被切分边界劈开，会造成假阴性；`query_level_results.csv` 的 "
                + "`evidence_in_corpus` 列记录了每题证据在该粒度下是否仍可整段命中，"
                + "`answerable_queries` 列已把不可判定的样本从分母中剔除。\n");
        md.append(ceilingNote(sections, evalWindow));
        md.append("- 跑批本身不含随机项：词法打分、余弦相似度与 RRF 排序都是确定性的，同一份语料与问题集重跑，"
                + "Hit@K 与 MRR 不会变化，只有耗时字段抖动；若论文需要置信区间，可多次执行跑批后按 "
                + "`config + strategy` 合并 CSV 再统计。语义路耗时受公网调用延迟影响明显，与本地词法路的耗时"
                + "不可直接等价比较，因此本实验以召回质量为主要论证对象。\n");
        md.append("- 向量库为一期内存实现（序列化到本地 JSON），十万级语料下的性能与召回表现需另行验证。\n");
        md.append("- 词法路耗时是「H2 内存库 + " + sections
                + " 段候选 + 逐词 LIKE 扫描」口径下的数字，与生产 MySQL 十万级语料不可直接比较；"
                + "生产默认 `rag.lexical-mode=fulltext`（ngram 全文索引 + MATCH AGAINST），"
                + "两种词法实现的性能对照见 `docs/bench/lexical_ab_results.csv`。\n");
        md.append("- 语料与问题集为人工构造，规模 " + sections + " 段 / " + totalQueries
                + " 题，属于教学级评测集，"
                + "不代表公开基准（如 DuReader、MS MARCO）上的可比结果。\n\n");

        md.append("## 7 附录：逐题明细（混合检索在实验一中的全部结果）\n\n");
        md.append("**表 5 逐题检索明细（混合检索，实验一）**\n\n");
        md.append(querySampleTable(queryRows, evalWindow));
        md.append('\n');
        md.append("## 8 复现方式\n\n");
        md.append("```bash\n").append(settings.command()).append("\n```\n\n");
        md.append("评测执行器是一个受系统属性开关控制的 JUnit 用例（`kb.benchmark=true` 时才执行），"
                + "普通 `mvn test` 会连同 benchmark 包一起排除，因此不会拖慢日常构建，"
                + "也不会在无密钥的 CI 环境里失败。数据集为 "
                + "`src/test/resources/benchmark/corpus.json` 与 `queries.json`，"
                + "全部语料与切片写入 H2 内存库、向量写入 `target/benchmark-embedding-store.json`，"
                + "不会接触开发库 `kb_rag`。重新跑批会覆盖本报告与三份 CSV，表格与正文数值自动同步。\n");
        return md.toString();
    }

    /** 表 1：策略 × 类别（总体） */
    private String tableOf(List<MetricRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 检索策略 | 问题类别 | 样本数 | Hit@1 | Hit@3 | Hit@5 | MRR | 平均耗时(ms) | P95耗时(ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (MetricRow r : rows) {
            sb.append("| ").append(r.strategyCn()).append(" | ").append(cnCategory(r.category()))
                    .append(" | ").append(r.queries())
                    .append(" | ").append(pct(r.hitAt1())).append(" | ").append(pct(r.hitAt3()))
                    .append(" | ").append(pct(r.hitAt5())).append(" | ").append(num(r.mrr()))
                    .append(" | ").append(num(r.avgLatencyMillis())).append(" | ")
                    .append(num(r.p95LatencyMillis())).append(" |\n");
        }
        return sb.toString();
    }

    private String chunkConfigTable(List<ChunkConfigRow> configs, int totalQueries) {
        StringBuilder sb = new StringBuilder();
        sb.append("| chunk-max-chars | 重叠字符 | 切片总数 | 平均切片字数 | 证据可判定题数 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (ChunkConfigRow c : configs) {
            sb.append("| ").append(c.chunkMaxChars()).append(" | ").append(c.chunkOverlapChars())
                    .append(" | ").append(c.chunkCount()).append(" | ")
                    .append(num(c.avgChunkChars())).append(" | ")
                    .append(c.answerableQueries()).append(" / ").append(totalQueries).append(" |\n");
        }
        return sb.toString();
    }

    private String sensitivityTable(List<MetricRow> rows, List<ChunkConfigRow> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 切片粒度 | 检索策略 | 切片数 | Hit@1 | Hit@3 | Hit@5 | MRR | 平均耗时(ms) | Top5 平均字数 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Map<Integer, ChunkConfigRow> bySize = new java.util.LinkedHashMap<>();
        configs.forEach(c -> bySize.put(c.chunkMaxChars(), c));
        for (MetricRow r : rows) {
            ChunkConfigRow c = bySize.get(parseSize(r.config()));
            sb.append("| ").append(r.config()).append(" | ").append(r.strategyCn())
                    .append(" | ").append(c == null ? "" : c.chunkCount())
                    .append(" | ").append(pct(r.hitAt1())).append(" | ").append(pct(r.hitAt3()))
                    .append(" | ").append(pct(r.hitAt5())).append(" | ").append(num(r.mrr()))
                    .append(" | ").append(num(r.avgLatencyMillis())).append(" | ")
                    .append(num(r.avgTop5Chars())).append(" |\n");
        }
        return sb.toString();
    }

    private String querySampleTable(List<QueryRow> rows, int evalWindow) {
        List<QueryRow> ablationHybrid = rows.stream()
                .filter(r -> "experiment1_ablation".equals(r.experiment()) && "HYBRID".equals(r.strategy()))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("| 题号 | 类别 | 问题 | 目标切片 | 证据 | 混合检索命中名次 | 字面重合度 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (QueryRow r : ablationHybrid) {
            sb.append("| ").append(r.queryId()).append(" | ").append(r.category())
                    .append(" | ").append(escapeCell(r.question())).append(" | ")
                    .append(r.targetSection()).append(" | ").append(escapeCell(r.evidence()))
                    .append(" | ").append(r.evidenceRank() == null
                            ? "未命中(Top-" + evalWindow + ")" : "第 " + r.evidenceRank() + " 名")
                    .append(" | ").append(pct(r.overlap())).append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 自动分析文字

    private String overlapSummary(List<MetricRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (String category : List.of("A", "B", "C")) {
            rows.stream().filter(r -> category.equals(r.category()) && "HYBRID".equals(r.strategy()))
                    .findFirst()
                    .ifPresent(r -> sb.append(sb.length() == 0 ? "" : "、")
                            .append(category).append(" 类 ").append(pct(r.avgOverlap())));
        }
        return sb.toString();
    }

    /** 用实测重合度说明 B 类的构造是否真的「无字面可依」，并把词法仍能命中的原因提前交代清楚 */
    private String overlapCompare(List<MetricRow> rows, int sections) {
        double a = overlapOf(rows, "A");
        double b = overlapOf(rows, "B");
        if (a <= 0) {
            return "。B 类问题的字面重合度为 " + pct(b) + "。\n";
        }
        return "。B 类的重合度只有 A 类的 " + num(b / a) + " 倍，说明这类提问在构造上确实不靠关键词匹配；"
                + "不过重合度低不等于词法路必然召不回——本次语料 " + sections
                + " 段，问题里残留的少量通用 2-gram "
                + "仍有机会把目标段顶进候选池，两路的真实关系见 3.3 的逐题互补性分析。\n";
    }

    private double overlapOf(List<MetricRow> rows, String category) {
        return rows.stream()
                .filter(r -> category.equals(r.category()) && "HYBRID".equals(r.strategy()))
                .mapToDouble(MetricRow::avgOverlap)
                .findFirst().orElse(0);
    }

    /**
     * 实验一的自动分析。
     *
     * <p>所有判断句都由实测数值推导（比较方向、差值都是算出来的），不会出现「表格 0.75、正文写 0.8」
     * 或正文断言与实际排序相反的问题——重跑换数据后结论会自动改口。
     */
    private String ablationAnalysis(List<MetricRow> rows, List<QueryRow> queryRows, int sections, int evalWindow) {
        StringBuilder sb = new StringBuilder();
        MetricRow lexAll = pick(rows, "LEXICAL", "ALL");
        MetricRow vecAll = pick(rows, "SEMANTIC", "ALL");
        MetricRow hybAll = pick(rows, "HYBRID", "ALL");
        MetricRow lexA = pick(rows, "LEXICAL", "A");
        MetricRow vecA = pick(rows, "SEMANTIC", "A");
        MetricRow hybA = pick(rows, "HYBRID", "A");
        MetricRow lexB = pick(rows, "LEXICAL", "B");
        MetricRow vecB = pick(rows, "SEMANTIC", "B");
        MetricRow hybB = pick(rows, "HYBRID", "B");
        MetricRow lexC = pick(rows, "LEXICAL", "C");
        MetricRow vecC = pick(rows, "SEMANTIC", "C");
        MetricRow hybC = pick(rows, "HYBRID", "C");
        sb.append("**现象与分析**\n\n");

        sb.append("- 总体（").append(hybAll.queries()).append(" 题，Top-").append(evalWindow)
                .append(" 窗口）：混合检索 Hit@1 ")
                .append(pct(hybAll.hitAt1())).append("、Hit@3 ").append(pct(hybAll.hitAt3()))
                .append("、Hit@5 ").append(pct(hybAll.hitAt5())).append("、MRR ").append(num(hybAll.mrr()))
                .append("。Hit@1 相对词法路").append(versus(hybAll.hitAt1(), lexAll.hitAt1()))
                .append("、相对语义路").append(versus(hybAll.hitAt1(), vecAll.hitAt1()))
                .append("；MRR 由词法 ").append(num(lexAll.mrr())).append(" / 语义 ").append(num(vecAll.mrr()))
                .append(" 变为 ").append(num(hybAll.mrr()))
                .append("，Hit@5 由 ").append(pct(lexAll.hitAt5())).append(" / ").append(pct(vecAll.hitAt5()))
                .append(" 变为 ").append(pct(hybAll.hitAt5())).append("。").append(bestOverall(lexAll, vecAll, hybAll));

        sb.append("- A 类（字面精确，平均字面重合度 ").append(pct(lexA.avgOverlap())).append("）：词法 Hit@1 ")
                .append(pct(lexA.hitAt1())).append("、语义 ").append(pct(vecA.hitAt1())).append("、混合 ")
                .append(pct(hybA.hitAt1())).append("，MRR ")
                .append(num(lexA.mrr())).append(" / ").append(num(vecA.mrr())).append(" / ")
                .append(num(hybA.mrr())).append("。").append(aClassReading(lexA, vecA, sections));

        sb.append("- B 类（同义改写，平均字面重合度仅 ").append(pct(lexB.avgOverlap())).append("）：词法 Hit@1 ")
                .append(pct(lexB.hitAt1())).append("、Hit@5 ").append(pct(lexB.hitAt5()))
                .append("，语义 ").append(pct(vecB.hitAt1())).append(" / ").append(pct(vecB.hitAt5()))
                .append("，混合 ").append(pct(hybB.hitAt1())).append(" / ").append(pct(hybB.hitAt5()))
                .append("，MRR ").append(num(lexB.mrr())).append(" / ").append(num(vecB.mrr()))
                .append(" / ").append(num(hybB.mrr())).append("。").append(bClassReading(lexB, vecB, hybB));

        sb.append("- C 类（术语与泛化并存）：三路 Hit@5 依次为词法 ").append(pct(lexC.hitAt5()))
                .append("、语义 ").append(pct(vecC.hitAt5())).append("、混合 ").append(pct(hybC.hitAt5()))
                .append("，MRR ").append(num(lexC.mrr())).append(" / ").append(num(vecC.mrr()))
                .append(" / ").append(num(hybC.mrr())).append("。").append(cClassReading(lexC, vecC, hybC));

        sb.append("- 耗时：词法路平均 ").append(num(lexAll.avgLatencyMillis())).append(" ms（P95 ")
                .append(num(lexAll.p95LatencyMillis()))
                .append(" ms，只做数据库 LIKE 扫描，不调用任何外部服务），语义路平均 ")
                .append(num(vecAll.avgLatencyMillis())).append(" ms（P95 ").append(num(vecAll.p95LatencyMillis()))
                .append(" ms）、混合路 ").append(num(hybAll.avgLatencyMillis())).append(" ms（P95 ")
                .append(num(hybAll.p95LatencyMillis())).append(" ms）。")
                .append("混合路并不比语义路多一次远程调用：两路各自只对问题做一次向量化，")
                .append("增量 ").append(num(hybAll.avgLatencyMillis() - vecAll.avgLatencyMillis()))
                .append(" ms 来自两组候选的回表与 RRF 排序，相对生成阶段动辄数秒的端到端耗时可以忽略；")
                .append("词法路虽快，但它单独承担召回时的质量损失（见上）远不是这点耗时能补偿的。\n\n");
        return sb.toString();
    }

    /** A 类解读：语义路是否在术语题上失手，决定这段结论往哪个方向写 */
    private String aClassReading(MetricRow lexA, MetricRow vecA, int sections) {
        if (vecA.hitAt1() >= lexA.hitAt1()) {
            return "词法路预期的优势在本组没有拉开，原因在实验尺度：语料只有 " + sections
                    + " 段，"
                    + "每个专有名词基本只在自己的那一段里出现，问题向量与目标段的余弦相似度足以压过其余段落，"
                    + "向量路等价地完成了「精确匹配」。这说明词法召回的价值不在于小语料下能不能命中术语，"
                    + "而在于语料规模上去、同一术语散布到多段之后仍能提供可控的硬特征区分——"
                    + "这一优势无法在 " + sections + " 段的教学级评测集上被放大，属于本实验的尺度局限（见第 6 节）。\n";
        }
        return "词法路在参数名、英文缩写与数字编号这类低频硬特征上明显占优：这类词一旦在问题里出现，"
                + "字面命中就是强证据，而向量相似度会被整段的主题词平均掉，名次反而靠后。\n";
    }

    /**
     * 总体口径下「哪一路最优」的如实描述。
     *
     * <p>混合检索并不天然占优：语料与题量扩大后，RRF 可能把一路的强命中被另一路的一堆弱命中拉平。
     * 这段文字按实测数值决定往哪个方向写，避免正文出现「混合全面最优」这类与表格相反的断言。
     */
    private String bestOverall(MetricRow lexAll, MetricRow vecAll, MetricRow hybAll) {
        MetricRow best = vecAll.mrr() > lexAll.mrr() ? vecAll : lexAll;
        if (hybAll.mrr() >= best.mrr() && hybAll.hitAt1() >= best.hitAt1()) {
            return "三路里混合检索在 Hit@1 与 MRR 上都不低于任一单路，本次融合的收益是全局性的。\n";
        }
        return "三路里总体的 Hit@1 与 MRR 都由" + best.strategyCn() + "最高（"
                + pct(best.hitAt1()) + " / " + num(best.mrr()) + "），混合检索没有超过它，"
                + "因此本实验不能把混合策略表述为「全面最优」，只能按类别论证它的补位价值（见下一条与 3.3）。\n";
    }

    /** B 类解读：混合路是否压过两路，决定这段结论往哪个方向写 */
    private String bClassReading(MetricRow lexB, MetricRow vecB, MetricRow hybB) {
        double bestSingleHit5 = Math.max(lexB.hitAt5(), vecB.hitAt5());
        double bestSingleMrr = Math.max(lexB.mrr(), vecB.mrr());
        if (hybB.hitAt5() > bestSingleHit5 || hybB.mrr() > bestSingleMrr) {
            return "混合路在这一类上同时压过两路（Hit@5 " + pct(bestSingleHit5) + " → " + pct(hybB.hitAt5())
                    + "，MRR " + num(bestSingleMrr) + " → " + num(hybB.mrr()) + "）："
                    + "同义改写削掉了字面特征，但两路的排序信息并不相同——RRF 把「词法勉强命中」与"
                    + "「语义高度相似」这两个弱信号叠加成一个强信号，正确切片因此被顶进名额。\n";
        }
        return "本组里混合路未能同时压过两路，说明在这套语料上同义改写题已被其中一路稳定覆盖，"
                + "融合的边际收益转移到别的类别与名次上（逐题证据见 3.3）。\n";
    }

    /** C 类解读：RRF 在双路都能命中时是否仍有提前名次的效果 */
    private String cClassReading(MetricRow lexC, MetricRow vecC, MetricRow hybC) {
        double bestSingleMrr = Math.max(lexC.mrr(), vecC.mrr());
        if (hybC.mrr() >= bestSingleMrr) {
            return "两路差距在这类上最小，而混合路的 MRR 仍不低于任一单路，"
                    + "说明 RRF 的作用主要不是「多召回」而是「把正确切片的名次提前」，"
                    + "让最相关的片段能在 Top-5 名额内进入 Prompt。\n";
        }
        return "这类问题上混合路的 MRR（" + num(hybC.mrr()) + "）没有超过最优单路（" + num(bestSingleMrr)
                + "），说明当两路都能稳定命中时融合不再带来名次收益；"
                + "RRF 的真实价值集中在「某一路彻底失效」的题上，见 3.3 的逐题统计。\n";
    }

    /**
     * 3.3 逐题互补性：直接统计「只有词法命中 / 只有语义命中 / 两路都漏 / 混合救回」四类样本。
     *
     * <p>消融实验最有说服力的不是聚合均值，而是能点名说出「哪些题只有某一路能做对」，
     * 从而证明混合不是取并集，而是补位 + 交叉验证。
     */
    private String complementarity(List<QueryRow> queryRows) {
        int topK = settings.productionTopK();
        Map<String, Integer> lex = new java.util.LinkedHashMap<>();
        Map<String, Integer> sem = new java.util.LinkedHashMap<>();
        Map<String, Integer> hyb = new java.util.LinkedHashMap<>();
        for (QueryRow r : queryRows) {
            if (!EXPERIMENT_1.equals(r.experiment())) {
                continue;
            }
            switch (r.strategy()) {
                case "LEXICAL" -> lex.put(r.queryId(), r.evidenceRank());
                case "SEMANTIC" -> sem.put(r.queryId(), r.evidenceRank());
                case "HYBRID" -> hyb.put(r.queryId(), r.evidenceRank());
                default -> {
                }
            }
        }
        List<String> lexOnly = new ArrayList<>();
        List<String> semOnly = new ArrayList<>();
        List<String> bothMiss = new ArrayList<>();
        List<String> rescued = new ArrayList<>();
        List<String> promoted = new ArrayList<>();
        List<String> demoted = new ArrayList<>();
        for (String id : lex.keySet()) {
            Integer l = lex.get(id);
            Integer s = sem.get(id);
            Integer h = hyb.get(id);
            boolean inL = within(l, topK);
            boolean inS = within(s, topK);
            if (inL && inS) {
                continue;
            }
            if (inL) {
                lexOnly.add(id);
            } else if (inS) {
                semOnly.add(id);
            } else {
                bothMiss.add(id);
                if (within(h, topK)) {
                    rescued.add(id);
                }
            }
            Integer bestSingle = betterRank(l, s);
            if (h != null && bestSingle != null) {
                if (h < bestSingle) {
                    promoted.add(id);
                } else if (h > bestSingle) {
                    demoted.add(id);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以 Top-").append(topK).append(" 为口径对 ").append(lex.size()).append(" 题逐一比对：\n\n");
        sb.append("- 两路都在名额内命中的题目之外，**只有词法路**做到的有 ").append(lexOnly.size()).append(" 题")
                .append(listOf(lexOnly)).append("；**只有语义路**做到的有 ").append(semOnly.size()).append(" 题")
                .append(listOf(semOnly)).append("。两者互不覆盖，正是「互补而非包含」的直接证据。\n");
        sb.append("- 两路都掉出名额的有 ").append(bothMiss.size()).append(" 题").append(listOf(bothMiss))
                .append("，其中混合检索救回 ").append(rescued.size()).append(" 题").append(listOf(rescued));
        if (rescued.isEmpty()) {
            sb.append("。本批 ").append(lex.size()).append(" 题没有出现「两路同时失效」的样本，"
                    + "因此 RRF 把两路弱信号叠加、重新抬进候选名额的"
                    + "救回能力没有被触发，它的价值只能从上面两路互不覆盖的补位题上间接体现；"
                    + "语料规模扩大、单路召回难度上升后才会出现可统计的救回案例。");
        } else {
            sb.append("——RRF 让两路的弱信号叠加后重新进入候选，这是任何单一路都做不到的。");
        }
        sb.append('\n');
        sb.append("- 在两路表现不一致的题目上，混合检索相对最优单路**提前**的有 ").append(promoted.size()).append(" 题")
                .append(listOf(promoted)).append("，**退后**的有 ").append(demoted.size()).append(" 题")
                .append(listOf(demoted)).append("。可见融合不是无损的：当某一路给出高分命中、另一路用一堆弱命中把名次拉平时，")
                .append("正确切片可能被挤出首位，这也是混合检索 Hit@1 未必高于单路的原因。\n\n");
        return sb.toString();
    }

    private static boolean within(Integer rank, int k) {
        return rank != null && rank <= k;
    }

    private static Integer betterRank(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String listOf(List<String> ids) {
        return ids.isEmpty() ? "（无）" : "：" + String.join("、", ids);
    }

    /** 比较短语由差值符号决定，保证正文断言与表格永远一致 */
    private static String versus(double main, double ref) {
        double diff = (main - ref) * 100;
        if (Math.abs(diff) < 0.005) {
            return "持平（同为 " + pct(ref) + "）";
        }
        return (diff > 0 ? "高出 " : "低 ") + String.format(Locale.ROOT, "%.2f", Math.abs(diff)) + " 个百分点";
    }

    private String sensitivityAnalysis(List<MetricRow> rows, List<ChunkConfigRow> configs, int evalWindow) {
        StringBuilder sb = new StringBuilder();
        sb.append("**现象与分析**\n\n");
        ChunkConfigRow smallest = configs.get(0);
        ChunkConfigRow largest = configs.get(configs.size() - 1);
        sb.append("- 粒度从 ").append(smallest.chunkMaxChars()).append(" 字放宽到 ")
                .append(largest.chunkMaxChars()).append(" 字时，切片总数由 ").append(smallest.chunkCount())
                .append(" 降到 ").append(largest.chunkCount())
                .append("，召回候选池随之缩小。粒度越粗，评测窗口在语料中所占的比例越高——最粗的 ")
                .append(largest.chunkMaxChars()).append(" 字档只剩 ").append(largest.chunkCount())
                .append(" 段候选，").append(ceilingOf(largest.chunkCount(), evalWindow))
                .append("因此本实验以 Hit@1 与 MRR 判断优劣，并用 Top-5 平均字数反向衡量 Prompt 成本。\n");
        for (ChunkConfigRow c : configs) {
            MetricRow hybrid = rows.stream()
                    .filter(r -> r.category().equals("ALL") && r.strategy().equals("HYBRID")
                            && Integer.parseInt(r.config()) == c.chunkMaxChars())
                    .findFirst().orElse(null);
            if (hybrid == null) {
                continue;
            }
            sb.append("  - ").append(c.chunkMaxChars()).append(" 字：").append(c.chunkCount())
                    .append(" 段，混合检索 Hit@1 ").append(pct(hybrid.hitAt1()))
                    .append("、MRR ").append(num(hybrid.mrr()))
                    .append("、平均耗时 ").append(num(hybrid.avgLatencyMillis()))
                    .append(" ms、Top-5 平均字数 ").append(num(hybrid.avgTop5Chars())).append("。\n");
        }
        double firstLatency = configs.getFirst().hybridAvgLatencyMillis();
        double lastLatency = configs.getLast().hybridAvgLatencyMillis();
        ChunkConfigRow bestMrr = bestByMrr(configs);
        MetricRow semSmallest = metric(rows, smallest.chunkMaxChars(), "SEMANTIC");
        MetricRow semBest = metric(rows, bestMrr.chunkMaxChars(), "SEMANTIC");
        if (semSmallest != null && semBest != null) {
            sb.append("- 纯语义路最能说明粒度的代价：").append(smallest.chunkMaxChars())
                    .append(" 字档把语料切成 ").append(smallest.chunkCount())
                    .append(" 段，一条完整知识被切分边界拆散后，问题向量只能与局部内容相似，Hit@1 只有 ")
                    .append(pct(semSmallest.hitAt1())).append("、MRR ").append(num(semSmallest.mrr()))
                    .append("；换到 ").append(bestMrr.chunkMaxChars()).append(" 字档则升到 ")
                    .append(pct(semBest.hitAt1())).append(" / ").append(num(semBest.mrr()))
                    .append("。反之在 ").append(largest.chunkMaxChars()).append(" 字档，候选只剩 ")
                    .append(largest.chunkCount()).append(" 段，评测窗口 Top-").append(evalWindow)
                    .append(" 已覆盖 ").append(pct(windowShare(largest.chunkCount(), evalWindow))).append(" 的语料，")
                    .append("Hit@5 ").append(pct(largest.hybridHitAt5()))
                    .append(" 这类数字的区分度随之下降，只能看 Hit@1 与 MRR。\n");
        }
        sb.append("- 大切片把整节内容塞进一个向量，主题被平均化、区分度下降，同时 Top-5 平均字数从 ")
                .append(num(configs.getFirst().hybridAvgTop5Chars())).append(" 涨到 ")
                .append(num(configs.getLast().hybridAvgTop5Chars()))
                .append("（").append(num(ratio(configs.getLast().hybridAvgTop5Chars(),
                        configs.getFirst().hybridAvgTop5Chars())))
                .append(" 倍），直接进入 Prompt 的无关文本成倍增加，token 成本与「注意力稀释」风险同步上升。\n");
        sb.append("- 平均耗时对粒度的敏感度不高（").append(num(firstLatency)).append(" ms → ")
                .append(num(lastLatency)).append(" ms），差值主要来自候选集大小与回表条数，而非切分本身；")
                .append("这说明工程上不必为性能选粒度，应当为召回质量选粒度。\n")
                .append("- 四档里混合检索 MRR 最高的是 ").append(bestMrr.chunkMaxChars())
                .append(" 字 / ").append(bestMrr.chunkOverlapChars()).append(" 字重叠一档（MRR ")
                .append(num(bestMrr.hybridMrr())).append("、Hit@1 ").append(pct(bestMrr.hybridHitAt1()))
                .append("），该档位与生产默认的 chunk-max-chars=")
                .append(settings.productionChunkMaxChars()).append(" / overlap=")
                .append(settings.productionChunkOverlapChars())
                .append(bestMrr.chunkMaxChars() == settings.productionChunkMaxChars()
                        ? " 相同，但要注意这个「相同」只在 MRR 口径下成立——"
                        : " 不同，选型时应在论文里说明取舍。")
                .append("Prompt 成本随粒度单调上升，命中率与 token 开销之间必须显式权衡（结论第 3 条给出对照）。\n\n");
        return sb.toString();
    }

    /** 按「粒度 + 策略」取实验二的指标行，取不到返回 null 由调用方跳过该论断 */
    private MetricRow metric(List<MetricRow> rows, int chunkMaxChars, String strategy) {
        return rows.stream()
                .filter(r -> "ALL".equals(r.category()) && strategy.equals(r.strategy())
                        && parseSize(r.config()) == chunkMaxChars)
                .findFirst().orElse(null);
    }

    private static double ratio(double value, double base) {
        return base <= 0 ? 0 : value / base;
    }

    private String conclusions(List<MetricRow> ablation, List<MetricRow> sensitivity,
                               List<ChunkConfigRow> configs, int rrfK) {
        MetricRow hybAll = pick(ablation, "HYBRID", "ALL");
        MetricRow lexAll = pick(ablation, "LEXICAL", "ALL");
        MetricRow vecAll = pick(ablation, "SEMANTIC", "ALL");
        ChunkConfigRow bestMrr = bestByMrr(configs);
        return """
                1. 三条路的失分位置并不相同：词法路 Hit@5 最低的类别是 %s（%s），语义路最低的是 %s（%s），
                   混合路三个类别里最差的 Hit@5 也有 %s（%s）。也就是说没有任何单路能同时兼顾字面硬特征与语义泛化，%s
                2. RRF 只用名次不用原始分数，规避了「词法命中词数」与「余弦相似度」量纲不可相加的问题，
                   代价只有一次候选合并与重排；k=%s 使两路名次差异被平滑，%s
                   逐题层面的补位与救回统计见 3.3。
                3. 切片粒度不存在对所有指标同时最优的取值：小片段提高语义纯度但割裂完整知识，
                   大片段扩大候选池噪声并成倍增加进入 Prompt 的字符数。四档里混合检索 MRR 最高的是
                   %s 字 / %s 字重叠（Hit@1 %s、MRR %s、Top-%s 平均字数 %s），
                   而生产默认配置是 chunk-max-chars=%s / overlap=%s，%s
                4. 检索耗时远低于大模型生成耗时（流式问答端到端通常在秒级），因此召回阶段的策略取舍
                   应以质量为主、性能为辅。本实验的词法路是「H2 内存库 + 逐词 LIKE 扫描」口径（平均 %s ms），
                   该开销会随语料行数线性上升，因此生产已把词法路切到 ngram 全文索引
                   （`rag.lexical-mode=fulltext` + `MATCH ... AGAINST ... IN BOOLEAN MODE`），
                   十万级切片下的 LIKE / 全文索引延迟对照见 `docs/bench/lexical_ab_results.csv`。
                """.formatted(
                cnCategory(worstCategory(ablation, "LEXICAL")), pct(worstHit5(ablation, "LEXICAL")),
                cnCategory(worstCategory(ablation, "SEMANTIC")), pct(worstHit5(ablation, "SEMANTIC")),
                pct(worstHit5(ablation, "HYBRID")), cnCategory(worstCategory(ablation, "HYBRID")),
                hybridPosition(lexAll, vecAll, hybAll, ablation),
                rrfK, rrfTradeoff(lexAll, vecAll, hybAll),
                bestMrr.chunkMaxChars(), bestMrr.chunkOverlapChars(), pct(bestMrr.hybridHitAt1()),
                num(bestMrr.hybridMrr()), settings.productionTopK(),
                num(bestMrr.hybridAvgTop5Chars()),
                settings.productionChunkMaxChars(), settings.productionChunkOverlapChars(),
                defaultMatches(bestMrr),
                num(lexAll.avgLatencyMillis()));
    }

    /** 结论第 1 条收尾：混合检索处在什么位置完全由实测数值决定，不占优时如实点名最优单路 */
    private String hybridPosition(MetricRow lexAll, MetricRow vecAll, MetricRow hybAll, List<MetricRow> rows) {
        MetricRow best = vecAll.mrr() >= lexAll.mrr() ? vecAll : lexAll;
        if (hybAll.mrr() >= best.mrr() && hybAll.hitAt1() >= best.hitAt1()) {
            return "而混合检索在总体 Hit@1 " + pct(hybAll.hitAt1()) + " 的同时把 Hit@5 抬到 "
                    + pct(hybAll.hitAt5()) + "、MRR 抬到 " + num(hybAll.mrr())
                    + "——「正确切片排得更靠前、且不依赖某一路单独做对」，"
                    + "这正是 Top-" + settings.productionTopK() + " 名额进入 Prompt 时最关心的性质。";
        }
        return "但本批数据里总体最优的是" + best.strategyCn() + "（Hit@1 " + pct(best.hitAt1())
                + "、MRR " + num(best.mrr()) + "），混合检索为 " + pct(hybAll.hitAt1()) + " / "
                + num(hybAll.mrr()) + "，因此论文里不能把混合策略表述为「全面最优」；"
                + "它的价值要按类别论证——混合同时在 Hit@5 与 MRR 上超过两路的类别是："
                + hybridBestCategories(rows) + "。";
    }

    /** 逐类别找出「混合同时优于两路」的样本组，一个都没有时也如实说明 */
    private String hybridBestCategories(List<MetricRow> rows) {
        List<String> wins = new ArrayList<>();
        for (String category : List.of("A", "B", "C")) {
            MetricRow lex = pick(rows, "LEXICAL", category);
            MetricRow vec = pick(rows, "SEMANTIC", category);
            MetricRow hyb = pick(rows, "HYBRID", category);
            if (hyb.hitAt5() > Math.max(lex.hitAt5(), vec.hitAt5())
                    && hyb.mrr() > Math.max(lex.mrr(), vec.mrr())) {
                wins.add(cnCategory(category) + "（Hit@5 " + pct(lex.hitAt5()) + " / " + pct(vec.hitAt5())
                        + " → " + pct(hyb.hitAt5()) + "，MRR " + num(lex.mrr()) + " / " + num(vec.mrr())
                        + " → " + num(hyb.mrr()) + "）");
            }
        }
        return wins.isEmpty() ? "没有哪一类问题上的混合检索能同时在 Hit@5 与 MRR 上超过两路" : String.join("、", wins);
    }

    /** 结论第 2 条：RRF 平滑名次是双向的，融合带来损失时也要写进结论 */
    private String rrfTradeoff(MetricRow lexAll, MetricRow vecAll, MetricRow hybAll) {
        MetricRow best = vecAll.hitAt1() >= lexAll.hitAt1() ? vecAll : lexAll;
        if (hybAll.hitAt1() >= best.hitAt1()) {
            return "任何单一路的偶然第一名都无法单独决定最终排序，而总体 Hit@1 也没有因为融合被拉低。";
        }
        return "但平滑是双向的：一路把正确切片顶到前列、另一路只贡献一堆弱命中时，名次同样会被拉平——"
                + "本批混合检索 Hit@1 " + pct(hybAll.hitAt1()) + " 低于最优单路 " + pct(best.hitAt1())
                + "（" + String.format(Locale.ROOT, "%.2f", (best.hitAt1() - hybAll.hitAt1()) * 100)
                + " 个百分点），说明融合不是无损的，这个代价应当与收益一起写进论文。";
    }

    /** 默认档位是否就是实测最优档位——两种情况都要能如实说出口 */
    private String defaultMatches(ChunkConfigRow bestMrr) {
        if (bestMrr.chunkMaxChars() == settings.productionChunkMaxChars()) {
            return "二者一致，说明现网参数在召回质量与 Prompt 成本之间确实取到了折中。";
        }
        return "二者不同，若以 MRR 为选型依据，应把默认参数调整到 " + bestMrr.chunkMaxChars()
                + " 字一档，或在论文中如实说明当前默认值的选择理由。";
    }

    private ChunkConfigRow bestByMrr(List<ChunkConfigRow> configs) {
        return configs.stream()
                .max(java.util.Comparator.comparingDouble(ChunkConfigRow::hybridMrr))
                .orElse(configs.get(configs.size() - 1));
    }

    /** 某条策略在 A/B/C 三类中表现最差的类别（按 Hit@5），用于把「短板」说成可核对的事实 */
    private String worstCategory(List<MetricRow> rows, String strategy) {
        return rows.stream()
                .filter(r -> !"ALL".equals(r.category()) && strategy.equals(r.strategy()))
                .min(java.util.Comparator.comparingDouble(MetricRow::hitAt5))
                .map(MetricRow::category)
                .orElse("B");
    }

    private double worstHit5(List<MetricRow> rows, String strategy) {
        return rows.stream()
                .filter(r -> !"ALL".equals(r.category()) && strategy.equals(r.strategy()))
                .mapToDouble(MetricRow::hitAt5)
                .min().orElse(0);
    }

    // ------------------------------------------------------------------ 小工具

    private List<MetricRow> filter(List<MetricRow> rows, String category) {
        return rows.stream().filter(r -> r.category().equals(category)).toList();
    }

    private List<MetricRow> filterCategory(List<MetricRow> rows, String category) {
        return rows.stream().filter(r -> r.category().equals(category)).toList();
    }

    private MetricRow pick(List<MetricRow> rows, String strategy, String category) {
        return rows.stream()
                .filter(r -> r.strategy().equals(strategy) && r.category().equals(category))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少指标行: " + strategy + "/" + category));
    }

    private static int parseSize(String config) {
        return Integer.parseInt(config.replaceAll("\\D+", ""));
    }

    /** 从环境/数据集概览里取整数，取不到用兜底值——报告标题不允许硬编码题量与语料段数 */
    private static int intOf(Map<String, String> map, String key, int fallback) {
        String raw = map.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 表 2 标题里的各类样本数，题量扩容后自动跟随 */
    private String categorySampleSizes(List<MetricRow> rows) {
        List<String> parts = new ArrayList<>();
        for (String category : List.of("A", "B", "C")) {
            rows.stream().filter(r -> category.equals(r.category()) && "HYBRID".equals(r.strategy()))
                    .findFirst()
                    .ifPresent(r -> parts.add(category + " 类 n=" + r.queries()));
        }
        return parts.isEmpty() ? "每类样本数见表内「样本数」列" : String.join("、", parts);
    }

    /** 第 6 节的天花板效应说明：按评测窗口占候选池的真实比例措辞 */
    private String ceilingNote(int sections, int evalWindow) {
        double share = windowShare(sections, evalWindow);
        return "- 语料规模（" + sections + " 段）仍远小于生产库，评测窗口 Top-" + evalWindow
                + " 约占候选池的 " + pct(share)
                + (share >= 0.5 ? "，Hit@5 存在明显的天花板效应" : "，Hit@5 的天花板效应已随候选池变大而减弱")
                + "，因此跨策略比较应以 Hit@1 与 MRR 为准；"
                + "候选池偏小还会让词法路残留的通用 2-gram 噪声更容易凑出命中，"
                + "语料规模继续扩大后词法与语义的差距才会被充分放大。\n";
    }

    /** 实验二里最粗粒度的候选池已被评测窗口覆盖到什么程度 */
    private String ceilingOf(int chunkCount, int evalWindow) {
        double share = windowShare(chunkCount, evalWindow);
        return share >= 1
                ? "Top-" + evalWindow + " 已不小于切片总数，Hit@5 只剩下「天花板效应」；"
                : "Top-" + evalWindow + " 覆盖 " + pct(share) + " 的候选，Hit@5 的区分度明显下降；";
    }

    private static double windowShare(int candidates, int evalWindow) {
        return candidates <= 0 ? 0 : (double) evalWindow / candidates;
    }

    private static String cnCategory(String category) {
        return switch (category) {
            case "A" -> "A 字面精确";
            case "B" -> "B 同义改写";
            case "C" -> "C 综合复杂";
            default -> "总体";
        };
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("\\.?0+$", "");
    }

    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "｜").replace("\n", " ");
    }

    private static String quote(String csv) {
        if (csv == null) {
            return "\"\"";
        }
        boolean needQuote = csv.contains(",") || csv.contains("\"") || csv.contains("\n");
        String value = csv.replace("\"", "\"\"");
        return needQuote ? '"' + value + '"' : value;
    }
}
