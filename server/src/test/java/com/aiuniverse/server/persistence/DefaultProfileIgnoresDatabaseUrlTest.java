package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ADR-032 测试面 16 补:<b>默认 profile 下即使环境里有 {@code DATABASE_URL},也完全不读、不解析、不建连接</b>
 * (将来 MPG attach 往 secret 里写入它时,文件 profile 的线上实例不得被它触动)。真起一次整个应用;
 * 值故意写成一个解析必失败、且带独特密码的串 —— 只要被解析就会拒启。
 * 「不读」本身由 {@link PgConnectionEnvironmentPostProcessorTest#defaultProfileNeverReadsOrParsesDatabaseUrl} 用记录型属性源断言。
 */
@ExtendWith(OutputCaptureExtension.class)
class DefaultProfileIgnoresDatabaseUrlTest {

	@TempDir
	Path storeDir;

	@Test
	void defaultProfileStartsWithoutDataSourceDespiteDatabaseUrl(CapturedOutput out) {
		Map<String, Object> env = Map.of("DATABASE_URL", "postgres://alice:" + DatabaseUrlTest.SECRET_ENC + "@h:5432");
		try (ConfigurableApplicationContext ctx = PgAppLauncher.run(env, storeDir)) {
			assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
			assertThat(ctx.getBean(SessionStore.class)).isInstanceOf(FileSessionStore.class);
			assertThat(ctx.getBeanNamesForType(PgStartupReport.class)).isEmpty();
		}
		assertThat(out.getAll()).doesNotContain("[pg-connection]").doesNotContain(DatabaseUrlTest.SECRET_ENC)
				.contains("[session-store] 落盘目录");
	}
}
