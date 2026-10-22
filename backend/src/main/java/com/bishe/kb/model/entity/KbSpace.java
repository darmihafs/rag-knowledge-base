package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库空间：一个空间就是一个独立的知识库，文档通过 {@code kb_document.space_id} 归属到空间下。
 *
 * <p>{@link #docCount} / {@link #chunkCount} 是冗余统计字段，方便列表直接展示与大盘汇总，
 * 由 {@code KbSpaceService#recount} 在文档增删后统一重算，避免并发自增导致的计数漂移。
 * id=1 是系统默认空间，历史文档全部归入，不可删除。
 */
@Data
@TableName("kb_space")
public class KbSpace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 创建人用户ID：由拦截器写入的当前登录用户填充，历史数据回填给管理员(id=1) */
    private Long createdBy;

    /** 该空间下的文档数（冗余统计） */
    private Integer docCount;

    /** 该空间下所有文档的分段总数（冗余统计） */
    private Integer chunkCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
