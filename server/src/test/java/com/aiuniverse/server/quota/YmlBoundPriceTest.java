package com.aiuniverse.server.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.aiuniverse.server.llm.LlmProperties;
import com.aiuniverse.server.llm.LlmUsage;

import tools.jackson.databind.ObjectMapper;

/**
 * 2026-10-06 模型 ID 迁移(F-036)+ 高峰档单价:单价从 <b>application.yml 实际绑定的值</b>读,
 * 而不是在测试里再写一份字面量 —— 否则 yml 与测试各说各话,改了 yml 测试照样绿。
 *
 * <p>绑定走生产同一条路径({@link YamlPropertySourceLoader} + {@link Binder},目标是真的
 * {@link LlmProperties} 记录),只把 {@code active} 覆盖成真实 provider(yml 里默认 mock)。
 */
class YmlBoundPriceTest {

	/** 前缀取自记录自己的注解(同 InitPropertiesEnvBindingTest 的理由:写死字符串则注解改名照样绿)。 */
	private static final String PREFIX = LlmProperties.class.getAnnotation(ConfigurationProperties.class).value();
	private static final String PROVIDER_KEY = "deepseek-v4-flash";

	@TempDir
	Path tmp;

	private static LlmProperties boundFromYml() throws Exception {
		List<PropertySource<?>> yml = new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"));
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("override",
				Map.of(PREFIX + ".active", PROVIDER_KEY)));
		yml.forEach(env.getPropertySources()::addLast);
		return Binder.get(env).bindOrCreate(PREFIX, LlmProperties.class);
	}

	@Test
	void costCnyUsesThePriceBoundFromYml() throws Exception {
		LlmProperties llm = boundFromYml();
		LlmProperties.Price p = llm.providers().get(PROVIDER_KEY).price();
		QuotaService q = new QuotaService(new QuotaProperties(6, 175, 10, 300), llm, tmp.toString(),
				new ObjectMapper());

		// hit 2048 / miss 2100 / completion 500(ADR-016 线上真实形态)。
		double expected = (2048 * p.inputCacheHit() + 2100 * p.inputCacheMiss() + 500 * p.output()) / 1_000_000.0;
		assertThat(q.costCny(new LlmUsage(4148, 500, 4648, 2048, 2100))).isEqualTo(expected);
	}

	@Test
	void ymlCarriesV41FlashIdAndPeakTierPrice() throws Exception {
		// 这一条才钉「yml 里到底写的是什么」:模型 ID 与高峰档单价(Felix 2026-10-06 核实)。
		LlmProperties.Provider provider = boundFromYml().providers().get(PROVIDER_KEY);
		assertThat(provider.model()).isEqualTo("deepseek-flash");
		assertThat(provider.thinking()).isFalse();
		assertThat(provider.price()).isEqualTo(new LlmProperties.Price(2.0, 0.04, 8.0));
	}
}
