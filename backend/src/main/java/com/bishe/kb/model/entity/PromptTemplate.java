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
 * 快捷提示词模板：聊天页「快捷指令」胶囊的数据源。
 *
 * <p>{@code promptContent} 里的 {@code {topic}} 是<b>给用户看的占位提醒</b>，后端不做变量替换：
 * 点击胶囊只是把整段前缀填进输入框并聚焦，用户补上主题词再发送。这样做的好处是
 * 模板可以随意改写而不需要任何渲染约定，也不会出现「替换失败留下半句话」的脏输出。
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {

    /** 启用：聊天页对所有登录用户可见 */
    public static final int ENABLED = 1;
    /** 下架：只在管理端可见 */
    public static final int DISABLED = 0;

    /**
     * 允许的模板分类，与 V6 种子数据一致。
     *
     * <p>分类是前端筛选页签的取值来源，因此必须闭合校验：管理员手打一个近义词（如「总结」）
     * 会让那条模板从所有页签里消失，比报错更难发现。
     */
    public static final Set<String> CATEGORIES = Set.of("学术总结", "概念解析", "对比分析", "自测出题");
    /** 未选分类时的兜底 */
    public static final String DEFAULT_CATEGORY = "概念解析";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称，同时是种子数据的判重键 */
    private String title;

    /** 分类：学术总结 / 概念解析 / 对比分析 / 自测出题 */
    private String category;

    /** 模板正文，含 {topic} 占位符 */
    private String promptContent;

    /** 一句话说明，前端展示在胶囊悬浮提示上 */
    private String description;

    /** 1 启用 / 0 禁用 */
    private Integer enabled;

    /** 排序权重，越小越靠前 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
