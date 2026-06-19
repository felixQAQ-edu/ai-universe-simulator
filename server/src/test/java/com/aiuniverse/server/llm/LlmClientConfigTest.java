package com.aiuniverse.server.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.ObjectMapper;

/**
 * 配置驱动的实现选择:active=mock 用 {@link MockLlmClient}(默认/回退),
 * active 指向真 provider 用 {@link OpenAiCompatLlmClient};未知 active 则启动即报错。
 */
class LlmClientConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(TestConfig.class)
			.withPropertyValues(
					"aiuniverse.llm.providers.deepseek-v4-flash.label=DeepSeek V4-Flash",
					"aiuniverse.llm.providers.deepseek-v4-flash.base-url=https://api.deepseek.com",
					"aiuniverse.llm.providers.deepseek-v4-flash.model=deepseek-v4-flash",
					"aiuniverse.llm.providers.deepseek-v4-flash.api-key-env=DEEPSEEK_API_KEY",
					"aiuniverse.llm.providers.deepseek-v4-flash.thinking=false",
					"aiuniverse.llm.providers.deepseek-v4-flash.max-context=1000000",
					"aiuniverse.llm.providers.deepseek-v4-flash.price.input-cache-miss=1.0",
					"aiuniverse.llm.providers.deepseek-v4-flash.price.input-cache-hit=0.02",
					"aiuniverse.llm.providers.deepseek-v4-flash.price.output=2.0");

	@Test
	void mockActiveProducesMockClient() {
		runner.withPropertyValues("aiuniverse.llm.active=mock")
				.run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(MockLlmClient.class));
	}

	@Test
	void realProviderActiveProducesOpenAiCompatClient() {
		runner.withPropertyValues("aiuniverse.llm.active=deepseek-v4-flash")
				.run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(OpenAiCompatLlmClient.class));
	}

	@Test
	void unknownActiveFailsFast() {
		runner.withPropertyValues("aiuniverse.llm.active=nope")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@EnableConfigurationProperties(LlmProperties.class)
	@Import({ LlmClientConfig.class, ThinkingAdapter.class })
	static class TestConfig {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
