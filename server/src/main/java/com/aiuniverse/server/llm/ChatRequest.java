package com.aiuniverse.server.llm;

/**
 * 给 {@link LlmClient} 的最小请求体。{@code prompt} 是已拼好的单条提示;{@code jsonObject} 为 true 时
 * 请求 provider 开 {@code response_format: json_object}(OpenAI 兼容)——event-loop 主调用<b>不开</b>
 * (输出是「叙事 + 哨兵 + 尾巴」非纯 JSON,ADR-006 §4.3),仅<b>修复发</b>开回 json_object(规格 §6.4)。
 */
public record ChatRequest(String prompt, boolean jsonObject) {

	/** 默认不开 json_object(主调用 / 骨架冒烟用)。 */
	public ChatRequest(String prompt) {
		this(prompt, false);
	}
}
