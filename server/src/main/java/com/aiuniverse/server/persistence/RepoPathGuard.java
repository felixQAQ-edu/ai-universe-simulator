package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「真实数据的输入 / 输出不得落在仓库目录之内」这一道闸门(ADR-031 刀 5a 立、ADR-032 刀 1 迁到 {@code src/main})。
 *
 * <p>原住 {@code src/test} 的 {@code TraceFixtureConverter}(夹具转换)并被 {@code TraceRecheck}(轨迹核读)共用;
 * ADR-032 刀 2 的导入 / 导出离线工具在 {@code src/main},要用<b>同一道</b>判定,故移到这里 —— 方法体逐字节照搬,行为不变。
 * 放在 {@code persistence} 包:它与 {@link FileSessionStore#assertOutsideWebRoot} 同为「落盘路径闸门」,且导出工具住在本包之下。
 *
 * <p>纯静态工具:不是 bean,不被任何 Web 路径或 bean 引用;进了生产 jar 也只在被显式调用时才执行。
 */
public final class RepoPathGuard {

	private RepoPathGuard() {
	}

	/** 路径落在 {@code repoRoot} 之下 → 抛。{@code repoRoot == null} 不检查。核读工具(TraceRecheck)共用这一道闸门。 */
	public static void requireOutsideRepo(Path repoRoot, Path p, String what) throws IOException {
		if (repoRoot == null) {
			return;
		}
		Path abs = p.toAbsolutePath().normalize();
		Path existing = abs;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		Path real = existing == null ? abs : existing.toRealPath().resolve(existing.relativize(abs));
		if (real.startsWith(repoRoot.toRealPath())) {
			throw new IllegalArgumentException(what + "路径在仓库目录之内(" + p
					+ "):原始轨迹、未经核对的夹具与核读报告一律放在仓库之外");
		}
	}

	/** 由 {@code start} 向上找含 {@code .git} 的目录。找不到抛:宁可不运行,也不在不知道仓库在哪的情况下写文件。 */
	public static Path findRepoRoot(Path start) {
		for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
			if (Files.exists(p.resolve(".git"))) {
				return p;
			}
		}
		throw new IllegalStateException("找不到仓库根目录(从 " + start + " 向上没有 .git):请在仓库内运行本工具");
	}
}
