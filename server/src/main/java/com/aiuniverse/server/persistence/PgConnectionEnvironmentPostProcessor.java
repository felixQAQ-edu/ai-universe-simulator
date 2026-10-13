package com.aiuniverse.server.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * {@code pg} profile 的连接来源(ADR-032 §4.2):在 DataSource 创建之前决定用哪一个来源,并开机说清现值。
 *
 * <ul>
 *   <li><b>非 {@code pg} profile → 立即返回</b>:不读、不解析 {@code DATABASE_URL},不建任何连接
 *       (防将来 MPG attach 往 secret 里写入 {@code DATABASE_URL} 时误触文件 profile 的线上实例)。</li>
 *   <li>{@code spring.datasource.url} 已设 → <b>完全不读 {@code DATABASE_URL}</b>(不按字段混用两个来源)。</li>
 *   <li>未设且 {@code DATABASE_URL} 存在 → 解析为 url / username / password 三个属性,放在最高优先级的属性源里
 *       (三个一起覆盖,同样不与 {@code SPRING_DATASOURCE_USERNAME/PASSWORD} 混用)。密码只进 password 属性,不进 url。</li>
 *   <li>两者都没有 → 只打一行说明,不在这里抛:若也没有别的连接注入(测试的 {@code @ServiceConnection}),
 *       DataSource 自动配置会以「url 未指定」失败,应用拒启(ADR-032 D-1)。</li>
 * </ul>
 *
 * <p><b>Spring Boot 4.1 的接口与注册方式(实测核对,不凭记忆)</b>:接口是 {@code org.springframework.boot.EnvironmentPostProcessor}
 * (4.1 的 {@code spring-boot-4.1.0.jar} 里旧的 {@code org.springframework.boot.env.EnvironmentPostProcessor} 标了 {@code @Deprecated});
 * 注册键同名,写在 {@code META-INF/spring.factories}(4.1 自带的五个 EPP 就登记在该键下)。构造器注入
 * {@link DeferredLogFactory}:EPP 跑在日志系统初始化之前,延迟日志在应用就绪或启动失败时补打出来。
 * 顺序取最低优先级 —— 必须在 {@code ConfigDataEnvironmentPostProcessor} 之后,才看得见 yml 与激活的 profile。
 */
public class PgConnectionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PG_PROFILE = "pg";
	static final String DATABASE_URL = "DATABASE_URL";
	static final String DATASOURCE_URL = "spring.datasource.url";
	/** 记下这次启动选了哪个来源,供 {@link PgStartupReport} 再打一次(连同池大小)。 */
	static final String SOURCE_MARKER = "aiuniverse.pg.connection-source";
	static final String PROPERTY_SOURCE_NAME = "aiuniverse-pg-database-url";

	static final String SOURCE_SPRING = "spring.datasource.*";
	static final String SOURCE_DATABASE_URL = "DATABASE_URL";
	static final String SOURCE_NONE = "未配置(spring.datasource.url 与 DATABASE_URL 皆无)";

	private final Log log;

	public PgConnectionEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(PgConnectionEnvironmentPostProcessor.class);
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
		if (!env.acceptsProfiles(Profiles.of(PG_PROFILE))) {
			return; // 默认 profile:DATABASE_URL 一个字节都不读
		}
		Map<String, Object> props = new LinkedHashMap<>();
		String springUrl = env.getProperty(DATASOURCE_URL);
		if (StringUtils.hasText(springUrl)) {
			props.put(SOURCE_MARKER, SOURCE_SPRING);
			String[] d = DatabaseUrl.describeJdbcUrl(springUrl);
			log.info(d == null
					? "[pg-connection] 来源 = " + SOURCE_SPRING + ";url 无法解析出主机 / 端口 / 库名(不回显原值)"
					: "[pg-connection] 来源 = " + SOURCE_SPRING + ",主机 = " + d[0] + ",端口 = " + d[1] + ",库 = " + d[2]);
		} else {
			String raw = env.getProperty(DATABASE_URL);
			if (StringUtils.hasText(raw)) {
				DatabaseUrl parsed;
				try {
					parsed = DatabaseUrl.parse(raw);
				} catch (DatabaseUrl.InvalidDatabaseUrlException e) {
					log.error("[pg-connection] 来源 = " + SOURCE_DATABASE_URL + ";" + e.getMessage() + " → 拒绝启动(ADR-032 D-1)");
					throw e;
				}
				props.put(DATASOURCE_URL, parsed.jdbcUrl());
				props.put("spring.datasource.username", parsed.username());
				props.put("spring.datasource.password", parsed.password());
				props.put(SOURCE_MARKER, SOURCE_DATABASE_URL);
				log.info("[pg-connection] 来源 = " + SOURCE_DATABASE_URL + ",主机 = " + parsed.host() + ",端口 = "
						+ parsed.port() + ",库 = " + parsed.database());
			} else {
				props.put(SOURCE_MARKER, SOURCE_NONE);
				log.info("[pg-connection] 来源 = " + SOURCE_NONE
						+ ";若也没有别的连接注入,DataSource 将以「url 未指定」创建失败 → 拒绝启动(ADR-032 D-1)");
			}
		}
		env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
	}
}
