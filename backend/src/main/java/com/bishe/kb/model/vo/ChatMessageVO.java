package com.bishe.kb.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息项：citations 已从库里的 JSON 字符串反序列化回对象，前端可直接渲染。
 */
public record ChatMessageVO(
        Long id,
        Long sessionId,
        String role,
        String content,
        List<AskResponse.Citation> citations,
        LocalDateTime createTime
) {
}
