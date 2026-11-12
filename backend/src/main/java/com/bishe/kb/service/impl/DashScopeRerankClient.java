package com.bishe.kb.service.impl;

import com.bishe.kb.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼（DashScope）gte-rerank 系列文本排序客户端：RAG 流水线里「RRF 粗排 → Cross-Encoder 精排」的最后一环。
 *
 * <p>调用的是百炼<b>原生</b> text-rerank 端点（{@code rag.rerank.endpoint}），
 * 不是 {@code rag.base-url} 的 OpenAI 兼容协议地址——gte-rerank 系列只在原生协议下提供。
 * 默认模型 {@code gte-rerank-v2}（v1 的 gte-rerank 实测 403 AccessDenied，已不对该账号开放）。
 * 报文结构（官方文档核对过）：
 * <pre>{@code
 * 请求  POST {endpoint}   Authorization: Bearer {rag.api-key}
 * {"model":"gte-rerank-v2",
 *  "input":{"query":"...","documents":["候选1","候选2",...]},
 *  "parameters":{"top_n":5,"return_documents":false}}
 *
 * 响应 {"output":{"results":[{"index":0,"relevance_score":0.93},...]},
 *       "usage":{"total_tokens":79},"request_id":"..."}
 * 错误 {"code":"InvalidApiKey","message":"...","request_id":"..."}
 * }</pre>
 *
 * <p>{@code results} 按 relevance_score 降序返回，{@code index} 指回入参 documents 的原始下标；
 * {@code return_documents=false} 省掉原文回传（正文本来就在调用方手里）。
 *
 * <p><b>失败语义</b>：本客户端不做静默降级——网络超时（连接/读取各 {@code rag.rerank.timeout-seconds} 秒，
 * 默认 3s）、非 2xx、错误码、报文缺字段一律抛 {@link IllegalStateException}，
 * 由调用方（{@code RerankRetrievalService}）统一 catch 后回退 RRF 粗排 Top-K。
 * 降级决策放在调用方而不是这里，是为了让「精排失败」与「精排成功但结果为空」走同一条兜底路径。
 *
 * <p>零新增依赖：HTTP 走 spring-web 自带的 {@link RestClient} + JDK HttpClient，
 * JSON 复用容器里的 {@link ObjectMapper}。
 */
@Slf4j
@Component
public class DashScopeRerankClient {

    /**
     * HTTP 传输接缝：生产实现走 RestClient；单元测试注入假传输（固定报文 / 模拟超时异常），
     * 完全断网也能覆盖解析与降级分支。
     */
    @FunctionalInterface
    public interface Transport {
        /** 发送请求体 JSON，返回响应体 JSON；任何失败直接抛异常，由外层包装 */
        String post(String requestBody) throws Exception;
    }

    /** 一条精排结果：index 指回入参 documents 的下标，score 为语义相关性（0~1，越大越相关） */
    public record ScoredIndex(int index, double score) {
    }

    private final RagProperties props;
    private final ObjectMapper objectMapper;
    private final Transport transport;

    /** RestClient 缓存锁：超时配置被运行时修改后重建一次，平时直接复用 */
    private final Object httpLock = new Object();
    private RestClient cachedHttp;
    private int cachedTimeoutSeconds = -1;

    @Autowired
    public DashScopeRerankClient(RagProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, null);
    }

    /** 测试接缝：{@code transport} 非空时用假传输，为空时走真实 RestClient（构造不发起任何网络请求） */
    public DashScopeRerankClient(RagProperties props, ObjectMapper objectMapper, Transport transport) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.transport = transport != null ? transport : this::httpPost;
    }

    /**
     * 精排是否可用：开关打开、非演示模式、且配了 API Key。
     *
     * <p>任一条件不满足时调用方应直接透传 RRF 粗排结果，不发起网络请求——
     * 这也是无网 Mock 测试与无密钥开发环境不受影响的原因。
     */
    public boolean isAvailable() {
        RagProperties.Rerank cfg = props.getRerank();
        return cfg.isEnabled() && !props.isMockEnabled() && StringUtils.isNotBlank(props.getApiKey());
    }

    /**
     * 对候选文档做 Cross-Encoder 精排。
     *
     * @param query     用户问题
     * @param documents RRF 粗排后的候选正文（顺序即粗排名次）
     * @param topN      最多返回几条；≤0 表示全部返回，超过候选数时按候选数截断
     * @return 按相关性降序的 (原始下标, 得分) 列表；入参为空时返回空列表（不调接口）
     * @throws IllegalStateException 超时、HTTP 错误、业务错误码或报文异常——调用方负责降级
     */
    public List<ScoredIndex> rerank(String query, List<String> documents, int topN) {
        if (StringUtils.isBlank(query) || documents == null || documents.isEmpty()) {
            return List.of();
        }
        RagProperties.Rerank cfg = props.getRerank();
        int effectiveTopN = topN <= 0 ? documents.size() : Math.min(topN, documents.size());
        String request = buildRequest(query, documents, effectiveTopN, cfg.getModel());

        long start = System.currentTimeMillis();
        String response;
        try {
            response = transport.post(request);
        } catch (Exception e) {
            throw new IllegalStateException("rerank 接口调用失败: " + e.getMessage(), e);
        }
        List<ScoredIndex> scored = parseResponse(response, documents.size());
        log.debug("rerank 完成：候选 {} 条，返回 {} 条，耗时 {}ms",
                documents.size(), scored.size(), System.currentTimeMillis() - start);
        return scored;
    }

    // ------------------------------------------------------------------ 报文组装与解析

    private String buildRequest(String query, List<String> documents, int topN, String model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", Map.of("query", query, "documents", documents));
        // return_documents=false：正文已在调用方手里，不需要接口回传，省带宽也省解析
        payload.put("parameters", Map.of("top_n", topN, "return_documents", false));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("rerank 请求体序列化失败: " + e.getMessage(), e);
        }
    }

    private List<ScoredIndex> parseResponse(String responseJson, int docCount) {
        if (StringUtils.isBlank(responseJson)) {
            throw new IllegalStateException("rerank 响应为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (IOException e) {
            throw new IllegalStateException("rerank 响应不是合法 JSON: " + e.getMessage(), e);
        }
        // 百炼业务错误（InvalidApiKey / Throttling 等）HTTP 状态码可能是 4xx，也可能 200 带 code，统一在这里拦
        if (root.hasNonNull("code")) {
            throw new IllegalStateException("rerank 返回错误 code=" + root.path("code").asText()
                    + ", message=" + root.path("message").asText());
        }
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("rerank 响应缺少 output.results");
        }
        List<ScoredIndex> scored = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= docCount) {
                log.warn("rerank 返回越界 index={}（候选 {} 条），忽略该条", index, docCount);
                continue;
            }
            scored.add(new ScoredIndex(index, node.path("relevance_score").asDouble(0d)));
        }
        if (scored.isEmpty()) {
            throw new IllegalStateException("rerank 响应没有有效结果");
        }
        // 官方已按 relevance_score 降序返回；这里再稳定排序兜底，防上游排序约定变化
        scored.sort(Comparator.comparingDouble(ScoredIndex::score).reversed());
        return List.copyOf(scored);
    }

    // ------------------------------------------------------------------ 真实 HTTP 传输

    private String httpPost(String requestBody) {
        RagProperties.Rerank cfg = props.getRerank();
        return httpClient(cfg).post()
                .uri(cfg.getEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    /**
     * 带 3s（可配）连接/读取超时的 RestClient。
     *
     * <p>JDK HttpClient 的连接超时挂在 client 上、读取超时挂在 request factory 上，两处都要设；
     * 只设其一会出现「连接挂 30s」或「响应体读一半永久等待」的半吊子超时。
     * 实例按超时值缓存：管理员在引擎参数页改超时后下一次调用自动重建，平时零开销复用。
     */
    private RestClient httpClient(RagProperties.Rerank cfg) {
        int timeout = Math.max(1, cfg.getTimeoutSeconds());
        synchronized (httpLock) {
            if (cachedHttp == null || cachedTimeoutSeconds != timeout) {
                HttpClient jdk = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeout))
                        .build();
                JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
                factory.setReadTimeout(Duration.ofSeconds(timeout));
                cachedHttp = RestClient.builder().requestFactory(factory).build();
                cachedTimeoutSeconds = timeout;
            }
            return cachedHttp;
        }
    }
}