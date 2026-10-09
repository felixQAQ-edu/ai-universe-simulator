package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.persistence.TurnTrace;
import com.aiuniverse.server.persistence.TurnTraceCodec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 复验短局 · 轨迹核读工具。轨迹全部由真实采集在测试里跑出({@link TraceScenarios}),不碰任何线上文件。
 */
class TraceRecheckTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ArchetypeRegistry REGISTRY = new ArchetypeRegistry();
	private static final TraceScenarios SCENARIOS = new TraceScenarios(MAPPER, REGISTRY);
	private static final Map<String, TraceScenarios.Run> RUNS = SCENARIOS.all();
	private static final Path REPO = TraceFixtureConverter.findRepoRoot(Path.of("").toAbsolutePath());

	@TempDir
	Path tmp;

	private static String report(List<TurnTrace> traces) {
		return new TraceRecheck(MAPPER, REGISTRY).report(traces, false);
	}

	/** 第 4 回合(原屋,金属声句组允许):正文写一句允许的原句 + 一句禁用的原句 + 纸箱。 */
	private static TraceScenarios.Run oldHomeT4(String narrative) {
		return SCENARIOS.run(SCENARIOS.session("animal_life", 3, true), List.of("A"), TraceScenarios.wire(narrative,
				"\"body\":78,\"warmth\":58,\"ground\":49,\"close\":51", "null", "A", "B", "C"));
	}

	private static TurnTrace withNarrative(TurnTrace t, String narrative) {
		ObjectNode parsed = t.parsed().deepCopy();
		parsed.put("narrative", narrative);
		return new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), t.commit(), t.actionId(),
				t.path(), t.pre(), parsed, t.degradeReason(), t.streamedNarrative(), t.promptSha256(), t.usage(),
				t.durMs(), t.repairErrors(), t.post());
	}

	private static String section(String report, int turn) {
		int i = report.indexOf("════════ 第 " + turn + " 回合");
		assertThat(i).as("报告里有第 " + turn + " 回合").isNotNegative();
		int j = report.indexOf("════════ 第 ", i + 1);
		return j < 0 ? report.substring(i) : report.substring(i, j);
	}

	// ── 样例 ─────────────────────────────────────────────────────────────

	@Test
	void sampleReportForBoxSceneThroughLeave() {
		String r = report(RUNS.get("box_scene_through_leave").traces());
		System.out.println("──── 核读报告样例(合成轨迹 box_scene_through_leave)────\n" + r);
		assertThat(r).startsWith("# 复验短局 · 轨迹核读报告\n").doesNotContain(TraceRecheck.SHA_WARNING)
				.contains("prompt 重渲染核对: 全部一致").contains("开场须人工截图")
				.contains("第一行轨迹不是从第 0 回合开始");
	}

	// ── 1 局面编排 ───────────────────────────────────────────────────────

	@Test
	void sceneLineComesFromPreRecomputed() {
		String r = report(RUNS.get("box_scene_through_leave").traces());
		assertThat(section(r, 15)).contains("结算结果=R3A").contains("拍号=B1").contains("处境=EMPTY_HOME");
		assertThat(section(r, 18)).contains("【离开回合】").contains("处境=OUTSIDE");
		assertThat(report(RUNS.get("normal").traces())).contains("局面: 无局面键");
	}

	// ── 2 正文与选项 ─────────────────────────────────────────────────────

	@Test
	void narrativeAndOptionsFromNextPreAndLastLineFromReplay() {
		List<TurnTrace> traces = RUNS.get("box_scene_through_leave").traces();
		String r = report(traces);
		String t18 = section(r, 18);
		assertThat(t18).contains("它把鼻子贴近地面。").contains("取自下一行的回合前状态");
		// 离开回合给出的是「刚出门」三条(下一行 pre 里的 currentActions)
		for (var a : traces.get(8).pre().path("currentActions")) {
			assertThat(t18).contains(a.path("text").asString());
		}
		String last = section(r, 19);
		assertThat(last).contains("由档 1 落账回放重算");
		for (var a : RUNS.get("box_scene_through_leave").session().currentActions()) {
			assertThat(last).contains(a.path("text").asString());
		}
	}

	@Test
	void degradedLeaveTurnShowsRecomputedLeaveNarrative() {
		TurnTrace leave = RUNS.get("box_scene_leave_degraded").last();
		GameSession s = com.aiuniverse.server.persistence.SessionDocument.decode(leave.saveId(), leave.pre(), MAPPER,
				REGISTRY);
		BoxSceneTurn.Plan plan = new EventLoopService(new TurnTraceTest.ScriptedLlm(), new TurnPromptBuilder(REGISTRY),
				MAPPER).scenePlan(s, leave.actionId());
		assertThat(plan.transition()).isTrue();
		String t18 = section(report(RUNS.get("box_scene_leave_degraded").traces()), 18);
		assertThat(t18).contains("降级原因=stream_interrupted").contains("离开叙事由编排重算")
				.contains(BoxSceneTurn.narrated(plan.feedback())).contains("由档 1 落账回放重算");
	}

	// ── 3 逐字句窗口 ─────────────────────────────────────────────────────

	@Test
	void allowedWindowSentenceIsMarkedAllowed() {
		String r = report(oldHomeT4("楼道里有金属碰金属的声音。你抬起头。门开了。").traces());
		String t4 = section(r, 4);
		assertThat(t4).contains("本回合允许的原句: 楼道里有金属碰金属的声音。 你抬起头。")
				.contains("逐字出现「楼道里有金属碰金属的声音。」 → 允许窗口内")
				.contains("逐字出现「你抬起头。」 → 允许窗口内").doesNotContain("⚠️ 禁用窗口出现")
				.as("逐字出现的那一段不再按片段重复计为疑似改写").doesNotContain("疑似改写");
	}

	@Test
	void plantedForbiddenSentenceIsFlaggedInForbiddenWindow() {
		TraceScenarios.Run run = RUNS.get("box_scene_through_leave");
		List<TurnTrace> traces = new ArrayList<>(run.traces());
		// 第 16 回合 = B2(被留下余波),金属声句组禁用
		traces.set(5, withNarrative(traces.get(5), "光挪到了墙上。是别的门。你把头低下来。"));
		String t16 = section(report(traces), 16);
		assertThat(t16).contains("拍号=B2").contains("本回合允许的原句: 无")
				.contains("逐字出现「是别的门。」 → ⚠️ 禁用窗口出现")
				.contains("疑似改写「你把头放下去。」(片段「把头低」)→ ⚠️ 禁用窗口出现:你把头低下来。");
	}

	@Test
	void keyWordsAreFlagged() {
		String t4 = section(report(oldHomeT4("你闻到钥匙的味道。多年以后也一样。").traces()), 4);
		assertThat(t4).contains("钥匙 / 多年以后: 2 句").contains("[钥匙] 你闻到钥匙的味道。")
				.contains("[多年以后] 多年以后也一样。");
	}

	@Test
	void standaloneNoOnlyCountsAsSentence() {
		assertThat(TraceRecheck.verbatimFindings("这不是。", List.of())).isEmpty();
		assertThat(TraceRecheck.verbatimFindings("声音停了。不是。", List.of("不是。")))
				.containsExactly("逐字出现「不是。」 → 允许窗口内");
	}

	@Test
	void shaMismatchPutsWarningAtTop() {
		List<TurnTrace> traces = new ArrayList<>(RUNS.get("box_scene_through_leave").traces());
		TurnTrace t = traces.get(2);
		traces.set(2, new TurnTrace(t.schema(), t.saveId(), t.turnBefore(), t.recordedAt(), t.commit(), t.actionId(),
				t.path(), t.pre(), t.parsed(), t.degradeReason(), t.streamedNarrative(), "0".repeat(64), t.usage(),
				t.durMs(), t.repairErrors(), t.post()));
		String r = report(traces);
		assertThat(r.lines().skip(1).findFirst().orElse("")).startsWith(TraceRecheck.SHA_WARNING).contains("T13");
		assertThat(section(r, 13)).contains("(" + TraceRecheck.SHA_WARNING + ")");
		assertThat(section(r, 12)).contains("(prompt 重渲染与记录一致)");
	}

	// ── 4 只标记的检查 ───────────────────────────────────────────────────

	@Test
	void markOnlyChecks() {
		List<TurnTrace> traces = new ArrayList<>(RUNS.get("box_scene_through_leave").traces());
		traces.set(4, withNarrative(traces.get(4), "天黑了。它被留下了。"));
		traces.set(7, withNarrative(traces.get(7), "你从门缝挤出去。屋里还亮着。"));
		String r = report(traces);
		assertThat(section(r, 15)).contains("B1 夜 / 次日: 1 句").contains("[天黑] 天黑了。")
				.contains("解释词: 1 句").contains("[被留下] 它被留下了。").contains("人称「它」: 1 次");
		assertThat(section(r, 18)).contains("离开回合 屋内: 1 句").contains("[屋里] 屋里还亮着。");
		assertThat(section(r, 16)).doesNotContain("夜 / 次日: 1").contains("B2 夜 / 次日: 无命中");
		assertThat(section(r, 19)).doesNotContain("夜 / 次日").doesNotContain("离开回合 屋内");
		assertThat(r).doesNotContain("F-031");
		String t4 = section(report(oldHomeT4("屋里多了一个纸箱。车开走了。").traces()), 4);
		assertThat(t4).contains("F-031 T1–10 纸箱类: 2 句").contains("[纸箱] 屋里多了一个纸箱。")
				.contains("[车] 车开走了。");
	}

	// ── 5 不含 saveId / 引擎字段名 ───────────────────────────────────────

	@Test
	void reportHasNoSaveIdNorEngineFieldNames() {
		for (Map.Entry<String, TraceScenarios.Run> e : RUNS.entrySet()) {
			String r = report(e.getValue().traces());
			assertThat(r).as(e.getKey()).doesNotContain(e.getValue().traces().get(0).saveId())
					.doesNotContain("isTrue").doesNotContain("hiddenLogic").doesNotContain("save-replay");
		}
		assertThat(report(RUNS.get("normal_with_leak").traces())).contains("已遮蔽为 " + TraceRecheck.MASK)
				.contains("镜子里有人念出了 " + TraceRecheck.MASK);
		// 正文里若恰好写出了 saveId(极端情形),报告里也不留
		TurnTrace t = RUNS.get("normal").last();
		String planted = report(List.of(withNarrative(t, "门牌上写着 " + t.saveId() + "。")));
		assertThat(planted).doesNotContain(t.saveId()).contains("门牌上写着 <saveId>。");
	}

	// ── 文件与闸门 ───────────────────────────────────────────────────────

	@Test
	void runWritesRecheckTxtOutsideRepoAndRefusesInside() throws IOException {
		Path in = tmp.resolve("3f2b9c1e-7a4d-4e8b-9c0f-1a2b3c4d5e6f.trace.jsonl");
		StringBuilder sb = new StringBuilder();
		for (TurnTrace t : RUNS.get("box_scene_through_leave").traces()) {
			sb.append(TurnTraceCodec.encode(t, MAPPER)).append('\n');
		}
		Files.writeString(in, sb.toString(), StandardCharsets.UTF_8);
		TraceRecheck tool = new TraceRecheck(MAPPER, REGISTRY);
		Path out = tool.run(in, tmp.resolve("r3a"), REPO);
		assertThat(out.getFileName().toString()).isEqualTo("r3a.recheck.txt");
		assertThat(Files.readString(out, StandardCharsets.UTF_8)).doesNotContain("3f2b9c1e")
				.contains("输入文件: (不回显)");
		assertThatThrownBy(() -> tool.run(in, REPO.resolve("server/target/should-not-exist"), REPO))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("输出路径在仓库目录之内");
		assertThatThrownBy(() -> new TraceRecheck(MAPPER, REGISTRY).run(in, Path.of("/nonexistent-outside/x"), tmp))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("输入路径在仓库目录之内");
	}
}
