package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息：一问一答各一行，assistant 行的 citations 存 JSON 数组字符串。
 *
 * <p>不做逻辑删除：会话被删时按 session_id 物理清理，避免孤儿数据无限堆积。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** user=提问，assistant=回答，取值见 {@link #ROLE_USER} / {@link #ROLE_ASSISTANT} */
    private String role;

    /** 消息正文；assistant 存的是流式结束后的完整回答 */
    private String content;

    /** 引用来源 JSON 数组，仅 assistant 有值 */
    private String citations;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
