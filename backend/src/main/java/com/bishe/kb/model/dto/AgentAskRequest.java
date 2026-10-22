package com.bishe.kb.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 一次无状态智能体任务；maxSteps 限制模型轮数，不允许客户端解除预算。 */
public record AgentAskRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题不能超过 1000 字") String question,
        @Positive(message = "空间 ID 必须为正数") Long spaceId,
        @Min(value = 1, message = "最多轮数至少为 1")
        @Max(value = 5, message = "最多轮数不能超过 5") Integer maxSteps,
        /**
         * 可选的任务 ID，由客户端生成（UUID），用于运行中取消。
         *
         * <p>不传时服务端自动生成；流式接口会在 meta 事件里回传。
         * 只允许字母、数字、下划线和短横线，避免把任意字符串带进日志或 URL。
         */
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "任务 ID 必须为 8~64 位字母、数字、下划线或短横线")
        String taskId
) {
}
