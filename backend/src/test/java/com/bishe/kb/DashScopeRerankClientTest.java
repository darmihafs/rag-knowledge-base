package com.bishe.kb;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.service.impl.DashScopeRerankClient;
import com.bishe.kb.service.impl.DashScopeRerankClient.ScoredIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gte-rerank 客户端的离线单元测试：通过公开的施工接缝注入假 HTTP 传输，
 * 用官方文档核对过的固定报文覆盖「解析 / 排序 / 错误码 / 超时 / 入参钳制」全部分支，
 * 不发起任何真实网络请求，无密钥环境也能全绿。
 */
class DashScopeRerankClientTest {

    private static final String QUERY = "什么是文本排序模型";
    private static final List<String> DOCS = List.of(
            "文本排序模型广泛用于搜索引擎和推荐系统中",
            "量子计算是计算科学的一个前沿领域",
            "预训练语言模型的发展给文本排序模型带来了新的进展");

    /** 官方成功响应样例（return_documents=false 口径）：results 已按 relevance_score 降序 */
    private static final String SUCCESS_JSON = """
            {
              "output": {
                "results": [
                  {"index": 0, "relevance_score": 0.9334521178273196},
                  {"index": 2, "relevance_score": 0.34100082626411193}
                ]
              },
              "usage": {"total_tokens": 79},
              "request_id": "85ba5752-1900-47d2-8896-23f99b13f6e1"
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RagProperties props;
    private AtomicReference<String> lastRequest;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.setApiKey("sk-unit-test");
        lastRequest = new AtomicReference<>();
        calls = new AtomicInteger();
    }

    /** 假传输：记录请求体、返回固定响应 */
    private DashScopeRerankClient clientReturning(String responseJson) {
        return new DashScopeRerankClient(props, objectMapper, body -> {
            calls.incrementAndGet();
            lastRequest.set(body);
            return responseJson;
        });
    }

    @Test
    @DisplayName("成功响应：按官方报文解析出 (index, score)，请求体字段与文档一致")
    void successResponseShouldParseIndicesAndBuildOfficialPayload() throws Exception {
        List<ScoredIndex> result = clientReturning(SUCCESS_JSON).rerank(QUERY, DOCS, 2);

        assertThat(result).containsExactly(
                new ScoredIndex(0, 0.9334521178273196),
                new ScoredIndex(2, 0.34100082626411193));

        JsonNode payload = objectMapper.readTree(lastRequest.get());
        assertThat(payload.path("model").asText()).isEqualTo("gte-rerank-v2");
        assertThat(payload.path("input").path("query").asText()).isEqualTo(QUERY);
        assertThat(payload.path("input").path("documents")).hasSize(3);
        assertThat(payload.path("input").path("documents").get(1).asText()).isEqualTo(DOCS.get(1));
        assertThat(payload.path("parameters").path("top_n").asInt()).isEqualTo(2);
        // 正文已在调用方手里，不需要接口回传原文
        assertThat(payload.path("parameters").path("return_documents").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("上游乱序返回时兜底重排：结果必须按 relevance_score 降序")
    void unorderedResultsShouldBeResortedDescending() {
        String json = """
                {"output":{"results":[
                  {"index":1,"relevance_score":0.2},
                  {"index":2,"relevance_score":0.9},
                  {"index":0,"relevance_score":0.5}]}}
                """;
        assertThat(clientReturning(json).rerank(QUERY, DOCS, 3))
                .extracting(ScoredIndex::index).containsExactly(2, 0, 1);
    }

    @Test
    @DisplayName("业务错误码（InvalidApiKey 等）：抛 IllegalStateException 交给调用方降级")
    void errorCodeShouldThrow() {
        String json = """
                {"code":"InvalidApiKey","message":"Invalid API-key provided.",
                 "request_id":"fb53c4ec-1c12-4fc4-a580-cdb7c3261fc1"}
                """;
        assertThatThrownBy(() -> clientReturning(json).rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InvalidApiKey");
    }

    @Test
    @DisplayName("报文缺 output.results / 非法 JSON / 空响应：一律明确报错而不是静默返回空")
    void malformedResponsesShouldThrow() {
        assertThatThrownBy(() -> clientReturning("{\"output\":{}}").rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("output.results");
        assertThatThrownBy(() -> clientReturning("not-json").rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clientReturning("").rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("响应为空");
    }

    @Test
    @DisplayName("越界 index 被丢弃；全部越界时按异常处理触发降级")
    void outOfRangeIndicesShouldBeSkipped() {
        String partial = """
                {"output":{"results":[
                  {"index":9,"relevance_score":0.99},
                  {"index":1,"relevance_score":0.4}]}}
                """;
        assertThat(clientReturning(partial).rerank(QUERY, DOCS, 2))
                .containsExactly(new ScoredIndex(1, 0.4));

        String allInvalid = """
                {"output":{"results":[{"index":-1,"relevance_score":0.9}]}}
                """;
        assertThatThrownBy(() -> clientReturning(allInvalid).rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有有效结果");
    }

    @Test
    @DisplayName("传输超时：包装成 IllegalStateException 且保留原因链，调用方据此降级")
    void transportTimeoutShouldWrapException() {
        DashScopeRerankClient client = new DashScopeRerankClient(props, objectMapper, body -> {
            throw new HttpTimeoutException("request timed out");
        });
        assertThatThrownBy(() -> client.rerank(QUERY, DOCS, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rerank 接口调用失败")
                .hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("空问题 / 空候选：直接返回空列表，一次接口都不调")
    void blankInputShouldSkipHttpCall() {
        DashScopeRerankClient client = clientReturning(SUCCESS_JSON);
        assertThat(client.rerank("  ", DOCS, 2)).isEmpty();
        assertThat(client.rerank(QUERY, List.of(), 2)).isEmpty();
        assertThat(client.rerank(QUERY, null, 2)).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("top_n 钳制：超过候选数按候选数截断，≤0 表示全部返回")
    void topNShouldBeClampedToDocumentCount() throws Exception {
        clientReturning(SUCCESS_JSON).rerank(QUERY, DOCS, 99);
        assertThat(objectMapper.readTree(lastRequest.get()).path("parameters").path("top_n").asInt())
                .isEqualTo(3);

        clientReturning(SUCCESS_JSON).rerank(QUERY, DOCS, 0);
        assertThat(objectMapper.readTree(lastRequest.get()).path("parameters").path("top_n").asInt())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("isAvailable 矩阵：开关 / 密钥 / 演示模式任一不满足都不可用（无网测试因此零影响）")
    void availabilityMatrix() {
        DashScopeRerankClient client = clientReturning(SUCCESS_JSON);
        assertThat(client.isAvailable()).isTrue();

        props.getRerank().setEnabled(false);
        assertThat(client.isAvailable()).isFalse();
        props.getRerank().setEnabled(true);

        props.setApiKey("");
        assertThat(client.isAvailable()).isFalse();
        props.setApiKey("sk-unit-test");

        props.setMockEnabled(true);
        assertThat(client.isAvailable()).isFalse();
    }
}