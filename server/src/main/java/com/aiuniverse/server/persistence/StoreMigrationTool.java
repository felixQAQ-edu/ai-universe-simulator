package com.aiuniverse.server.persistence;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Profiles;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

import tools.jackson.databind.ObjectMapper;

/**
 * 存档导入 / 回退导出的<b>离线</b>命令(ADR-032 §三;不加任何 HTTP 端点)。同一个 jar 里的独立 {@code main} 类:
 *
 * <pre>
 *   import &lt;dir&gt; [--report=&lt;file&gt;]   文件存档目录 → game_session(source='import')
 *   export &lt;dir&gt; [--report=&lt;file&gt;]   game_session → 空目录下的 &lt;save_id&gt;.json
 * </pre>
 * 其余以 {@code --} 开头的参数原样交给 Spring(如 {@code --spring.datasource.url=…});连接来源与应用同一套解析
 * ({@code spring.datasource.*} 优先,其次 {@code DATABASE_URL},见 {@link PgConnectionEnvironmentPostProcessor},
 * 经 {@code spring.factories} 对任何 {@link SpringApplication} 生效,本类不另写一份)。
 *
 * <h2>运行条件</h2>
 * <ul>
 *   <li><b>必须在 {@code pg} profile 下运行</b>;不是 → 环境就绪那一刻抛,退出码 1,不建任何连接。</li>
 *   <li>先跑 Flyway migrate(随上下文启动),再操作 —— 空库也能用。</li>
 *   <li>⚠️ <b>导入 / 导出时应用必须停着。</b>本工具不防并发写者:应用在跑时它的 persist 会与导入交错,
 *       对账读数也会失真。刀 3 的 runbook 照这一条写。</li>
 *   <li>事务超时用本工具自己的配置项 {@value #TX_TIMEOUT_PROPERTY}(缺省 {@value #DEFAULT_TX_TIMEOUT_SECONDS} s),
 *       <b>不碰</b> {@code application-pg.yml} 里 persist 的 2 s(ADR-032 已知代价 5)。</li>
 * </ul>
 *
 * <h2>装配面(收窄,不扫包)</h2>
 * 上下文只由 {@link ToolConfig} 起:{@code @ImportAutoConfiguration} 只引 DataSource / Flyway / Jackson 三项,
 * 外加一个 {@link ArchetypeRegistry} bean;<b>不用 {@code @SpringBootApplication}、不做组件扫描</b> ——
 * 扫全包会把 {@code GameSessionManager}(其 {@code @PostConstruct reloadFromStore()})与 {@code JdbcSessionStore}
 * 的启动收口一起拉起来。非 web 上下文({@link WebApplicationType#NONE})。{@code StoreMigrationToolContextTest} 守住这一点。
 * 本类与 {@link ToolConfig} 都<b>不是</b>组件,应用自己的组件扫描也不会把它们装进线上上下文。
 *
 * <h2>退出码(ADR-032「已决 · D-2」)</h2>
 * 0 通过 · 1 工具或配置错误(什么都没写)· 2 数据层面需要人看(有不可读档 / 库与源文件不一致;可读档已照常导入)·
 * 3 完整性失败(读回不一致或对账不平,整批已回滚)。多种情况取最大值。
 *
 * <p>线上(容器内)的调用方式属 ADR-032 检查清单 D-3,<b>未验证</b>。
 */
public final class StoreMigrationTool {

	public static final int EXIT_OK = 0;
	public static final int EXIT_TOOL_ERROR = 1;
	public static final int EXIT_NEEDS_REVIEW = 2;
	public static final int EXIT_INTEGRITY = 3;

	static final String TX_TIMEOUT_PROPERTY = "aiuniverse.store-migration.tx-timeout-seconds";
	/**
	 * 缺省 300 s。理由:一批是一个事务,体量按底稿最近读数约 145 档(2026-09-28),每档三条短语句,
	 * 正常总耗时是秒级;上界只为「DB 挂起时不要无限等」,给两个数量级的余量。单条语句仍另受
	 * {@code application-pg.yml} 的 {@code socketTimeout=2} 约束(每条都是毫秒级,不受影响)。
	 */
	static final int DEFAULT_TX_TIMEOUT_SECONDS = 300;

	private StoreMigrationTool() {
	}

	public static void main(String[] args) {
		System.exit(run(args, MigrationPathGate.strict(), Seams.DEFAULT, System.out));
	}

	/** 导入的两个测试接缝;生产只用 {@link #DEFAULT}。 */
	record Seams(SessionImporter.ReadBack readBack, SessionImporter.SourceReader reader) {
		static final Seams DEFAULT = new Seams(SessionImporter.DEFAULT_READ_BACK, SessionImporter.DEFAULT_SOURCE_READER);
	}

	/** 解析后的命令。 */
	record Command(String verb, Path dir, Path report) {
	}

	/** 只装这些:DataSource + Flyway + Jackson(ObjectMapper)+ ArchetypeRegistry。不是组件,不会被扫进应用上下文。 */
	@ImportAutoConfiguration({ DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
			JacksonAutoConfiguration.class })
	static class ToolConfig {

		@Bean
		ArchetypeRegistry archetypeRegistry() {
			return new ArchetypeRegistry();
		}
	}

	/** 环境就绪即查 profile:不是 pg 就在建任何连接之前停下。 */
	static final class RequirePgProfile implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			if (!event.getEnvironment().acceptsProfiles(Profiles.of(PgConnectionEnvironmentPostProcessor.PG_PROFILE))) {
				throw new NotPgProfile();
			}
		}
	}

	static final class NotPgProfile extends IllegalStateException {
		NotPgProfile() {
			super("必须在 pg profile 下运行(SPRING_PROFILES_ACTIVE=pg 或 --spring.profiles.active=pg)");
		}
	}

	/** 起工具上下文(包内可见:装配面测试直接用它)。 */
	static ConfigurableApplicationContext startContext(String[] args) {
		SpringApplication app = new SpringApplication(ToolConfig.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.addListeners(new RequirePgProfile());
		return app.run(args);
	}

	static Command parse(String[] args) {
		List<String> positional = new ArrayList<>();
		Path report = null;
		for (String a : args) {
			if (a.startsWith("--report=")) {
				report = Path.of(a.substring("--report=".length()));
			} else if (!a.startsWith("--")) {
				positional.add(a);
			}
		}
		if (positional.size() != 2 || !List.of("import", "export").contains(positional.get(0))) {
			throw new IllegalArgumentException("用法:import <dir> | export <dir> [--report=<file>](其余 --x=y 交给 Spring)");
		}
		return new Command(positional.get(0), Path.of(positional.get(1)), report);
	}

	/** 全流程;返回退出码。报告打到 {@code out}(不含存档内容、不含绝对路径)。 */
	static int run(String[] args, MigrationPathGate gate, Seams seams, PrintStream out) {
		Command cmd;
		Path dir;
		Path report;
		try {
			cmd = parse(args);
			String dirWhat = "import".equals(cmd.verb()) ? "导入源目录" : "导出目标目录";
			dir = gate.check(cmd.dir(), dirWhat);
			report = cmd.report() == null ? null : gate.check(cmd.report(), "报告文件");
			if ("import".equals(cmd.verb()) && !Files.isDirectory(dir)) {
				throw new IllegalArgumentException("导入源目录不存在或不是目录");
			}
			if ("export".equals(cmd.verb()) && !SessionExporter.isEmptyOrAbsent(dir)) {
				throw new IllegalArgumentException("导出目标目录不为空(不与月账、旧存档混写),什么都没写");
			}
		} catch (Exception e) {
			out.println("[store-migration] 退出码 " + EXIT_TOOL_ERROR + ":" + e.getMessage());
			return EXIT_TOOL_ERROR;
		}

		ConfigurableApplicationContext ctx;
		try {
			ctx = startContext(args);
		} catch (Throwable t) {
			out.println("[store-migration] 退出码 " + EXIT_TOOL_ERROR + ":" + startupFailure(t));
			return EXIT_TOOL_ERROR;
		}
		try (ctx) {
			DataSource ds = ctx.getBean(DataSource.class);
			ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
			ArchetypeRegistry archetypes = ctx.getBean(ArchetypeRegistry.class);
			int timeout = ctx.getEnvironment().getProperty(TX_TIMEOUT_PROPERTY, Integer.class, DEFAULT_TX_TIMEOUT_SECONDS);
			String text;
			int code;
			if ("import".equals(cmd.verb())) {
				SessionImporter.Result r = new SessionImporter(ds, mapper, archetypes, timeout, seams.readBack(),
						seams.reader()).run(dir);
				code = r.exitCode();
				text = renderImport(r, timeout, code);
			} else {
				SessionExporter.Result r = new SessionExporter(ds, mapper, archetypes, timeout).run(dir);
				code = r.exitCode();
				text = renderExport(r, timeout, code);
			}
			out.print(text);
			if (report != null) {
				writeReport(report, text);
			}
			return code;
		} catch (Exception e) {
			// 走到这里:导入的事务已回滚(非完整性类失败)/ 导出在写之前失败 —— 什么都没写
			out.println("[store-migration] 退出码 " + EXIT_TOOL_ERROR + ":" + e.getClass().getSimpleName() + " — "
					+ e.getMessage());
			return EXIT_TOOL_ERROR;
		}
	}

	/** 启动失败的原因:最内层异常类型 + message(驱动与连接池的 message 不带密码;DatabaseUrl 解析失败不回显原值)。 */
	private static String startupFailure(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		if (t instanceof NotPgProfile || root instanceof NotPgProfile) {
			return root.getMessage();
		}
		return "上下文启动失败(连接数据库或 Flyway 迁移):" + root.getClass().getSimpleName() + " — " + root.getMessage();
	}

	private static void writeReport(Path report, String text) throws IOException {
		Path parent = report.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = report.resolveSibling(report.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException atomicUnsupported) {
			Files.move(tmp, report, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static String renderImport(SessionImporter.Result r, int timeout, int code) {
		StringBuilder b = new StringBuilder();
		b.append("[store-migration] import(事务超时 ").append(timeout).append(" s)\n");
		b.append("候选 N(目录直接下 *.json)= ").append(r.candidateFiles()).append('\n');
		if (r.failure() != null) {
			b.append("⚠️ 整批回滚:").append(r.failure()).append('\n');
			b.append("imported = 0(已回滚)\n");
		} else {
			b.append("imported = ").append(r.imported()).append('\n');
		}
		b.append("skipped = ").append(r.failure() != null ? r.skippedNotASave() : r.skipped())
				.append("(非存档 ").append(r.skippedNotASave())
				.append(" / 重复导入 ").append(r.skippedDuplicate())
				.append(" / 库中已有且字节相同 ").append(r.skippedSameAsExisting().size())
				.append(" / 库与源文件不一致·不覆盖 ").append(r.inconsistent().size()).append(")\n");
		b.append("integrity_failed = ").append(r.integrityFailed()).append('\n');
		b.append("不可读 = ").append(r.unreadable().size()).append('\n');
		r.unreadable().forEach(u -> b.append("  - ").append(u.fileName()).append(" — ").append(u.reason().label).append('\n'));
		if (!r.skippedSameAsExisting().isEmpty()) {
			b.append("库中已有、与源文件字节相同(跳过):\n");
			r.skippedSameAsExisting().forEach(e -> b.append("  - ").append(e.saveId()).append("(source=")
					.append(e.source()).append(")\n"));
		}
		if (!r.inconsistent().isEmpty()) {
			b.append("库与源文件不一致(不覆盖):\n");
			r.inconsistent().forEach(e -> b.append("  - ").append(e.saveId()).append("(source=").append(e.source())
					.append(")\n"));
		}
		b.append("库中 source='import' 行数:导入前 ").append(r.importRowsBefore()).append(" / 导入后 ")
				.append(r.importRowsAfter()).append('\n');
		if (r.failure() == null) {
			b.append("对账:N = imported + skipped + 不可读 → ").append(r.candidateFiles()).append(" = ")
					.append(r.imported()).append(" + ").append(r.skipped()).append(" + ").append(r.unreadable().size())
					.append(" ✓;导入后 − 导入前 = imported → ").append(r.importRowsAfter() - r.importRowsBefore())
					.append(" = ").append(r.imported()).append(" ✓(两项均在提交前于同一事务内断言)\n");
		} else {
			b.append("对账:整批已回滚,导入后 = 导入前 → ")
					.append(r.importRowsAfter() == r.importRowsBefore() ? "✓ 零残留" : "✗ 有残留").append('\n');
		}
		b.append("退出码 = ").append(code).append('\n');
		return b.toString();
	}

	static String renderExport(SessionExporter.Result r, int timeout, int code) {
		StringBuilder b = new StringBuilder();
		b.append("[store-migration] export(事务超时 ").append(timeout).append(" s)\n");
		b.append("库中行数 N = ").append(r.rows()).append('\n');
		b.append("写出 = ").append(r.written()).append('\n');
		b.append("拒写(save_id 非法)= ").append(r.refused().size()).append('\n');
		r.refused().forEach(s -> b.append("  - ").append(s).append('\n'));
		b.append("载不动(照样写出,回退后会被拒载)= ").append(r.undecodable().size()).append('\n');
		r.undecodable().forEach(s -> b.append("  - ").append(s).append('\n'));
		if (r.failure() != null) {
			b.append("⚠️ ").append(r.failure()).append('\n');
		} else {
			b.append("对账:N = 写出 + 拒写 → ").append(r.rows()).append(" = ").append(r.written()).append(" + ")
					.append(r.refused().size()).append(r.rows() == r.written() + r.refused().size() ? " ✓" : " ✗")
					.append('\n');
		}
		b.append("退出码 = ").append(code).append('\n');
		return b.toString();
	}

	/** 调试用:列出工具上下文里的 bean 名(装配面取证)。 */
	static String beanNames(ConfigurableApplicationContext ctx) {
		return java.util.Arrays.stream(ctx.getBeanDefinitionNames()).sorted().collect(Collectors.joining("\n"));
	}
}
