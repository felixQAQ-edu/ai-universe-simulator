package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.zaxxer.hikari.HikariDataSource;

/**
 * ADR-032 刀 1 · 真库上的启动语义与连接池(Testcontainers 真 PG;真起一次整个应用,见 {@link PgAppLauncher})。
 * 每条用例各建一个独立的库,互不影响(其中一条会在迁移后把表改名)。
 *
 * <ul>
 *   <li>测试面 13(后半):Flyway 迁移<b>成功</b>之后,启动回载读表失败 → 拒启(不再「以空档启动」);</li>
 *   <li>测试面 14(D-5):池大小未设 = 5;用 env {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3} 覆盖时,
 *       启动日志与<b>正在运行的池</b>都等于 3 —— 用非默认值,防「打字面量」假绿;</li>
 *   <li>测试面 11(端到端):真 {@code DATABASE_URL}(密码含 @ : /,百分号编码)能连上,jdbc url 里没有密码。</li>
 * </ul>
 */
@RequiresDocker
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class PgStartupSemanticsTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	@TempDir
	Path storeDir;

	private static void admin(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement st = c.createStatement()) {
			st.execute(sql);
		}
	}

	private static String jdbcUrl(String db) {
		return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
	}

	private static Map<String, Object> springEnv(String db) throws SQLException {
		admin("CREATE DATABASE " + db);
		Map<String, Object> env = new HashMap<>();
		env.put("SPRING_PROFILES_ACTIVE", "pg");
		env.put("SPRING_DATASOURCE_URL", jdbcUrl(db));
		env.put("SPRING_DATASOURCE_USERNAME", pg.getUsername());
		env.put("SPRING_DATASOURCE_PASSWORD", pg.getPassword());
		return env;
	}

	private static HikariDataSource hikari(ConfigurableApplicationContext ctx) throws SQLException {
		return ctx.getBean(DataSource.class).unwrap(HikariDataSource.class);
	}

	@Test
	void flywayOkThenLoadAllFailsRefusesStartup(CapturedOutput out) throws Exception {
		Map<String, Object> env = springEnv("brk");
		env.put("SPRING_FLYWAY_LOCATIONS", "classpath:db/migration,classpath:db/test-break-after-migrate");
		Path save = storeDir.resolve("save-keep.json");
		Files.writeString(save, "{\"schemaVersion\":\"0.4\",\"world\":{}}");
		Files.setLastModifiedTime(save, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
		Map<String, List<Object>> before = PgAppLauncher.snapshotJsonFiles(storeDir);

		assertThatThrownBy(() -> PgAppLauncher.run(env, storeDir).close())
				.satisfies(t -> assertThat(PgAppLauncher.fullText(t)).contains("启动回载失败"));
		String log = out.getAll();
		assertThat(log).as("Flyway 确实迁移成功了(不是 Flyway 让它失败的)").contains("Successfully applied 2 migrations");
		assertThat(log).contains("[session-store:pg] 读取 game_session 失败 → 拒绝启动");
		assertThat(log).doesNotContain("[session-store:pg] 启动回载:载入").doesNotContain("[session-store] 落盘目录");
		assertThat(PgAppLauncher.snapshotJsonFiles(storeDir)).isEqualTo(before);
	}

	@Test
	void poolSizeDefaultsToFive(CapturedOutput out) throws Exception {
		try (ConfigurableApplicationContext ctx = PgAppLauncher.run(springEnv("pool_default"), storeDir)) {
			assertThat(ctx.getBean(PgStartupReport.class).maximumPoolSize()).isEqualTo(5);
			assertThat(hikari(ctx).getHikariConfigMXBean().getMaximumPoolSize()).as("正在运行的池").isEqualTo(5);
			assertThat(out.getAll()).contains("[pg-connection] 连接来源 = spring.datasource.*,连接池 maximum-pool-size = 5");
		}
	}

	@Test
	void poolSizeEnvOverrideIsTheFinalValue(CapturedOutput out) throws Exception {
		Map<String, Object> env = springEnv("pool_env");
		env.put("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "3");
		try (ConfigurableApplicationContext ctx = PgAppLauncher.run(env, storeDir)) {
			assertThat(ctx.getBean(PgStartupReport.class).maximumPoolSize()).isEqualTo(3);
			assertThat(hikari(ctx).getHikariConfigMXBean().getMaximumPoolSize()).as("正在运行的池").isEqualTo(3);
			assertThat(out.getAll()).contains("连接池 maximum-pool-size = 3").doesNotContain("maximum-pool-size = 5");
		}
	}

	@Test
	void realDatabaseUrlConnectsAndKeepsThePasswordOutOfTheUrl(CapturedOutput out) throws Exception {
		String secret = DatabaseUrlTest.SECRET; // 含 @ : /
		admin("CREATE ROLE \"al@ice\" LOGIN PASSWORD '" + secret + "'");
		admin("CREATE DATABASE dburl OWNER \"al@ice\"");
		Map<String, Object> env = Map.of("SPRING_PROFILES_ACTIVE", "pg", "DATABASE_URL", "postgres://al%40ice:"
				+ DatabaseUrlTest.SECRET_ENC + "@" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/dburl");
		try (ConfigurableApplicationContext ctx = PgAppLauncher.run(env, storeDir)) {
			HikariDataSource ds = hikari(ctx);
			assertThat(ds.getJdbcUrl()).doesNotContain(secret).doesNotContain(DatabaseUrlTest.SECRET_ENC)
					.endsWith("/dburl");
			assertThat(ds.getUsername()).isEqualTo("al@ice");
			assertThat(new JdbcTemplate(ds).queryForObject("SELECT current_user", String.class)).isEqualTo("al@ice");
			assertThat(out.getAll()).contains("[pg-connection] 来源 = DATABASE_URL,主机 = " + pg.getHost())
					.contains("库 = dburl").doesNotContain(secret).doesNotContain(DatabaseUrlTest.SECRET_ENC);
		}
	}
}
