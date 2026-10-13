package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * ADR-032 测试面 13 · 启动语义 D-1 = 拒启:{@code pg} profile、数据库不可达 → 整个应用启动失败
 * ({@code SpringApplication.run} 抛出;{@code main} 不接 → JVM 非零退出)。不需要 Docker:连的是一个刚关掉的本机端口。
 *
 * <p>每条断言四件事:① 启动失败;② 原因进了日志 / 异常,且<b>不含密码</b>(独特字面量 {@link #SECRET});
 * ③ 没有装配 {@link FileSessionStore}(启动日志无 {@code [session-store] 落盘目录} 行);
 * ④ 存档目录下既有的 {@code *.json} 文件个数、字节、mtime 全部不变。
 *
 * <p>今天让它拒启的是 Flyway(启动即 migrate,拿不到连接就抛);{@link JdbcSessionStore#loadAll} 在 Flyway 之后
 * 也会因读不到表而抛(见 {@link PgStartupSemanticsTest#flywayOkThenLoadAllFailsRefusesStartup})—— 两层,变异各打一层。
 */
@ExtendWith(OutputCaptureExtension.class)
class PgStartupRefusalTest {

	static final String SECRET = DatabaseUrlTest.SECRET;
	static final String SECRET_ENC = DatabaseUrlTest.SECRET_ENC;

	@TempDir
	Path storeDir;

	private Map<String, List<Object>> seedSaves() throws IOException {
		Path save = storeDir.resolve("save-keep.json");
		Files.writeString(save, "{\"schemaVersion\":\"0.4\",\"world\":{}}");
		Files.setLastModifiedTime(save, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
		Path quota = storeDir.resolve("quota-2026-10.json");
		Files.writeString(quota, "{\"month\":\"2026-10\",\"spentCny\":1.0}");
		Files.setLastModifiedTime(quota, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
		return PgAppLauncher.snapshotJsonFiles(storeDir);
	}

	/** 一个本机上刚刚关掉、没有人在听的端口 → 连接被拒(不是挂起,所以快)。 */
	private static int closedPort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void assertRefused(Map<String, Object> env, CapturedOutput out, String expectedSourceLine)
			throws IOException {
		Map<String, List<Object>> before = seedSaves();
		assertThatThrownBy(() -> PgAppLauncher.run(env, storeDir).close()).satisfies(t -> {
			String text = PgAppLauncher.fullText(t);
			assertThat(text).as("异常链不含密码").doesNotContain(SECRET).doesNotContain(SECRET_ENC);
		});
		String log = out.getAll();
		assertThat(log).as("开机说清来源").contains(expectedSourceLine);
		// 失败原因由 Boot 打出:有 FailureAnalyzer 认领的走「APPLICATION FAILED TO START」报告,其余走「Application run failed」+ 栈
		assertThat(log).as("原因进了日志").containsAnyOf("Application run failed", "APPLICATION FAILED TO START");
		assertThat(log).as("日志不含密码").doesNotContain(SECRET).doesNotContain(SECRET_ENC);
		assertThat(log).as("没有回退到文件存储:FileSessionStore 未装配").doesNotContain("[session-store] 落盘目录")
				.doesNotContain("[session-store] 启动回载");
		assertThat(PgAppLauncher.snapshotJsonFiles(storeDir)).as("存档目录下的文件未被读写").isEqualTo(before);
	}

	@Test
	void unreachableDatabaseViaSpringDatasourceRefusesStartup(CapturedOutput out) throws IOException {
		assertRefused(Map.of("SPRING_PROFILES_ACTIVE", "pg", "SPRING_DATASOURCE_URL",
				"jdbc:postgresql://127.0.0.1:" + closedPort() + "/wanjie", "SPRING_DATASOURCE_USERNAME", "alice",
				"SPRING_DATASOURCE_PASSWORD", SECRET), out, "[pg-connection] 来源 = spring.datasource.*");
	}

	@Test
	void unreachableDatabaseViaDatabaseUrlRefusesStartup(CapturedOutput out) throws IOException {
		assertRefused(Map.of("SPRING_PROFILES_ACTIVE", "pg", "DATABASE_URL",
				"postgres://alice:" + SECRET_ENC + "@127.0.0.1:" + closedPort() + "/wanjie"), out,
				"[pg-connection] 来源 = DATABASE_URL,主机 = 127.0.0.1");
	}

	@Test
	void noConnectionSourceAtAllRefusesStartup(CapturedOutput out) throws IOException {
		assertRefused(Map.of("SPRING_PROFILES_ACTIVE", "pg"), out, "[pg-connection] 来源 = 未配置");
	}

	@Test
	void invalidDatabaseUrlRefusesStartup(CapturedOutput out) throws IOException {
		assertRefused(Map.of("SPRING_PROFILES_ACTIVE", "pg", "DATABASE_URL", "postgres://alice:" + SECRET_ENC + "@h:5432"),
				out, "DATABASE_URL 无法解析");
	}
}
