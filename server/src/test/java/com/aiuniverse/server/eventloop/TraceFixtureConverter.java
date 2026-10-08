package com.aiuniverse.server.eventloop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.TraceFileReader;
import com.aiuniverse.server.persistence.TurnTrace;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-031 刀 5a · 原始轨迹 → 回归夹具(测试侧工具,W-10;由 Felix 在本机运行,见 runbook §七「转换为回归夹具」)。
 *
 * <p>规则(ADR-031 已决 W-11):原始文件不进仓库、不进聊天、不交给云端会话;夹具只来自 Felix 本人为夹具专门开的局。
 * 本工具负责能由机器保证的那一半:
 * <ol>
 *   <li>输入与输出都必须在仓库目录<b>之外</b> —— 夹具要先经人工核对,再由人手动拷进
 *       {@code server/src/test/resources/trace-fixtures/};工具<b>不替人放进去</b>;</li>
 *   <li>白名单保留回放必需字段({@link TraceFixture#KEYS}),其余丢弃;</li>
 *   <li>saveId 一律换成 {@code fixture-<name>}(name 由人给,不由原 saveId 派生);</li>
 *   <li><b>自检</b>:对转换后的每一行跑档 1 回放,差异清单必须为空;任一行不空 → 不写夹具;</li>
 *   <li>扫描将要写出的全文({@link FixtureScanner}),任一命中(含原 saveId 残留)→ 不写夹具。</li>
 * </ol>
 * 无论成败都写一份扫描报告(在输出路径旁,{@code .report.txt}),供人核对字段清单与每行的 actionId。
 */
final class TraceFixtureConverter {

	static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");

	private final ObjectMapper mapper;
	private final ArchetypeRegistry registry;
	private final Path repoRoot;

	/** @param repoRoot 本仓库根目录(输入输出不得落在其下);{@code null} 表示不检查(仅供单测以外的场合,不应使用) */
	TraceFixtureConverter(ObjectMapper mapper, ArchetypeRegistry registry, Path repoRoot) {
		this.mapper = mapper;
		this.registry = registry;
		this.repoRoot = repoRoot;
	}

	/** @param written 是否写出了夹具;{@code refusals} 为拒绝原因(空 = 已写出) */
	record Result(boolean written, Path fixture, Path report, List<String> refusals, String reportText) {
	}

	Result convert(Path in, String name, Path out) throws IOException {
		Path report = out.resolveSibling(out.getFileName() + ".report.txt");
		List<String> refusals = new ArrayList<>();
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("name 只许 [a-z0-9-],且以字母或数字开头(收到:" + name + ")");
		}
		insideRepo(in, "输入");
		insideRepo(out, "输出");
		insideRepo(report, "报告");

		TraceFileReader.Result read = TraceFileReader.read(in, mapper);
		List<TurnTrace> raw = read.traces();
		String fixtureSaveId = TraceFixture.SAVE_ID_PREFIX + name;
		StringBuilder rep = new StringBuilder();
		rep.append("# 回归夹具转换报告(ADR-031 刀 5a)\n");
		rep.append("输入文件: ").append(in.getFileName()).append(read.partialTailSkipped() ? "(末尾半行已跳过)" : "")
				.append('\n');
		rep.append("轨迹行数: ").append(raw.size()).append('\n');
		rep.append("saveId 替换为: ").append(fixtureSaveId).append('\n');
		rep.append("保留字段(白名单): ").append(String.join(", ", TraceFixture.KEYS))
				.append("  (post 内: ").append(String.join(", ", TraceFixture.POST_KEYS)).append(")\n");
		rep.append("丢弃字段: 原始轨迹中白名单以外的全部字段(recordedAt / usage / durMs / repairErrors /"
				+ " degradeReason / promptSha256 / turnBefore 等)\n\n");

		if (raw.isEmpty()) {
			refusals.add("输入没有任何完整的轨迹行");
		}
		List<String> originalIds = raw.stream().map(TurnTrace::saveId).distinct().toList();
		if (originalIds.size() > 1) {
			refusals.add("一个输入文件里出现了 " + originalIds.size() + " 个 saveId(应恰为一局)");
		}

		TraceReplayer replayer = new TraceReplayer(
				new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(registry), mapper),
				mapper, registry, TurnTraceCollector.COMMIT);
		StringBuilder fixtureText = new StringBuilder();
		rep.append("## 逐行(行号 / 回合 / path / actionId / 原始回放差异 / 夹具回放)\n");
		for (int i = 0; i < raw.size(); i++) {
			TurnTrace t = raw.get(i);
			String line = TraceFixture.encode(t, fixtureSaveId, mapper);
			TraceReplayer.Report rawReport = replayer.replay(t);
			TraceReplayer.Report fixReport = replayer.replay(TraceFixture.decode(line, mapper));
			rep.append(String.format("%3d  T%d→%d  %-8s  actionId=%s  原始差异=%d  夹具=%s%n", i + 1, t.turnBefore(),
					t.turnBefore() + 1, t.path(), t.actionId(), rawReport.differences().size(),
					fixReport.differences().isEmpty() ? "一致(" + label(fixReport.outcome()) + ")"
							: "不一致 " + fixReport.differences()));
			if (!fixReport.differences().isEmpty()) {
				refusals.add("第 " + (i + 1) + " 行档 1 回放不一致:" + fixReport.differences());
			}
			fixtureText.append(line).append('\n');
		}

		Map<String, String> literals = new LinkedHashMap<>();
		for (int i = 0; i < originalIds.size(); i++) {
			literals.put("原 saveId" + (originalIds.size() > 1 ? "#" + (i + 1) : ""), originalIds.get(i));
		}
		List<FixtureScanner.Hit> hits = FixtureScanner.scan(fixtureText.toString(), literals);
		rep.append("\n## 敏感形态扫描(扫的是将要写出的夹具全文)\n");
		List<String> kinds = new ArrayList<>(FixtureScanner.PATTERNS.keySet());
		kinds.addAll(literals.keySet().stream().map(k -> "残留:" + k).toList());
		for (String kind : kinds) {
			List<FixtureScanner.Hit> k = hits.stream().filter(h -> h.kind().equals(kind)).toList();
			rep.append(String.format("  %-18s %s%n", kind, k.isEmpty() ? "0" : k.size() + " 处 " + k));
		}
		if (!hits.isEmpty()) {
			refusals.add("敏感形态扫描命中 " + hits.size() + " 处(见上)");
		}

		rep.append("\n## 结论\n");
		boolean ok = refusals.isEmpty();
		if (ok) {
			Files.writeString(out, fixtureText.toString(), StandardCharsets.UTF_8);
			rep.append("已写出夹具: ").append(out.getFileName()).append('\n');
			rep.append("下一步(人工):逐项核对上面的字段清单、每行 actionId 都是你本人选的、扫描全 0;\n"
					+ "确认后再手动拷进 server/src/test/resources/trace-fixtures/。若无法确认,不提交。\n");
		} else {
			Files.deleteIfExists(out); // 不留上一次的残档冒充这一次的产物
			rep.append("拒绝写出夹具:\n");
			refusals.forEach(r -> rep.append("  - ").append(r).append('\n'));
		}
		Files.writeString(report, rep.toString(), StandardCharsets.UTF_8);
		return new Result(ok, ok ? out : null, report, List.copyOf(refusals), rep.toString());
	}

	private static String label(TraceReplayer.Outcome o) {
		return o == TraceReplayer.Outcome.CONSISTENT ? "同版本" : "跨版本、无差异";
	}

	private void insideRepo(Path p, String what) throws IOException {
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
					+ "):原始轨迹与未经核对的夹具一律放在仓库之外,核对后再手动拷入");
		}
	}

	/** 由 {@code start} 向上找含 {@code .git} 的目录。找不到抛:宁可不运行,也不在不知道仓库在哪的情况下写文件。 */
	static Path findRepoRoot(Path start) {
		for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
			if (Files.exists(p.resolve(".git"))) {
				return p;
			}
		}
		throw new IllegalStateException("找不到仓库根目录(从 " + start + " 向上没有 .git):请在仓库内运行本工具");
	}
}
