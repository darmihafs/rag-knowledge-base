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
 * 知识库文档主表：一次上传对应一行，解析出的分段存 kb_chunk。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 处理状态机：待解析 -> 解析中 -> 已索引 / 失败 */
    public enum Status {
        PENDING, PARSING, INDEXED, FAILED
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属知识库空间 ID，默认归入 id=1 的默认空间 */
    private Long spaceId;

    private String title;

    /** 原始文件名后缀，如 pdf / docx / md / txt */
    private String fileType;

    /** 落盘相对路径（相对于 kb.upload-dir） */
    private String filePath;

    private Long fileSize;

    /**
     * 上传文件 SHA-256（十六进制）。
     *
     * <p>用于识别「同一空间、同标题、同类型、同内容」的重复上传；历史数据为空，
     * 不参与去重，也不影响预览与检索。
     */
    private String fileHash;

    /** 处理状态机：待解析 -> 解析中 -> 已索引 / 失败，取值见 {@link Status} */
    private String status;

    /** 解析出的分段数量，冗余字段便于列表展示 */
    private Integer chunkCount;

    /** 解析失败或异常原因，便于前端提示与重试 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
