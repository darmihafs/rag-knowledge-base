package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分段（RAG 的检索单元）。
 *
 * <p>向量本身不落 MySQL：一期用内存 EmbeddingStore（进程重启后按本表重建），
 * 二期接 Milvus / PGVector 时把 {@link #vectorRef} 写成外部向量 ID。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 在所属文档内的序号，重建向量库时按它顺序回放 */
    private Integer chunkIndex;

    /** PDF 分段的起始页码（从 1 开始）；非 PDF 文档没有稳定页概念，保持为 null */
    private Integer pageNumber;

    /** 分段正文 */
    private String content;

    private Integer charCount;

    /** 是否启用：1=参与检索召回，0=人工禁用不召回 */
    private Integer enabled;

    /** 外部向量库中的 ID；内存模式下为空 */
    private String vectorRef;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 全文索引召回时的 MySQL 相关度分值（不是表字段，仅这一次查询的结果集里带回来）。
     *
     * <p>LIKE 兜底分支不填它，排序由「命中词数」自己算，所以这里是可空的包装类型。
     */
    @TableField(exist = false)
    private Double relevance;
}
