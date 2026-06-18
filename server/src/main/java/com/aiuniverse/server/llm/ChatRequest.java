package com.aiuniverse.server.llm;

/**
 * 给 {@link LlmClient} 的最小请求体。骨架阶段只承载一个 prompt 用于跑通流式通路;
 * 接真实 event-loop 时再扩成 messages 列表 + 目标 provider key + 思考开关等(见 ADR-001)。
 */
public record ChatRequest(String prompt) {
}
