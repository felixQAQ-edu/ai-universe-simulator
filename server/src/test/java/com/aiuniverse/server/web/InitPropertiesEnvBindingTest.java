package com.aiuniverse.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * ADR-030 实施刀 2 第 6 项:开局准入容量的 env 写法到底哪一个生效。
 *
 * <p>实测结论:规范写法 {@code AIUNIVERSE_INIT_MAXCONCURRENT} 与 ADR 原写的 {@code AIUNIVERSE_INIT_MAX_CONCURRENT}
 * <b>都生效</b> —— 后者走 Spring Boot 的 legacy 下划线映射。用的是 Boot 自己的 {@link SystemEnvironmentPropertySource}
 * + {@link Binder},即生产启动时的同一条绑定路径,绑定目标是真的 {@link InitProperties} 记录。
 */
class InitPropertiesEnvBindingTest {

	/** 前缀取自记录自己的注解 —— 写死字符串的话,注解前缀被改名这条测试照样是绿的(变异实测过)。 */
	private static final String PREFIX = InitProperties.class.getAnnotation(ConfigurationProperties.class).value();

	@ParameterizedTest
	@ValueSource(strings = { "AIUNIVERSE_INIT_MAXCONCURRENT", "AIUNIVERSE_INIT_MAX_CONCURRENT" })
	void envSpellingBindsInitMaxConcurrent(String envName) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(envName, "1")));
		InitProperties props = Binder.get(env).bindOrCreate(PREFIX, InitProperties.class);
		assertThat(props.maxConcurrent()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "AIUNIVERSE_INIT_MAX", "AIUNIVERSE_INITMAXCONCURRENT" })
	void unrelatedSpellingFallsBackToDefault(String envName) {
		// 对照:写错的名字不绑定、落回 @DefaultValue —— 证明上面那条不是「什么都绑得上」。
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(envName, "1")));
		InitProperties props = Binder.get(env).bindOrCreate(PREFIX, InitProperties.class);
		assertThat(props.maxConcurrent()).isEqualTo(4);
	}
}
