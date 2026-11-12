package com.bishe.kb.service.impl;

import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.service.RetrievalScope;
import com.bishe.kb.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rerank 精排装饰器：把生产混合检索升级为「双路召回 → RRF 粗排 → gte-rerank 精排」三段流水线。
 *
 * <p>采用装饰器而不是改 {@link RetrievalServiceImpl}，有三个原因：
 * <ol>
 *   <li>粗排实现保持原样，{@code LexicalRetrievalRoutingTest} 等直接 new 三参构造器的测试零改动；</li>
 *   <li>精排是可选增强：开关关闭 / 无 API Key / 演示模式 / 超时 / 接口报错时整体透传或回退，
 *       旧链路的行为一个字节都不变；</li>
 *   <li>消融实验的 LEXICAL / SEMANTIC 单路必须保持「纯」，装饰器只拦 HYBRID，
 *       单路请求原样直达粗排实现，实验数据不被精排污染。</li>
 * </ol>
 *
 * <p>{@code @Primary} 让所有按 {@link RetrievalService} 接口注入的调用方
 * （问答、Agent 工具、检索实验室）自动拿到带精排的流水线；
 * 需要裸粗排的地方（如评测基线）直接注入 {@link RetrievalServiceImpl}。
 *
 * <p><b>降级契约</b>：精排环节任何 {@link RuntimeException} 都会被 catch，
 * 回退为 RRF 粗排列表的前 topK 条——粗排候选本来就多取了（{@code rag.rerank.candidates}，默认 20），
 * 截断即得粗排 Top-K，不需要再查一次库。权限语义不受影响：
 * {@link RetrievalScope} 原样传给粗排，空授权在粗排入口就被挡成空列表，精排根本不会发起。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RerankRetrievalService implements RetrievalService {

    private final RetrievalServiceImpl delegate;
    private final DashScopeRerankClient rerankClient;
    private final RagProperties props;

    /**
     * 降级计数器：精排失败回退粗排时自增。字段在声明处初始化，Lombok 不会把它纳入
     * {@code @RequiredArgsConstructor} 的三参构造器（{@code LexicalRetrievalRoutingTest}
     * 等直接 new 的测试零改动）。评测跑批用它统计「精排臂降级题数」，
     * 如实反映本批有多少题的精排调用没成功。
     */
    private final AtomicLong fallbackCount = new AtomicLong();

    @Override
    public List<KbChunk> retrieve(String question, int topK, Route route, RetrievalScope scope) {
        // 单路消融 / 非法入参 / 精排不可用：一律透传，行为与旧链路完全一致
        if (route != Route.HYBRID || topK <= 0 || StringUtils.isBlank(question) || !rerankClient.isAvailable()) {
            return delegate.retrieve(question, topK, route, scope);
        }

        RagProperties.Rerank cfg = props.getRerank();
        // 粗排多取一些候选给精排挑：精排只能重排送进去的列表，候选池太小体现不出 Cross-Encoder 的收益
        int coarseK = Math.max(topK, Math.max(1, cfg.getCandidates()));
        List<KbChunk> coarse = delegate.retrieve(question, coarseK, Route.HYBRID, scope);
        if (coarse.isEmpty()) {
            return coarse;
        }

        // 最终保留条数 = min(调用方要的 topK, 配置的精排 topN)，任何一侧都不会被突破
        int keep = Math.min(topK, cfg.getTopN() > 0 ? cfg.getTopN() : topK);
        try {
            List<String> documents = coarse.stream().map(KbChunk::getContent).toList();
            List<DashScopeRerankClient.ScoredIndex> ranked = rerankClient.rerank(question, documents, keep);
            List<KbChunk> refined = new ArrayList<>(ranked.size());
            for (DashScopeRerankClient.ScoredIndex scored : ranked) {
                // index 已在客户端按候选数校验过界，这里直接映射回粗排列表
                refined.add(coarse.get(scored.index()));
            }
            if (refined.isEmpty()) {
                throw new IllegalStateException("rerank 返回空结果");
            }
            return refined.size() > keep ? List.copyOf(refined.subList(0, keep)) : List.copyOf(refined);
        } catch (RuntimeException e) {
            // 超时 / 网络错误 / 业务错误码 / 报文异常：平滑降级为 RRF 粗排 Top-K，绝不阻断问答主链路
            fallbackCount.incrementAndGet();
            log.warn("Rerank 精排失败，降级为 RRF 粗排 Top-{}：{}", keep, e.getMessage());
            return List.copyOf(coarse.subList(0, Math.min(topK, coarse.size())));
        }
    }

    /**
     * 进程启动以来精排降级（回退粗排）的累计次数。供评测跑批做差值统计与运维观测，
     * 不参与检索逻辑；线程安全。
     */
    public long fallbackCount() {
        return fallbackCount.get();
    }
}