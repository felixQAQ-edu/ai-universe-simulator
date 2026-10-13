package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * ADR-032 测试面 11–12 · 连接来源的选择(EPP 本身;不需要 Docker)。端到端的「真起一次应用」见 {@link PgStartupSemanticsTest}。
 *
 * <p>「不读 {@code DATABASE_URL}」用一个<b>记录被问过哪些键</b>的属性源来断言 —— 只看结果对不对,
 * 证明不了「没去读」(读了但没用,结果一样)。
 */
class PgConnectionEnvironmentPostProcessorTest {

	static final String SECRET = DatabaseUrlTest.SECRET;
	static final String SECRET_ENC = DatabaseUrlTest.SECRET_ENC;

	private final List<String> logLines = new ArrayList<>();

	/** 一个属性源:记下被 getProperty 问过的每个键。 */
	static final class RecordingSource extends EnumerablePropertySource<Map<String, Object>> {
		final List<String> asked = new ArrayList<>();

		RecordingSource(Map<String, Object> m) {
			super("recording", m);
		}

		@Override
		public Object getProperty(String name) {
			asked.add(name);
			return getSource().get(name);
		}

		@Override
		public String[] getPropertyNames() {
			return getSource().keySet().toArray(String[]::new);
		}
	}

	private DeferredLogFactory capture() {
		Log log = (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] { Log.class },
				(p, m, args) -> {
					if (m.getName().startsWith("is")) {
						return true;
					}
					if (args != null && args.length > 0) {
						logLines.add(m.getName() + ": " + args[0]);
					}
					return null;
				});
		return supplier -> log;
	}

	private StandardEnvironment env(RecordingSource src, String... profiles) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(src);
		env.setActiveProfiles(profiles);
		return env;
	}

	private void run(StandardEnvironment env) {
		new PgConnectionEnvironmentPostProcessor(capture()).postProcessEnvironment(env, new SpringApplication());
	}

	@Test
	void defaultProfileNeverReadsOrParsesDatabaseUrl() {
		// 值故意非法:一旦被解析就会抛 —— 不抛 + 没被问过,两条一起证明「完全不读」
		RecordingSource src = new RecordingSource(Map.of("DATABASE_URL", "garbage://" + SECRET));
		StandardEnvironment env = env(src);
		run(env);
		assertThat(src.asked).doesNotContain("DATABASE_URL");
		assertThat(env.getPropertySources().contains(PgConnectionEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
		assertThat(env.getProperty("spring.datasource.url")).isNull();
		assertThat(logLines).isEmpty();
	}

	@Test
	void springDatasourceUrlWinsAndDatabaseUrlIsNeverRead() {
		RecordingSource src = new RecordingSource(Map.of("spring.datasource.url", "jdbc:postgresql://a.host:5433/main",
				"spring.datasource.password", "spring-pw", "DATABASE_URL",
				"postgres://other:" + SECRET_ENC + "@b.host:5432/other"));
		StandardEnvironment env = env(src, "pg");
		run(env);
		assertThat(src.asked).doesNotContain("DATABASE_URL");
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://a.host:5433/main");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("spring-pw");
		assertThat(env.getProperty(PgConnectionEnvironmentPostProcessor.SOURCE_MARKER))
				.isEqualTo(PgConnectionEnvironmentPostProcessor.SOURCE_SPRING);
		assertThat(logLines).singleElement().asString().contains("来源 = spring.datasource.*", "主机 = a.host",
				"端口 = 5433", "库 = main");
	}

	@Test
	void databaseUrlIsParsedIntoThreePropertiesWhenSpringUrlIsAbsent() {
		RecordingSource src = new RecordingSource(new HashMap<>(Map.of("DATABASE_URL",
				"postgres://alice:" + SECRET_ENC + "@db.internal:5433/wanjie?sslmode=disable",
				// 不按字段混用:即便另有 SPRING_DATASOURCE_USERNAME/PASSWORD,也被 DATABASE_URL 的三件一起覆盖
				"spring.datasource.username", "stray-user", "spring.datasource.password", "stray-pw")));
		StandardEnvironment env = env(src, "pg");
		run(env);
		assertThat(env.getProperty("spring.datasource.url"))
				.isEqualTo("jdbc:postgresql://db.internal:5433/wanjie?sslmode=disable");
		assertThat(env.getProperty("spring.datasource.username")).isEqualTo("alice");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo(SECRET);
		assertThat(env.getProperty(PgConnectionEnvironmentPostProcessor.SOURCE_MARKER))
				.isEqualTo(PgConnectionEnvironmentPostProcessor.SOURCE_DATABASE_URL);
		assertThat(logLines).singleElement().asString()
				.contains("来源 = DATABASE_URL", "主机 = db.internal", "端口 = 5433", "库 = wanjie")
				.doesNotContain(SECRET).doesNotContain(SECRET_ENC).doesNotContain("alice");
	}

	@Test
	void neitherSourceLeavesUrlUnsetAndSaysSo() {
		RecordingSource src = new RecordingSource(Map.of());
		StandardEnvironment env = env(src, "pg");
		run(env);
		assertThat(env.getProperty("spring.datasource.url")).isNull();
		assertThat(env.getProperty(PgConnectionEnvironmentPostProcessor.SOURCE_MARKER))
				.isEqualTo(PgConnectionEnvironmentPostProcessor.SOURCE_NONE);
		assertThat(logLines).singleElement().asString().contains("拒绝启动");
	}

	@Test
	void invalidDatabaseUrlRefusesStartupWithoutLeakingThePassword() {
		RecordingSource src = new RecordingSource(Map.of("DATABASE_URL", "postgres://u:" + SECRET_ENC + "@h:5432"));
		StandardEnvironment env = env(src, "pg");
		assertThatThrownBy(() -> run(env)).isInstanceOf(DatabaseUrl.InvalidDatabaseUrlException.class)
				.satisfies(t -> assertThat(t.getMessage()).doesNotContain(SECRET).doesNotContain(SECRET_ENC));
		assertThat(String.join("\n", logLines)).contains("拒绝启动").doesNotContain(SECRET).doesNotContain(SECRET_ENC);
	}
}
