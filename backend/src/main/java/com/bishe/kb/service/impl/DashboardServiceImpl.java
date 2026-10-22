package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bishe.kb.mapper.ChatMessageMapper;
import com.bishe.kb.mapper.KbChunkMapper;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.mapper.KbSpaceMapper;
import com.bishe.kb.model.dto.AuditPageQuery;
import com.bishe.kb.model.entity.ChatMessage;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.vo.ActivityVO;
import com.bishe.kb.model.vo.DashboardStatsVO;
import com.bishe.kb.model.vo.FormatSlice;
import com.bishe.kb.model.vo.TrendPoint;
import com.bishe.kb.service.ChatAuditService;
import com.bishe.kb.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据大盘统计实现。
 *
 * <p>口径说明：
 * <ul>
 *   <li>空间/文档数走 {@code selectCount}，@TableLogic 自动过滤已删除；分段表无逻辑删除，直接全量计数。</li>
 *   <li>提问数只统计 {@code role='user'} 的消息（一轮问答里 assistant 那条不计入）。</li>
 *   <li>近 7 日走势在 Java 侧按 {@link LocalDate} 分桶并零填充，避免 MySQL/H2 的 DATE() 方言差异。</li>
 *   <li>格式分布用 {@code group by file_type} 一次聚合，比逐类型 count 更省往返。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 走势统计的天数（含今日） */
    private static final int TREND_DAYS = 7;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 动态流各取多少条：再多就超出卡片高度了 */
    private static final int RECENT_DOCUMENTS = 6;
    private static final int RECENT_QUESTIONS = 8;

    /** 动态流里问题文本的截断长度 */
    private static final int QUESTION_LENGTH = 60;

    private final KbSpaceMapper spaceMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatAuditService chatAuditService;

    @Override
    public DashboardStatsVO stats() {
        long spaceCount = spaceMapper.selectCount(null);
        long documentCount = documentMapper.selectCount(null);
        long chunkCount = chunkMapper.selectCount(null);

        LocalDate today = LocalDate.now();
        LocalDateTime startOfToday = today.atStartOfDay();
        long todayQuestions = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER)
                .ge(ChatMessage::getCreateTime, startOfToday));

        return new DashboardStatsVO(spaceCount, documentCount, chunkCount, todayQuestions,
                buildTrend(today), buildFormatDistribution());
    }

    /**
     * 第二排动态流：最近入库文档 + 最近问答。
     *
     * <p>问答部分直接复用审计服务的第一页（它已经把提问、提问人、引用数配对好了），
     * 而不是在这里再写一遍跨表配对逻辑——两处各写一份迟早会对不上口径。
     */
    @Override
    public ActivityVO activity() {
        List<ActivityVO.RecentDocument> documents = recentDocuments();
        List<ActivityVO.RecentQuestion> questions = chatAuditService
                .pageMessages(new AuditPageQuery(1, RECENT_QUESTIONS, null, null))
                .getRecords().stream()
                .map(m -> new ActivityVO.RecentQuestion(m.messageId(), m.sessionId(), m.username(),
                        m.nickname(), StringUtils.abbreviate(m.question(), QUESTION_LENGTH),
                        m.citationCount(), m.askTime()))
                .toList();
        return new ActivityVO(documents, questions);
    }

    /** 最近入库文档：按创建时间倒序取若干条，空间名一次批量补齐 */
    private List<ActivityVO.RecentDocument> recentDocuments() {
        List<KbDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .orderByDesc(KbDocument::getId)
                .last("limit " + RECENT_DOCUMENTS));
        if (docs.isEmpty()) {
            return List.of();
        }
        List<Long> spaceIds = docs.stream().map(KbDocument::getSpaceId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = spaceIds.isEmpty() ? Map.of()
                : spaceMapper.selectBatchIds(spaceIds).stream()
                        .collect(Collectors.toMap(KbSpace::getId, KbSpace::getName, (a, b) -> a));

        return docs.stream()
                .map(d -> new ActivityVO.RecentDocument(d.getId(), d.getTitle(), d.getFileType(),
                        names.get(d.getSpaceId()), d.getChunkCount(), d.getStatus(),
                        statusCn(d.getStatus()), d.getCreateTime()))
                .toList();
    }

    private String statusCn(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "PENDING" -> "待解析";
            case "PARSING" -> "解析中";
            case "INDEXED" -> "已索引";
            case "FAILED" -> "解析失败";
            default -> status;
        };
    }

    /** 近 7 日（含今日）提问走势：拉取区间内的 user 消息，在内存按天分桶并对缺失日期补 0 */
    private List<TrendPoint> buildTrend(LocalDate today) {
        LocalDate fromDate = today.minusDays(TREND_DAYS - 1L);
        List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .select(ChatMessage::getCreateTime)
                .eq(ChatMessage::getRole, ChatMessage.ROLE_USER)
                .ge(ChatMessage::getCreateTime, fromDate.atStartOfDay()));

        // 先按顺序铺满 7 天，保证前端折线横轴连续、无空洞
        Map<LocalDate, Long> bucket = new LinkedHashMap<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            bucket.put(fromDate.plusDays(i), 0L);
        }
        for (ChatMessage m : messages) {
            LocalDateTime ct = m.getCreateTime();
            if (ct == null) {
                continue;
            }
            LocalDate day = ct.toLocalDate();
            if (bucket.containsKey(day)) {
                bucket.merge(day, 1L, Long::sum);
            }
        }
        List<TrendPoint> trend = new ArrayList<>(TREND_DAYS);
        bucket.forEach((day, count) -> trend.add(new TrendPoint(day.format(DATE_FORMAT), count)));
        return trend;
    }

    /** 文档格式分布：group by file_type 聚合，按数量降序返回 */
    private List<FormatSlice> buildFormatDistribution() {
        // 直接用原始列名 file_type 作为 key，避免 H2(DATABASE_TO_LOWER) 把驼峰别名转小写导致取不到值
        QueryWrapper<KbDocument> wrapper = new QueryWrapper<KbDocument>()
                .select("file_type", "COUNT(*) AS cnt")
                .groupBy("file_type")
                .orderByDesc("COUNT(*)");
        List<Map<String, Object>> rows = documentMapper.selectMaps(wrapper);
        List<FormatSlice> formats = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object type = row.get("file_type");
            Object cnt = row.get("cnt");
            formats.add(new FormatSlice(
                    type == null ? "未知" : String.valueOf(type),
                    cnt == null ? 0L : ((Number) cnt).longValue()));
        }
        return formats;
    }
}
