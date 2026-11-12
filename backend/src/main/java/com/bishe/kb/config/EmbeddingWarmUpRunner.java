package com.bishe.kb.config;

import com.bishe.kb.service.VectorStoreEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 向量链路启动预热：应用就绪后在后台发一次 embedding 请求，把首查的冷启动成本提前付掉。
 *
 * <p>实测问题向量化的耗时里很大一块不是模型计算，而是<b>第一次建连</b>——DNS、TLS 握手、
 * 连接池预热都只发生在首个请求上。不预热的话，答辩现场第一位提问的人要等十几秒才看到
 * 语义召回结果，看起来像「系统变慢了」；预热过之后同样的请求只需要模型本身的时间。
 *
 * <p>三点约束：
 * <ul>
 *   <li>监听 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：端口已经就绪，
 *       健康探针不会因为一次外网调用而超时；</li>
 *   <li>放在虚拟线程上异步执行：预热的十几秒绝不能压住启动，也不占用 SSE 线程池；</li>
 *   <li>只做一次、失败不重试：向量检索未启用时直接跳过，离线演示不会因此多出一次公网调用。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingWarmUpRunner {

    private final VectorStoreEngine vectorStoreEngine;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAsync() {
        if (!vectorStoreEngine.isEnabled()) {
            log.info("跳过向量链路预热：rag.embedding-enabled=false 或向量模型未装配，问答走词法检索");
            return;
        }
        // 一次性的阻塞外网调用用虚拟线程：不建线程、不进队列，跑完自然结束
        Thread.ofVirtual().name("rag-embedding-warmup-").start(() -> {
            try {
                log.info("向量链路预热 {}", vectorStoreEngine.warmUp());
            } catch (RuntimeException e) {
                log.warn("向量链路预热异常（不影响启动，问答自动退化为词法检索）: {}", e.getMessage());
            }
        });
    }
}
