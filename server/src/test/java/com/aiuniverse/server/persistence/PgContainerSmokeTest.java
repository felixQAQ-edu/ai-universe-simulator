package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-025 刀 1 步骤 0 门禁:CI 上 Testcontainers 能否真起 PostgreSQL。起容器 → {@code SELECT 1}。
 * 取证读的是 CI 日志里的容器启动行与 surefire 用例数,不是 job 绿(ADR-018 §4.14)。
 */
@RequiresDocker
class PgContainerSmokeTest {

	private static final Logger log = LoggerFactory.getLogger(PgContainerSmokeTest.class);

	static PostgreSQLContainer pg;

	@BeforeAll
	static void start() {
		pg = new PostgreSQLContainer("postgres:16-alpine");
		pg.start();
		log.info("[db-tests] PG 容器已起:image={} id={} jdbc={}", pg.getDockerImageName(),
				pg.getContainerId(), pg.getJdbcUrl());
	}

	@AfterAll
	static void stop() {
		if (pg != null) {
			pg.stop();
		}
	}

	@Test
	void selectOne() throws Exception {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT 1")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getInt(1)).isEqualTo(1);
		}
	}
}
