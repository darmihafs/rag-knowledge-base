package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 */
public record SessionVO(
        Long id,
        String title,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
