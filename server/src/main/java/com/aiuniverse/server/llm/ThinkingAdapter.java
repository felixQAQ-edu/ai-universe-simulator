package com.aiuniverse.server.llm;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 思考模式适配器(ADR-001 §5.2):把「是否思考」翻译成各家非标参数,经 {@code extra_body} 注入,
 * 收口在单点,不污染主流程。对应 bakeoff {@code client.py} 的 {@code _thinking_extra_body()}。
 *
 * <p>从 bakeoff {@code client.py} 的 {@code _thinking_extra_body()} 移植:按 {@code base-url} 分派,
 * 把布尔 {@code thinking} 翻成各家非标参数(DeepSeek {@code thinking.type} / Qwen {@code enable_thinking}
 * / GLM {@code thinking})。⚠️ 具体参数名以各家官方文档为准(沿用 bakeoff 的 VERIFY 占位约定),
 * 真实接通后据实回填。未识别的 provider 返回空表(走纯 OpenAI 标准参数)。
 */
@Component
public class ThinkingAdapter {

	public Map<String, Object> extraBody(LlmProperties.Provider provider) {
		if (provider == null) {
			return Map.of();
		}
		String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl();
		boolean thinking = provider.thinking();
		if (baseUrl.startsWith("https://api.deepseek.com")) {
			// DeepSeek:显式开/关思考(非思考也明确传 disabled,与 bakeoff 一致)。
			return Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
		}
		if (baseUrl.contains("dashscope")) { // 通义千问
			return Map.of("enable_thinking", thinking);
		}
		if (baseUrl.contains("bigmodel")) { // 智谱 GLM
			return Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
		}
		return Map.of();
	}
}
