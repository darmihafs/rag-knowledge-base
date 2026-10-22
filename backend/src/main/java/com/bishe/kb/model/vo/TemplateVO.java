package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 提示词模板视图：聊天页胶囊与管理端列表共用同一结构。
 *
 * @param enabled 1=聊天页可见，0=已下架；只有管理端列表会返回 0 的行
 */
public record TemplateVO(
        Long id,
        String title,
        String category,
        String promptContent,
        String description,
        Integer enabled,
        Integer sortOrder,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
