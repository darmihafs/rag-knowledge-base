package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 问答反馈工单：一条记录 = 用户对某条 AI 回答的一次评价。
 *
 * <p>设计取向说明：{@code message_id} 指向被评价的 assistant 消息，但<b>不建外键</b>。
 * 删会话时消息会被物理清理，工单却必须留着——BadCase 的数量与问题类型分布是检索质量的
 * 历史证据，跟着消息一起消失就等于把评测依据丢了。因此列表侧对消息已不存在的工单
 * 统一显示「消息已清理」，而不是把工单级联删掉。
 */
@Data
@TableName("chat_feedback")
public class ChatFeedback {

    /** 点赞：回答有用 */
    public static final int RATING_UP = 1;
    /** 点踩：回答有问题，进入 BadCase 队列 */
    public static final int RATING_DOWN = -1;

    /** 待处理：新提交的踩赞都从这里起步，管理员尚未给结论 */
    public static final int STATUS_PENDING = 0;
    /** 已修复：管理员确认问题并按纠偏动作处理过 */
    public static final int STATUS_FIXED = 1;
    /** 已忽略：确认不是缺陷（如资料本身就没有该内容） */
    public static final int STATUS_IGNORED = 2;

    /**
     * BadCase 的四种归因，直接存中文：这一列既进筛选条件也直接展示给管理员，
     * 再套一层英文枚举码只会让前端多维护一份映射表。
     */
    public static final Set<String> ISSUE_TYPES = Set.of("检索漏检", "答案幻觉", "引用不准", "其他");
    /** 点踩时没选类型就按「其他」兜底，保证工单一定能被归类统计 */
    public static final String DEFAULT_ISSUE_TYPE = "其他";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被评价的 assistant 消息 ID（chat_message.id），消息被清理后仍保留原值用于溯源 */
    private Long messageId;

    private Long sessionId;

    /** 提交人，取值来自 JWT，不接受前端传入 */
    private Long userId;

    /** 1 点赞 / -1 点踩 */
    private Integer rating;

    /** 问题类型：检索漏检 / 答案幻觉 / 引用不准 / 其他；点赞时为空 */
    private String issueType;

    /** 用户补充说明 */
    private String comment;

    /** 0 待处理 / 1 已修复 / 2 已忽略 */
    private Integer status;

    /** 管理员处理备注 */
    private String adminNote;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
