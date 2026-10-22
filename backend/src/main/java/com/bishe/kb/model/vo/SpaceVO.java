package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 知识库空间视图对象：前端空间侧栏 / 管理列表用。
 */
public record SpaceVO(
        Long id,
        String name,
        String description,
        Integer docCount,
        Integer chunkCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
