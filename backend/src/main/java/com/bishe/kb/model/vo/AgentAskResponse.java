package com.bishe.kb.model.vo;

import java.util.List;

/** 工具记录是实际执行日志，不包含模型的隐藏推理；引用仅由服务端读取结果生成。 */
public record AgentAskResponse(
        String answer, List<ToolStep> steps, List<AskResponse.Citation> citations,
        long elapsedMillis, int modelCalls, StopReason stopReason, boolean mock
) {
    public enum StopReason {
        COMPLETED, STEP_LIMIT, TOOL_LIMIT, TIME_LIMIT, CANCELLED, MODEL_ERROR, NO_EVIDENCE
    }

    public record ToolStep(int step, String tool, String arguments, String observation,
                           boolean success, long elapsedMillis) {
    }
}
