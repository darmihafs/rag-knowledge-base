package com.bishe.kb.service.impl;

import com.bishe.kb.common.BizException;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.model.dto.SystemConfigRequest;
import com.bishe.kb.model.vo.SystemConfigVO;
import com.bishe.kb.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎参数运行时配置实现。
 *
 * <p>快照在 {@code @PostConstruct} 里抓一次：此时 {@code application.yml} 与
 * {@code application-local.yml} 都已绑定完成，抓到的就是本次进程的「出厂值」，
 * 「恢复默认配置」因此不需要知道 yaml 文件在哪、也不用重新读盘。
 *
 * <p>只有「每次调用都从 props 读」的参数才允许在这里改：召回名额、RRF 常数、相似度阈值、
 * 切分粒度、历史轮数、系统提示。模型名与温度在装配期就被固化进 LangChain4j 的客户端 Bean，
 * 运行时改动不会生效，所以它们只出现在只读区，并在 hints 里说明「需重启」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    /** 系统提示允许的最大长度，防手滑把整篇文档粘进去把上下文撑爆 */
    private static final int MAX_PROMPT_CHARS = 4000;

    private final RagProperties props;

    /** 启动时的出厂快照，永不写回业务对象，只用于展示默认值与恢复 */
    private RagProperties defaults;

    @PostConstruct
    void captureDefaults() {
        RagProperties snapshot = new RagProperties();
        BeanUtils.copyProperties(props, snapshot);
        defaults = snapshot;
        log.info("引擎参数出厂快照已记录: topK={} rrfK={} chunk={}/{} history={}",
                snapshot.getTopK(), snapshot.getRrfK(), snapshot.getChunkMaxChars(),
                snapshot.getChunkOverlapChars(), snapshot.getHistoryMessages());
    }

    @Override
    public SystemConfigVO current() {
        return toVO(props);
    }

    @Override
    public SystemConfigVO update(SystemConfigRequest request) {
        validate(request);

        if (request.topK() != null) {
            props.setTopK(request.topK());
        }
        if (request.rrfK() != null) {
            props.setRrfK(request.rrfK());
        }
        if (request.semanticMinScore() != null) {
            props.setSemanticMinScore(request.semanticMinScore());
        }
        if (request.chunkMaxChars() != null) {
            props.setChunkMaxChars(request.chunkMaxChars());
        }
        if (request.chunkOverlapChars() != null) {
            props.setChunkOverlapChars(request.chunkOverlapChars());
        }
        if (request.historyMessages() != null) {
            props.setHistoryMessages(request.historyMessages());
        }
        if (request.historyMaxChars() != null) {
            props.setHistoryMaxChars(request.historyMaxChars());
        }
        if (StringUtils.isNotBlank(request.systemPrompt())) {
            props.setSystemPrompt(request.systemPrompt().trim());
        }
        log.info("引擎参数已运行时更新: topK={} rrfK={} minScore={} chunk={}/{} history={}/{} 提示词长度={}",
                props.getTopK(), props.getRrfK(), props.getSemanticMinScore(), props.getChunkMaxChars(),
                props.getChunkOverlapChars(), props.getHistoryMessages(), props.getHistoryMaxChars(),
                StringUtils.length(props.getSystemPrompt()));
        return toVO(props);
    }

    @Override
    public SystemConfigVO resetDefaults() {
        props.setTopK(defaults.getTopK());
        props.setRrfK(defaults.getRrfK());
        props.setSemanticMinScore(defaults.getSemanticMinScore());
        props.setChunkMaxChars(defaults.getChunkMaxChars());
        props.setChunkOverlapChars(defaults.getChunkOverlapChars());
        props.setHistoryMessages(defaults.getHistoryMessages());
        props.setHistoryMaxChars(defaults.getHistoryMaxChars());
        props.setSystemPrompt(defaults.getSystemPrompt());
        log.info("引擎参数已恢复为启动时的出厂配置");
        return toVO(props);
    }

    // ------------------------------------------------------------------ 校验与组装

    /**
     * 跨字段约束在这里补：单字段范围由 DTO 注解负责，切片上限与重叠的关系只能合起来看。
     *
     * <p>业务码用 402x 段：401x 已被认证类占用（4010 未登录 / 4011 非法 token / 4012 过期），
     * 复用会让前端和日志按「登录态问题」去理解一个配置校验失败。
     */
    private void validate(SystemConfigRequest request) {
        int maxChars = request.chunkMaxChars() == null ? props.getChunkMaxChars() : request.chunkMaxChars();
        int overlap = request.chunkOverlapChars() == null ? props.getChunkOverlapChars() : request.chunkOverlapChars();
        if (overlap >= maxChars) {
            throw new BizException(4021, "重叠字符必须小于切片上限，否则切分窗口无法前进");
        }
        if (overlap > maxChars / 2) {
            throw new BizException(4022, "重叠字符不宜超过切片上限的一半，否则切片大量重复、候选池被稀释");
        }
        if (request.systemPrompt() != null && StringUtils.isBlank(request.systemPrompt())) {
            throw new BizException(4023, "系统提示不能为空白；若要使用内置默认提示，请点「恢复默认配置」");
        }
        if (StringUtils.length(request.systemPrompt()) > MAX_PROMPT_CHARS) {
            throw new BizException(4024, "系统提示过长，最多 " + MAX_PROMPT_CHARS + " 字");
        }
    }

    private SystemConfigVO toVO(RagProperties source) {
        return new SystemConfigVO(
                source.getTopK(), source.getRrfK(), source.getSemanticMinScore(),
                source.getChunkMaxChars(), source.getChunkOverlapChars(),
                source.getHistoryMessages(), source.getHistoryMaxChars(),
                StringUtils.defaultString(source.getSystemPrompt()),
                source.isEmbeddingEnabled(), source.isMockEnabled(),
                editableMap(defaults), runtimeMap(source), hints());
    }

    /** 默认值只暴露可改的那几项，密钥相关一律不外泄 */
    private Map<String, Object> editableMap(RagProperties source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("topK", source.getTopK());
        map.put("rrfK", source.getRrfK());
        map.put("semanticMinScore", source.getSemanticMinScore());
        map.put("chunkMaxChars", source.getChunkMaxChars());
        map.put("chunkOverlapChars", source.getChunkOverlapChars());
        map.put("historyMessages", source.getHistoryMessages());
        map.put("historyMaxChars", source.getHistoryMaxChars());
        map.put("systemPrompt", StringUtils.defaultString(source.getSystemPrompt()));
        return map;
    }

    private Map<String, Object> runtimeMap(RagProperties source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chatModel", source.getChatModel());
        map.put("embeddingModel", source.getEmbeddingModel());
        map.put("baseUrl", source.getBaseUrl());
        map.put("temperature", source.getTemperature());
        map.put("apiKeyConfigured", StringUtils.isNotBlank(source.getApiKey()));
        map.put("minScore", source.getMinScore());
        map.put("embeddingBatchSize", source.getEmbeddingBatchSize());
        map.put("uploadDir", source.getUploadDir());
        map.put("embeddingStorePath", source.getEmbeddingStorePath());
        return map;
    }

    private Map<String, String> hints() {
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("topK", "进入 Prompt 的切片数量，问答与检索实验室都按它截断；即时生效。");
        hints.put("rrfK", "RRF 融合常数 k，得分 = Σ 1/(k+rank)。k 越大两路名次差异被压得越平；即时生效。");
        hints.put("semanticMinScore", "语义路余弦相似度下限，0 表示只按名次截断，不做绝对分数过滤。");
        hints.put("chunkMaxChars", "单切片上限，只影响此后上传文档的切分结果，已入库切片不会重切。");
        hints.put("chunkOverlapChars", "相邻切片重叠字数，用于避免关键句被切分边界劈开。");
        hints.put("historyMessages", "多轮上下文携带的历史条数，0 即退化为单轮问答。");
        hints.put("historyMaxChars", "单条历史进 Prompt 前的截断长度，防止长回答挤爆上下文。");
        hints.put("systemPrompt", "系统级防幻觉提示词；留空即使用代码内置默认规则。资料区由后端自动追加，不必写 %s。");
        hints.put("runtime", "模型名、baseUrl、温度与密钥在装配期固化进模型客户端 Bean，改动需重启后端才生效。");
        return hints;
    }
}
