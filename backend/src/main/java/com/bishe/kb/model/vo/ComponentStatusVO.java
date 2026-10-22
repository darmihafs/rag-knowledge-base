package com.bishe.kb.model.vo;

import java.util.List;

/**
 * 单个核心组件的体检结果，供首页大屏的「系统核心组件健康状态」列表渲染。
 *
 * <p>状态取四个固定枚举串，前端按值上色，避免把中文文案当判断条件：
 * <ul>
 *   <li>{@code ONLINE} 正常可用；</li>
 *   <li>{@code DEGRADED} 能用但打了折扣（例如向量条目少于启用切片数，语义召回覆盖不全）；</li>
 *   <li>{@code OFFLINE} 该在线却不可用；</li>
 *   <li>{@code DISABLED} 由配置主动关掉，不算故障（如未开启向量化、mock 演示模式）。</li>
 * </ul>
 *
 * @param key      稳定标识（mysql / llm / embedding / vectorStore），前端可据此选图标
 * @param name     展示名
 * @param state    状态枚举串
 * @param endpoint 该组件的连接目标或模型名，无则 null
 * @param fields   明细键值对（连接池占用、维度、文件条目数等），按顺序展示
 * @param note     补充说明或异常原因，可为空
 */
public record ComponentStatusVO(
        String key,
        String name,
        String state,
        String stateCn,
        String endpoint,
        List<Field> fields,
        String note
) {

    public static final String ONLINE = "ONLINE";
    public static final String DEGRADED = "DEGRADED";
    public static final String OFFLINE = "OFFLINE";
    public static final String DISABLED = "DISABLED";

    /** 明细里的一行 */
    public record Field(String label, String value) {
    }

    /** 带上状态枚举构造，中文标签自动推导，免得两处写歪 */
    public static ComponentStatusVO of(String key, String name, String state, String endpoint,
                                       List<Field> fields, String note) {
        return new ComponentStatusVO(key, name, state, stateCn(state), endpoint, fields, note);
    }

    public static String stateCn(String state) {
        return switch (state) {
            case ONLINE -> "在线";
            case DEGRADED -> "降级";
            case OFFLINE -> "离线";
            case DISABLED -> "未启用";
            default -> "未知";
        };
    }
}
