package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.eventloop.GameSessionManager;
import com.aiuniverse.server.eventloop.TurnStateMachine;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-032 刀 2 · 工具上下文的装配面(Testcontainers 真 PG):只有 DataSource + Flyway + ObjectMapper + ArchetypeRegistry,
 * <b>没有</b> {@link GameSessionManager}(其 {@code @PostConstruct reloadFromStore()})、没有任何 {@link SessionStore}
 * (含 {@link JdbcSessionStore} 的启动收口)、没有 web 服务器。变异「改回扫全包」→ 本条红。
 */
@RequiresDocker
@Testcontainers
class StoreMigrationToolContextTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	@Test
	void toolContextIsNarrowAndHasNoSessionMachineryOrWebServer() throws Exception {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement st = c.createStatement()) {
			st.execute("CREATE DATABASE ctx");
		}
		String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/ctx";
		try (ConfigurableApplicationContext ctx = StoreMigrationTool.startContext(new String[] {
				"--spring.profiles.active=pg", "--spring.datasource.url=" + url,
				"--spring.datasource.username=" + pg.getUsername(), "--spring.datasource.password=" + pg.getPassword() })) {
			System.out.println("[store-migration] 工具上下文 bean 清单:\n" + StoreMigrationTool.beanNames(ctx));
			assertThat(ctx.getBeanNamesForType(GameSessionManager.class)).isEmpty();
			assertThat(ctx.getBeanNamesForType(SessionStore.class)).isEmpty();
			assertThat(ctx.getBeanNamesForType(JdbcSessionStore.class)).isEmpty();
			assertThat(ctx.getBeanNamesForType(TurnStateMachine.class)).isEmpty();
			assertThat(ctx.getBeanNamesForType(PgStartupReport.class)).isEmpty();
			assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
			assertThat(ctx.getBeanNamesForType(DataSource.class)).hasSize(1);
			assertThat(ctx.getBeanNamesForType(Flyway.class)).hasSize(1);
			assertThat(ctx.getBeanNamesForType(ObjectMapper.class)).hasSize(1);
			assertThat(ctx.getBeanNamesForType(ArchetypeRegistry.class)).hasSize(1);
			// Flyway 已在上下文启动时迁移:空库上表已建好
			assertThat(new org.springframework.jdbc.core.JdbcTemplate(ctx.getBean(DataSource.class))
					.queryForObject("SELECT count(*) FROM game_session", Long.class)).isZero();
		}
	}
}
