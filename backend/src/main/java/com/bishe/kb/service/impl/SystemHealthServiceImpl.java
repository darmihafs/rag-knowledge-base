package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bishe.kb.config.RagProperties;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.model.entity.KbChunk;
import com.bishe.kb.model.vo.ComponentStatusVO;
import com.bishe.kb.model.vo.ComponentStatusVO.Field;
import com.bishe.kb.model.vo.DashboardHealthVO;
import com.bishe.kb.service.SystemHealthService;
import com.bishe.kb.service.VectorStoreEngine;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 组件体检实现。全部检查都限定在「本地、毫秒级」的范围内：
 *
 * <ul>
 *   <li>MySQL 真探活——取一次连接并 {@code isValid(2)}，顺带读 Hikari 池的实时占用；</li>
 *   <li>大模型不发起远程调用——那会让每次刷新大屏都花掉一次推理请求的钱和几秒等待，
 *       因此只报告配置与装配状态，并在说明里写清楚「未远程探活」；</li>
 *   <li>向量引擎读一次维度即缓存，向量库文件只 stat + 解析 JSON 数组长度。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthServiceImpl implements SystemHealthService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 探活连接的校验超时（秒），设小一点避免数据库卡住时大屏转圈 */
    private static final int VALIDATE_TIMEOUT_SECONDS = 2;

    /** 取连接超过这个耗时就把数据库标成「降级」，作为池子被占满的早期信号 */
    private static final int SLOW_CONNECT_MILLIS = 200;

    private final DataSource dataSource;
    private final RagProperties props;
    private final VectorStoreEngine vectorStoreEngine;
    private final KbChunkMapper chunkMapper;

    @Override
    public DashboardHealthVO health() {
        List<ComponentStatusVO> components = new ArrayList<>(4);
        components.add(mysql());
        components.add(chatModel());
        components.add(embeddingEngine());
        components.add(vectorStore());

        long online = components.stream().filter(c -> ComponentStatusVO.ONLINE.equals(c.state())).count();
        return new DashboardHealthVO(LocalDateTime.now(), components, online, components.size());
    }

    // ------------------------------------------------------------------ 四个组件

    /** 数据库：真连接 + 连接池实时指标 */
    private ComponentStatusVO mysql() {
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            long elapsed = System.currentTimeMillis() - start;
            DatabaseMetaData meta = connection.getMetaData();
            String jdbcUrl = StringUtils.defaultString(meta.getURL());
            if (!connection.isValid(VALIDATE_TIMEOUT_SECONDS)) {
                return ComponentStatusVO.of("mysql", "MySQL 关系数据库", ComponentStatusVO.OFFLINE,
                        jdbcUrl, List.of(new Field("连接校验", "isValid 失败")),
                        "连接池能给出连接但数据库未响应校验，请检查 MySQL 服务状态");
            }

            List<Field> fields = new ArrayList<>();
            fields.add(new Field("数据库", StringUtils.defaultString(meta.getDatabaseProductName())
                    + " " + StringUtils.defaultString(meta.getDatabaseProductVersion())));
            fields.add(new Field("握手耗时", elapsed + " ms"));
            appendPoolFields(fields);

            String state = elapsed > SLOW_CONNECT_MILLIS ? ComponentStatusVO.DEGRADED : ComponentStatusVO.ONLINE;
            return ComponentStatusVO.of("mysql", "MySQL 关系数据库", state, jdbcUrl, fields,
                    elapsed > SLOW_CONNECT_MILLIS ? "连接可用但响应偏慢，检索与落库会受影响" : null);
        } catch (Exception e) {
            log.warn("数据库体检失败: {}", e.getMessage());
            return ComponentStatusVO.of("mysql", "MySQL 关系数据库", ComponentStatusVO.OFFLINE,
                    "见 spring.datasource.url", List.of(), "无法取得数据库连接：" + e.getMessage());
        }
    }

    /** 生成模型：只报配置与装配状态，不做远程推理探活 */
    private ComponentStatusVO chatModel() {
        List<Field> fields = List.of(
                new Field("模型", props.getChatModel()),
                new Field("采样温度", String.valueOf(props.getTemperature())),
                new Field("协议", "OpenAI 兼容"),
                new Field("服务地址", props.getBaseUrl()),
                new Field("密钥", StringUtils.isNotBlank(props.getApiKey()) ? "已配置" : "未配置"));

        if (props.isMockEnabled()) {
            return ComponentStatusVO.of("llm", "大模型生成（Qwen）", ComponentStatusVO.DISABLED, props.getBaseUrl(),
                    fields, "演示模式 rag.mock-enabled=true：回答由检索片段拼装，未真正调用大模型");
        }
        if (StringUtils.isBlank(props.getApiKey())) {
            return ComponentStatusVO.of("llm", "大模型生成（Qwen）", ComponentStatusVO.OFFLINE, props.getBaseUrl(),
                    fields, "未配置 API Key，问答接口会直接报「未配置模型密钥」");
        }
        return ComponentStatusVO.of("llm", "大模型生成（Qwen）", ComponentStatusVO.ONLINE, props.getBaseUrl(),
                fields, "配置与客户端装配就绪；为避免每次刷新大屏都产生一次推理计费，本项不做远程探活");
    }

    /** 向量化引擎：开关 + 模型 Bean 是否装配 + 维度 */
    private ComponentStatusVO embeddingEngine() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("模型", props.getEmbeddingModel()));
        fields.add(new Field("批量大小", String.valueOf(props.getEmbeddingBatchSize())));
        fields.add(new Field("语义分数下限", String.valueOf(props.getSemanticMinScore())));

        if (!props.isEmbeddingEnabled()) {
            fields.add(new Field("向量维度", "—"));
            return ComponentStatusVO.of("embedding", "向量化引擎（Embedding）", ComponentStatusVO.DISABLED,
                    props.getBaseUrl(), fields,
                    "rag.embedding-enabled=false：语义检索路关闭，混合检索自动退化为纯词法");
        }
        if (!vectorStoreEngine.isEnabled()) {
            fields.add(new Field("向量维度", "—"));
            return ComponentStatusVO.of("embedding", "向量化引擎（Embedding）", ComponentStatusVO.OFFLINE,
                    props.getBaseUrl(), fields, "开关已打开但向量模型 Bean 未装配，请检查密钥与启动日志");
        }
        Integer dimension = vectorStoreEngine.resolveDimension();
        fields.add(new Field("向量维度", dimension == null ? "探测失败" : String.valueOf(dimension)));
        if (dimension == null) {
            return ComponentStatusVO.of("embedding", "向量化引擎（Embedding）", ComponentStatusVO.OFFLINE,
                    props.getBaseUrl(), fields, "向量模型调用失败，语义检索本次不可用（多为密钥或网络问题）");
        }
        return ComponentStatusVO.of("embedding", "向量化引擎（Embedding）", ComponentStatusVO.ONLINE,
                props.getBaseUrl(), fields, null);
    }

    /** 本地向量库持久化：把「文件里到底有多少条向量」和「库里有多少条启用切片」摆在一起比 */
    private ComponentStatusVO vectorStore() {
        VectorStoreEngine.VectorStoreInfo info = vectorStoreEngine.inspectStore();
        long enabledChunks = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getEnabled, 1));

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("存储文件", info.path()));
        fields.add(new Field("文件状态", info.exists() ? "存在" : "未生成"));
        fields.add(new Field("文件大小", info.bytes() < 0 ? "未知" : formatBytes(info.bytes())));
        fields.add(new Field("向量条目", info.entries() < 0 ? "未统计" : info.entries() + " 条"));
        fields.add(new Field("启用切片", enabledChunks + " 条"));
        fields.add(new Field("最后写入", lastModified(info.path())));

        if (!info.exists()) {
            return enabledChunks == 0
                    ? ComponentStatusVO.of("vectorStore", "本地向量库持久化", ComponentStatusVO.ONLINE,
                            info.path(), fields, "库内暂无可向量化的切片，文件将在首次向量化后生成")
                    : ComponentStatusVO.of("vectorStore", "本地向量库持久化", ComponentStatusVO.DEGRADED,
                            info.path(), fields,
                            "有 " + enabledChunks + " 条启用切片但向量文件尚未生成，语义检索当前无候选");
        }
        if (info.entries() < 0) {
            return ComponentStatusVO.of("vectorStore", "本地向量库持久化", ComponentStatusVO.DEGRADED,
                    info.path(), fields, info.note());
        }
        if (info.entries() < enabledChunks) {
            long gap = enabledChunks - info.entries();
            return ComponentStatusVO.of("vectorStore", "本地向量库持久化", ComponentStatusVO.DEGRADED,
                    info.path(), fields,
                    "向量条目比启用切片少 " + gap + " 条，差额切片只能被词法召回；可对相应文档重新上传以补索引");
        }
        return ComponentStatusVO.of("vectorStore", "本地向量库持久化", ComponentStatusVO.ONLINE,
                info.path(), fields, info.note());
    }

    // ------------------------------------------------------------------ 小工具

    /** Hikari 池指标：只有 DataSource 就是 HikariDataSource 时才读得到，其余实现跳过 */
    private void appendPoolFields(List<Field> fields) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            fields.add(new Field("连接池", "非 Hikari，跳过统计"));
            return;
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            fields.add(new Field("连接池", "尚未初始化"));
            return;
        }
        fields.add(new Field("连接池", "活跃 " + pool.getActiveConnections()
                + " / 空闲 " + pool.getIdleConnections()
                + " / 总计 " + pool.getTotalConnections()
                + " / 上限 " + hikari.getMaximumPoolSize()));
        if (pool.getThreadsAwaitingConnection() > 0) {
            fields.add(new Field("等待连接的线程", String.valueOf(pool.getThreadsAwaitingConnection())));
        }
    }

    private String lastModified(String path) {
        try {
            Instant time = Files.getLastModifiedTime(Path.of(path)).toInstant();
            return TIME_FORMAT.format(LocalDateTime.ofInstant(time, ZoneId.systemDefault()));
        } catch (IOException e) {
            return "未知";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
