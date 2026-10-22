package com.bishe.kb.model.vo;

/**
 * 一轮历史对话的最小载体，用于把上下文喂给大模型。
 *
 * <p>不直接把 {@code ChatMessage} 实体传给问答服务，是为了避免
 * {@code com.bishe.kb.model.entity.ChatMessage} 与
 * {@code dev.langchain4j.data.message.ChatMessage} 在同一个类里撞名。
 *
 * @param role    user / assistant
 * @param content 消息正文
 */
public record ChatTurn(String role, String content) {

    public boolean isAssistant() {
        return "assistant".equals(role);
    }
}
