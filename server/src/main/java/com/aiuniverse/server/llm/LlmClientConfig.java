package com.aiuniverse.server.llm;

import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code aiuniverse.llm.active} 选定唯一的 {@link LlmClient} 实现(ADR-001:换 provider 只改配置表)。
 *
 * <ul>
 *   <li>{@code active} 缺省 / 空 / {@code mock} → {@link MockLlmClient}(默认 + 回退,不读任何 key、不发真实请求);
 *   <li>{@code active} 指向配置表里的真 provider → {@link OpenAiCompatLlmClient}(读环境变量取 key);
 *   <li>{@code active} 指向表里不存在的 key → 启动即失败(fail-fast,免得运行期才暴露配置错)。
 * </ul>
 *
 * <p>用工厂 {@code @Bean} 单点决策(而非每个实现各挂 {@code @Component} + 条件注解),意图最直白,
 * 也避免「两个 LlmClient bean」歧义。web 层照旧注入 {@link LlmClient} 接口,一行不动。
 */
@Configuration
public class LlmClientConfig {

	@Bean
	LlmClient llmClient(LlmProperties properties, ThinkingAdapter thinkingAdapter, ObjectMapper mapper) {
		String active = properties.active();
		if (active == null || active.isBlank() || "mock".equals(active)) {
			return new MockLlmClient(properties);
		}
		LlmProperties.Provider provider = properties.providers() == null ? null
				: properties.providers().get(active);
		if (provider == null) {
			throw new IllegalStateException("aiuniverse.llm.active=" + active
					+ " 不在 providers 配置表中;可用 = "
					+ (properties.providers() == null ? "[]" : properties.providers().keySet())
					+ "(或设为 mock 回退)");
		}
		return new OpenAiCompatLlmClient(provider, thinkingAdapter, mapper, System::getenv);
	}
}
