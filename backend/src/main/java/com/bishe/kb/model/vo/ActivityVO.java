package com.bishe.kb.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大屏第二排的「最近入库文档与问答动态」。
 *
 * <p>两个列表都按时间倒序取前若干条，让首页能看出系统此刻是「活的」：
 * 刚进了什么文档、谁在什么时候问了什么。
 */
public record ActivityVO(
        List<RecentDocument> recentDocuments,
        List<RecentQuestion> recentQuestions
) {

    /** @param statusCn 处理状态的中文口径，前端直接展示，避免自己维护一套枚举翻译 */
    public record RecentDocument(
            Long id,
            String title,
            String fileType,
            String spaceName,
            Integer chunkCount,
            String status,
            String statusCn,
            LocalDateTime createTime
    ) {
    }

    /** @param citationCount 该次回答附带的引用切片数，0 表示模型没拿到资料（可据此看出「空答」比例） */
    public record RecentQuestion(
            Long messageId,
            Long sessionId,
            String username,
            String nickname,
            String question,
            Integer citationCount,
            LocalDateTime createTime
    ) {
    }

    public static ActivityVO empty() {
        return new ActivityVO(List.of(), List.of());
    }
}
