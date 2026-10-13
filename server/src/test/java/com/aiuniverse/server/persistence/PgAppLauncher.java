package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.aiuniverse.server.ServerApplication;

/**
 * ADR-032 刀 1 测试工具:<b>真起一次整个应用</b>(与 {@code java -jar app.jar} 同一个入口类、同一套自动配置),
 * 环境变量用一张假的系统环境表替换 —— 走的是 Boot 自己的 {@link SystemEnvironmentPropertySource} 绑定路径,
 * 即生产里 {@code SPRING_PROFILES_ACTIVE} / {@code DATABASE_URL} / {@code SPRING_DATASOURCE_*} 被读到的同一条路。
 * 不用 {@code @SpringBootTest}:启动失败本身就是被测对象,而它会把失败变成整个测试类的上下文错误。
 */
final class PgAppLauncher {

	private PgAppLauncher() {
	}

	/** 以给定的「系统环境变量」启动;存档目录 / 端口走命令行参数(与 fly.toml 里的 env 无关,只是隔离测试)。 */
	static ConfigurableApplicationContext run(Map<String, Object> fakeEnv, Path storeDir) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						fakeEnv));
		return new SpringApplicationBuilder(ServerApplication.class).environment(env).run("--server.port=0",
				"--aiuniverse.session.store-dir=" + storeDir.toAbsolutePath());
	}

	/** 异常链的全部文本(含每一层 message 与栈),用来断言「不含密码」。 */
	static String fullText(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		return w.toString();
	}

	/** 存档目录下直接的 *.json:文件名 → (字节数, mtime, 内容);用来证明启动失败没有读写它们。 */
	static Map<String, List<Object>> snapshotJsonFiles(Path dir) throws IOException {
		Map<String, List<Object>> out = new TreeMap<>();
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
				FileTime mtime = Files.getLastModifiedTime(p);
				out.put(p.getFileName().toString(),
						new ArrayList<>(List.of(Files.size(p), mtime.toMillis(), Files.readString(p))));
			}
		}
		return out;
	}
}
