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
 * 聊天会话：前端左侧历史列表里的一项，一个会话下的多轮问答共享上下文。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    /** 新建会话时的默认标题，首次提问后会被问题前 15 字覆盖 */
    public static final String DEFAULT_TITLE = "新对话";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID：会话按用户隔离，普通用户只能看到/操作自己的会话 */
    private Long userId;

    private String title;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 最后活跃时间，会话列表按它倒序；每轮问答都会刷新 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
