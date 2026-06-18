package com.aiuniverse.server.llm;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 思考模式适配器(ADR-001 §5.2):把「是否思考」翻译成各家非标参数,经 {@code extra_body} 注入,
 * 收口在单点,不污染主流程。对应 bakeoff {@code client.py} 的 {@code _thinking_extra_body()}。
 *
 * <p>骨架占位:{@link MockLlmClient} 不调用本类;接真实 OpenAI 兼容 provider 时再按各家官方文档
 * 填具体参数(DeepSeek {@code thinking.type} / Qwen {@code enable_thinking} / GLM {@code thinking})。
 */
@Component
public class ThinkingAdapter {

	public Map<String, Object> extraBody(LlmProperties.Provider provider) {
		if (provider == null || !provider.thinking()) {
			return Map.of();
		}
		// TODO(接真实 provider): 按 base-url 分派各家思考开关参数,见 ADR-001 §5.2。
		throw new UnsupportedOperationException(
				"thinking 适配待接真实 provider 时填充(ADR-001 §5.2);骨架阶段所有 provider thinking=false");
	}
}
