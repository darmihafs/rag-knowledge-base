package com.bishe.kb.service;

import com.bishe.kb.model.dto.RetrieveCompareRequest;
import com.bishe.kb.model.vo.RetrieveCompareVO;

/**
 * 检索对比服务：把同一个问题的三条召回路（纯词法 / 纯语义 / RRF 混合）并排跑一遍。
 *
 * <p>它只是 {@link RetrievalService} 的「同题三路」视图，不复制任何打分逻辑，
 * 因此实验室里看到的就是线上问答真实使用的召回行为。
 */
public interface RetrievalCompareService {

    RetrieveCompareVO compare(RetrieveCompareRequest request);
}
