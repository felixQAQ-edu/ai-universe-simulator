package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * ADR-025 刀 1 回滚保证:默认 profile 下<b>不起任何 DB</b>(无 DataSource、无 Flyway、无 JDBC 版 store),
 * 会话存储仍是 {@link FileSessionStore}。不需要 Docker —— JDBC / Flyway 依赖进了 classpath,
 * 靠 application.yml 的排除项让它们在默认 profile 下退场。
 */
@SpringBootTest
class DefaultProfileNoDatabaseTest {

	@Autowired
	ApplicationContext ctx;
	@Autowired
	SessionStore store;

	@Test
	void defaultProfileHasNoDatabaseAndKeepsFileStore() {
		assertThat(ctx.getBeanNamesForType(DataSource.class)).as("默认 profile 无 DataSource").isEmpty();
		assertThat(ctx.getBeanNamesForType(JdbcSessionStore.class)).isEmpty();
		assertThat(store).isInstanceOf(FileSessionStore.class);
	}
}
