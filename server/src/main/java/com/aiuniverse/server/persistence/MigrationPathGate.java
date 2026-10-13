package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 存档导入 / 导出工具的路径闸门(ADR-032 §三「路径规则」、测试面 10)。输入目录、输出目录、报告文件一律先
 * {@code toAbsolutePath().normalize()},再过两道判定,<b>复用</b>既有实现、不另写:
 * <ol>
 *   <li>不得位于 static web 根之下 —— {@link FileSessionStore#assertOutsideWebRoot}(存档是视图 1 全量,
 *       含 isTrue / hiddenLogic,绝不出网,CONTEXT §三.9);</li>
 *   <li>不得位于仓库目录之内 —— {@link RepoPathGuard#requireOutsideRepo}(ADR-031 W-11:真实数据不进仓库)。
 *       仓库根取「当前工作目录向上」与「该路径自己向上」两处各找一次:只看工作目录的话,在仓库外启动工具
 *       却把仓库里的路径当参数传进来就会漏判。两处都找不到 {@code .git}(例如容器里没有仓库)→ 没有仓库可漏,
 *       这一道不适用。</li>
 * </ol>
 *
 * <h2>合成夹具的测试豁免</h2>
 * 第 2 道只能由 {@link #forSyntheticFixturesInTests} 关掉,它是<b>包内可见</b>的静态工厂,只有同包的测试代码调用。
 * 生产入口 {@link StoreMigrationTool#main} 只用 {@link #strict()},且命令行没有任何参数映射到豁免 ——
 * 走 {@code java … StoreMigrationTool import <dir>} 的人<b>没有办法</b>关掉它。
 * {@code MigrationPathGateSourceTest} 用源码级断言守住「{@code src/main} 里除定义处之外没有任何地方调用豁免工厂」。
 * web 根那一道没有豁免(测试的临时目录本来就不在 web 根之下)。
 */
public final class MigrationPathGate {

	private final boolean allowInsideRepo;
	private final List<Path> webRoots;

	MigrationPathGate(boolean allowInsideRepo, List<Path> webRoots) {
		this.allowInsideRepo = allowInsideRepo;
		this.webRoots = List.copyOf(webRoots);
	}

	/** 生产入口唯一用的闸门:web 根 + 仓库两道都在,web 根取当前 classpath。 */
	public static MigrationPathGate strict() {
		return new MigrationPathGate(false, FileSessionStore.classpathWebRoots(MigrationPathGate.class.getClassLoader()));
	}

	/**
	 * <b>测试专用</b>:关掉「不得在仓库之内」这一道,供合成夹具(在 {@code target/} 下现场生成)使用。
	 * 只能由同包的测试代码调用;生产代码调用它会被源码级断言打红。
	 */
	static MigrationPathGate forSyntheticFixturesInTests(List<Path> webRoots) {
		return new MigrationPathGate(true, webRoots);
	}

	/** 同 {@link #strict()} 的两道判定,但 web 根由调用方给(测试用;仓库那一道不放松)。 */
	static MigrationPathGate strictWithWebRoots(List<Path> webRoots) {
		return new MigrationPathGate(false, webRoots);
	}

	/** 归一化后检查;不通过抛 {@link IllegalArgumentException} / {@link IllegalStateException}。返回归一化后的路径。 */
	public Path check(Path p, String what) throws IOException {
		Path abs = p.toAbsolutePath().normalize();
		FileSessionStore.assertOutsideWebRoot(abs, webRoots, what, "命令行参数(换一个不在 web 根之下的目录)");
		if (!allowInsideRepo) {
			for (Path root : repoRoots(abs)) {
				RepoPathGuard.requireOutsideRepo(root, abs, what);
			}
		}
		return abs;
	}

	private static List<Path> repoRoots(Path abs) {
		Set<Path> roots = new LinkedHashSet<>();
		addIfFound(roots, Path.of(""));
		addIfFound(roots, abs);
		return new ArrayList<>(roots);
	}

	private static void addIfFound(Set<Path> roots, Path start) {
		try {
			roots.add(RepoPathGuard.findRepoRoot(start));
		} catch (IllegalStateException notInARepo) {
			// 这一处向上没有 .git:没有仓库可漏
		}
	}
}
