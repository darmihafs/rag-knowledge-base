package com.bishe.kb.model.vo;

import java.time.LocalDateTime;

/**
 * 文档列表项视图对象：只暴露前端需要的字段，路径等内部信息不外泄。
 */
public record DocumentVO(
        Long id,
        Long spaceId,
        String title,
        String fileType,
        Long fileSize,
        String status,
        Integer chunkCount,
        String errorMsg,
        LocalDateTime createTime
) {
}
