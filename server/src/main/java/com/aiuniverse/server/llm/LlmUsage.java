package com.aiuniverse.server.llm;

/**
 * 一次 LLM 调用的 token 用量(纯观测,ADR-002 成本闸门的程序内读数来源;本身不做任何成本计算/限流)。
 *
 * <p>字段对应 OpenAI 兼容流式响应最后一个 chunk 的 {@code usage} 块;<b>缺失的字段记 -1</b>
 * (DeepSeek 方言偶有出入,如录制样本缺 {@code total_tokens}、多 {@code prompt_cache_*} —— 只透传
 * 存在的标准字段,绝不猜、不自行推算)。
 */
public record LlmUsage(long promptTokens, long completionTokens, long totalTokens) {

	/** 供日志用的单行展示,缺失字段显示 {@code -}。 */
	public String display() {
		return "prompt=" + fmt(promptTokens) + " completion=" + fmt(completionTokens) + " total=" + fmt(totalTokens);
	}

	private static String fmt(long v) {
		return v < 0 ? "-" : String.valueOf(v);
	}
}
