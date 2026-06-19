package com.aiuniverse.server.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 思考开关单点适配(ADR-001 §5.2):从 bakeoff {@code _thinking_extra_body()} 移植的行为。
 * 各家非标参数只在这里翻译,不污染主流程。
 */
class ThinkingAdapterTest {

	private final ThinkingAdapter adapter = new ThinkingAdapter();

	private LlmProperties.Provider provider(String baseUrl, boolean thinking) {
		return new LlmProperties.Provider("label", baseUrl, "model", "KEY_ENV", thinking, 1000,
				new LlmProperties.Price(1.0, 0.02, 2.0));
	}

	@Test
	void deepseekNonThinkingSendsDisabled() {
		Map<String, Object> extra = adapter.extraBody(provider("https://api.deepseek.com", false));
		assertThat(extra).containsEntry("thinking", Map.of("type", "disabled"));
	}

	@Test
	void deepseekThinkingSendsEnabled() {
		Map<String, Object> extra = adapter.extraBody(provider("https://api.deepseek.com", true));
		assertThat(extra).containsEntry("thinking", Map.of("type", "enabled"));
	}

	@Test
	void dashscopeUsesEnableThinkingFlag() {
		Map<String, Object> extra = adapter.extraBody(provider("https://dashscope.aliyuncs.com/v1", true));
		assertThat(extra).containsEntry("enable_thinking", true);
	}

	@Test
	void unknownProviderSendsNoExtraBody() {
		assertThat(adapter.extraBody(provider("https://api.openai.com", false))).isEmpty();
	}
}
