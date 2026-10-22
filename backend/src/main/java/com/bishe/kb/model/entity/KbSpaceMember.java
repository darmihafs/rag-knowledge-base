package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库空间成员：一行记录 = 「用户 {@code userId} 可以读空间 {@code spaceId} 的语料」。
 *
 * <p>这是把「空间」从<b>分类标签</b>升级成<b>权限边界</b>的那张表：在 V8 之前 {@code spaceId}
 * 只是管理员在检索实验室里手选的过滤条件，普通用户在问答接口里传任意 spaceId 就能把别人
 * 私有空间的切片召进 Prompt，引用还会直接带出原文片段。
 *
 * <p>ADMIN 角色不依赖本表（见 {@code SpaceAccessService}），否则新建一个空间，
 * 管理员自己反而看不见它。
 */
@Data
@TableName("kb_space_member")
public class KbSpaceMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
