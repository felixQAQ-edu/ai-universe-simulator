package com.aiuniverse.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-032 刀 2 · 不需要 Docker 的半边:路径闸门(测试面 10)、非 pg profile 立即退出、源码级断言(测试面 3 / 豁免只在测试)。
 * 这些失败都发生在建任何连接之前 —— 参数里给的连接串指向一个不存在的端口,若走到建连就会以别的原因失败。
 */
class StoreMigrationToolGateTest {

	@TempDir
	Path tmp;

	private static final String[] DEAD_DB = { "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none",
			"--spring.datasource.username=u", "--spring.datasource.password=p" };

	private static String[] args(String verb, Path dir, String... extra) {
		return Stream.of(new String[] { verb, dir.toString(), "--spring.profiles.active=pg" }, DEAD_DB, extra)
				.flatMap(Stream::of).toArray(String[]::new);
	}

	private static String[] run(String[] args, MigrationPathGate gate) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int code = StoreMigrationTool.run(args, gate, StoreMigrationTool.Seams.DEFAULT,
				new PrintStream(buf, true, StandardCharsets.UTF_8));
		return new String[] { String.valueOf(code), buf.toString(StandardCharsets.UTF_8) };
	}

	private static Path repoRoot() {
		return RepoPathGuard.findRepoRoot(Path.of(""));
	}

	@Test
	void inputInsideTheRepoIsRefusedBeforeAnyConnection() {
		String[] r = run(args("import", repoRoot().resolve("server/data")), MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("导入源目录路径在仓库目录之内");
	}

	@Test
	void exportTargetInsideTheRepoIsRefused() {
		String[] r = run(args("export", repoRoot().resolve("server/target/out-never")), MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("导出目标目录路径在仓库目录之内");
		assertThat(Files.exists(repoRoot().resolve("server/target/out-never"))).isFalse();
	}

	@Test
	void reportFileInsideTheRepoIsRefused() throws IOException {
		Path src = Files.createDirectories(tmp.resolve("src"));
		String[] r = run(args("import", src, "--report=" + repoRoot().resolve("server/target/report.txt")),
				MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("报告文件路径在仓库目录之内");
	}

	@Test
	void repoPathIsRefusedEvenWhenItIsWrittenRelativeAndUnnormalized() throws IOException {
		Path sneaky = tmp.resolve("../" + tmp.getFileName() + "/../../" ).resolve(repoRoot().toString().substring(1));
		String[] r = run(args("import", sneaky), MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("在仓库目录之内");
	}

	@Test
	void pathsUnderAWebRootAreRefused() throws IOException {
		Path webRoot = Files.createDirectories(tmp.resolve("static"));
		Path src = Files.createDirectories(webRoot.resolve("saves"));
		String[] r = run(args("import", src), MigrationPathGate.strictWithWebRoots(List.of(webRoot)));
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("位于 static web 根之下");
		String[] ex = run(args("export", webRoot.resolve("out")), MigrationPathGate.strictWithWebRoots(List.of(webRoot)));
		assertThat(ex[0]).isEqualTo("1");
		assertThat(Files.exists(webRoot.resolve("out"))).isFalse();
	}

	@Test
	void notPgProfileExitsOneBeforeConnecting() throws IOException {
		Path src = Files.createDirectories(tmp.resolve("src"));
		String[] noPg = Stream.of(new String[] { "import", src.toString() }, DEAD_DB).flatMap(Stream::of)
				.toArray(String[]::new);
		String[] r = run(noPg, MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("必须在 pg profile 下运行").doesNotContain("Connection");
	}

	@Test
	void unreachableDatabaseExitsOneWithoutLeakingThePassword() throws IOException {
		Path src = Files.createDirectories(tmp.resolve("src"));
		String[] a = Stream.of(new String[] { "import", src.toString(), "--spring.profiles.active=pg",
				"--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none", "--spring.datasource.username=u",
				"--spring.datasource.password=S3cret-PW" }).flatMap(Stream::of).toArray(String[]::new);
		String[] r = run(a, MigrationPathGate.strict());
		assertThat(r[0]).isEqualTo("1");
		assertThat(r[1]).contains("上下文启动失败").doesNotContain("S3cret-PW");
	}

	@Test
	void usageErrorExitsOne() {
		assertThat(run(new String[] { "frobnicate" }, MigrationPathGate.strict())[0]).isEqualTo("1");
	}

	// ── 源码级断言 ─────────────────────────────────────────────────────

	private static String source(String name) throws IOException {
		return Files.readString(Path.of("src/main/java/com/aiuniverse/server/persistence/" + name));
	}

	/** 测试面 3:三分判据唯一一份在 SessionDocument;导入不另写 world 判据、不复制 SAFE_SAVE_ID 正则、不 encode。 */
	@Test
	void importerUsesTheSharedJudgementAndNeverReEncodes() throws IOException {
		String src = source("SessionImporter.java");
		assertThat(src).contains("SessionDocument.isSaveDocument(").contains("SessionDocument.decode(")
				.contains("FileSessionStore.SAFE_SAVE_ID");
		assertThat(src).doesNotContain("\"world\"").doesNotContain("Pattern.compile").doesNotContain("SessionDocument.encode")
				.doesNotContain("writeValueAsString");
	}

	/** 豁免只能由测试调用:src/main 里除定义处之外没有任何地方提到豁免工厂。 */
	@Test
	void theTestExemptionIsNeverCalledFromMainCode() throws IOException {
		long uses;
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			uses = files.filter(p -> p.toString().endsWith(".java")).mapToLong(p -> {
				try {
					return Files.readString(p).split("forSyntheticFixturesInTests\\(", -1).length - 1;
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}).sum();
		}
		assertThat(uses).as("只有 MigrationPathGate 里的定义那一处").isEqualTo(1);
		assertThat(source("StoreMigrationTool.java")).contains("MigrationPathGate.strict()")
				.doesNotContain("forSyntheticFixturesInTests");
	}
}
