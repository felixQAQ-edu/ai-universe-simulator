package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.Situation;
import com.aiuniverse.server.eventloop.VerbatimWindows.BedFoot;
import com.aiuniverse.server.eventloop.VerbatimWindows.Metal;
import com.aiuniverse.server.eventloop.VerbatimWindows.Windows;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-029 逐字句排窗:判定(§3)、注入文字(§4,只接受「ADR → 代码」一个方向)、逐回合安排(R1 / R2 / R3a/b/c ×
 * T17 留或走,T1–T48)、七句原句在整份 prompt 里的出现集合、「钥匙」、旧局与局已结束不注入。
 *
 * <p><b>保证范围</b>(ADR-029 §6):这里证明的是 prompt 提供哪些句子;不证明流式正文遵守,也不证明
 * ADR-021「抬头落空先于伸手被打」的实际先后。
 */
class VerbatimWindowsTest {

	private static final java.nio.file.Path ADR029 =
			java.nio.file.Path.of("../docs/adr/ADR-029-animal-life-verbatim-sentence-windows.md");
	private static final java.nio.file.Path ADR028 =
			java.nio.file.Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md");

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(registry);
	private static final BoxScene.Table T = BoxSceneTables.box("animal_life");

	// ── ADR-029 §4 → 注入文字 ─────────────────────────────────────────

	private record Row(List<String> originals, List<String> notes) {
	}

	private static final Pattern QUOTED = Pattern.compile("「[^」]*」");

	/** 读 §4 下某一张表的数据行(跳过表头与分隔行)。 */
	private static List<Row> table(List<String> all, String heading) {
		int i = all.indexOf(heading);
		assertThat(i).as("ADR-029 找不到「%s」", heading).isGreaterThanOrEqualTo(0);
		List<Row> rows = new ArrayList<>();
		int k = i + 1;
		while (!all.get(k).startsWith("|")) {
			k++;
		}
		k += 2;
		for (; k < all.size() && all.get(k).startsWith("|"); k++) {
			String[] c = all.get(k).split("\\|", -1);
			String orig = c[2].strip();
			List<String> originals = new ArrayList<>();
			if (!orig.equals("无")) {
				// 床脚句连同第 (6) 条括注一起给:括注挂在最后一句后面
				int paren = orig.indexOf('(');
				String head = paren < 0 ? orig : orig.substring(0, paren);
				Matcher m = QUOTED.matcher(head);
				while (m.find()) {
					originals.add(m.group());
				}
				if (paren >= 0) {
					originals.set(originals.size() - 1, originals.get(originals.size() - 1) + orig.substring(paren));
				}
			}
			String note = c[3].strip();
			List<String> notes = note.startsWith("无") ? List.of() : Arrays.asList(note.split("<br>"));
			rows.add(new Row(originals, notes));
		}
		return rows;
	}

	private static List<String> codeBlock(List<String> all, String heading) {
		int i = all.indexOf(heading);
		int k = i + 1;
		while (!all.get(k).equals("```")) {
			k++;
		}
		List<String> out = new ArrayList<>();
		for (k++; !all.get(k).equals("```"); k++) {
			out.add(all.get(k));
		}
		return out;
	}

	@Test
	void injectedTextIsAdr029Section4_verbatim_forEveryWindowCombination() throws Exception {
		List<String> all = Files.readAllLines(ADR029);
		List<String> common = codeBlock(all, "### 4.2 共同部分(每个新局回合都注入)");
		List<Row> metal = table(all, "### 4.3 金属声窗口:可用原句 / 说明");
		List<Row> bed = table(all, "### 4.4 床脚窗口(独立序列,可与金属声窗口同回合叠加)");
		assertThat(metal).hasSize(Metal.values().length);
		assertThat(bed).hasSize(4);
		Map<BedFoot, Row> bedRows = new LinkedHashMap<>();
		bedRows.put(BedFoot.ALLOW, bed.get(0));
		bedRows.put(BedFoot.EMPTY_HOME, bed.get(1));
		bedRows.put(BedFoot.NEW_HOME_OR_OUTSIDE, bed.get(2));
		bedRows.put(BedFoot.OTHER, bed.get(3));
		for (Metal m : Metal.values()) {
			Row mr = metal.get(m.ordinal());
			for (BedFoot b : BedFoot.values()) {
				Row br = bedRows.get(b);
				List<String> lines = new ArrayList<>(List.of(common.get(0), common.get(1)));
				List<String> orig = new ArrayList<>(mr.originals());
				orig.addAll(br.originals());
				if (!orig.isEmpty()) {
					lines.add(common.get(2));
					orig.forEach(o -> lines.add("    " + o));
				}
				lines.addAll(mr.notes());
				lines.addAll(br.notes());
				assertThat(VerbatimWindows.render(new Windows(m, b))).as(m + "/" + b).isEqualTo(String.join("\n", lines));
			}
		}
		// T4 渲染示例逐字相等
		assertThat(VerbatimWindows.render(new Windows(Metal.OLD_HOME, BedFoot.OTHER)))
				.isEqualTo(String.join("\n", codeBlock(all, "### 4.5 渲染示例(T4,全文)")));
	}

	// ── 判定纯函数 ─────────────────────────────────────────────────────

	@Test
	void a2IsJudgedByBeatNotByTurn() {
		// 拍号与回合号错开:按拍命中
		assertThat(VerbatimWindows.judge(T, 23, false, Path.R2, Situation.NEW_HOME, "A2", false).metal())
				.isEqualTo(Metal.A2);
		assertThat(VerbatimWindows.judge(T, 15, false, Path.R2, Situation.NEW_HOME, "A1", false).metal())
				.isEqualTo(Metal.A1);
		assertThat(VerbatimWindows.judge(T, 15, false, Path.R1, Situation.NEW_HOME, "A2", false).metal())
				.isEqualTo(Metal.A2);
		assertThat(VerbatimWindows.judge(T, 16, false, Path.R2, Situation.NEW_HOME, "A2", false).metal())
				.isEqualTo(Metal.A2);
		assertThat(VerbatimWindows.judge(T, 16, false, Path.R1, Situation.NEW_HOME, "A3", false).metal())
				.isEqualTo(Metal.OTHER);
	}

	@Test
	void r3T18HitsInBothEmptyHomeAndOutside_r1r2T18DoNot() {
		for (Path p : List.of(Path.R3A, Path.R3B, Path.R3C)) {
			assertThat(VerbatimWindows.judge(T, 18, false, p, Situation.EMPTY_HOME, null, false).metal())
					.as(p + " EMPTY_HOME").isEqualTo(Metal.T18);
			assertThat(VerbatimWindows.judge(T, 18, false, p, Situation.OUTSIDE, null, false).metal())
					.as(p + " OUTSIDE").isEqualTo(Metal.T18);
		}
		for (Path p : List.of(Path.R1, Path.R2)) {
			assertThat(VerbatimWindows.judge(T, 18, false, p, Situation.NEW_HOME, null, false).metal())
					.as(p.name()).isEqualTo(Metal.OTHER);
		}
	}

	@Test
	void legacyAndEndedInjectNothing() {
		assertThat(VerbatimWindows.judge(T, 4, true, null, null, null, false)).isNull();
		assertThat(VerbatimWindows.judge(T, 4, false, null, null, null, true)).isNull();
		assertThat(VerbatimWindows.render(null)).isEmpty();
	}

	// ── 逐回合安排(ADR-029 §3.2 / §3.3,经 EventLoopService 真走一局)──────

	private ObjectNode world(String... ruleContents) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("animal_life");
		world.putObject("world").put("title", "屋里的灯").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
		ArrayNode rules = world.putArray("rules");
		int id = 1;
		for (String c : ruleContents) {
			rules.addObject().put("id", id++).put("content", c).put("hiddenLogic", "-").put("discovered", false);
		}
		world.putArray("endings").addObject().put("id", "hit").put("title", "撞上")
				.put("condition", "【身子】归零").put("outcome", "failure").put("reached", false);
		return world;
	}

	private GameSession fresh(BoxSceneState st, String... ruleContents) {
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		acts.addObject().put("id", "B").put("text", "闻闻");
		GameSession s = new GameSession("save-verbatim", new Engine(world(ruleContents), mapper), acts);
		s.setBoxScene(st);
		return s;
	}

	private static String ok(int body) {
		return "它把鼻子贴近地面。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"body\":" + body + ",\"warmth\":60,\"ground\":50,\"close\":50,\"timeline\":\"日子\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"a\",\"hint\":\"h\"},"
				+ "{\"id\":\"B\",\"text\":\"b\",\"hint\":\"h\"},{\"id\":\"C\",\"text\":\"c\",\"hint\":\"h\"}],"
				+ "\"ending\":null}";
	}

	private String turn(GameSession s, String choice) {
		BoxSceneWiringTest.ScriptedLlm llm = new BoxSceneWiringTest.ScriptedLlm();
		llm.script(ok(80));
		new EventLoopService(llm, prompts, mapper).execute(s, choice, new BoxSceneWiringTest.Sink());
		return llm.prompts.get(0);
	}

	/**
	 * 生成第 n 回合用的那一次选择(同 {@code BoxSceneClockOverrideTest}:生成 T11 起依次为
	 * R1 = A…;R2 = A,A,A,B,…;R3 = A,C,C,C,(A|B|C → R3a|b|c),A,A,(T17 那组:C = 离开 / B = 留下))。
	 */
	private static String choiceFor(String path, boolean leaveAt17, int n) {
		if (path.equals("R2") && n == 14) {
			return "B";
		}
		if (path.startsWith("R3")) {
			if (n >= 12 && n <= 14) {
				return "C";
			}
			if (n == 15) {
				return switch (path) {
					case "R3A" -> "A";
					case "R3B" -> "B";
					default -> "C";
				};
			}
			if (n == 18) {
				return leaveAt17 ? "C" : "B";
			}
		}
		return "A";
	}

	/** 跑完 T1–T48,返回每回合 prompt;按路径断言结算结果。 */
	private Map<Integer, String> play(String path, boolean leaveAt17) {
		GameSession s = fresh(BoxSceneState.fresh());
		Map<Integer, String> out = new LinkedHashMap<>();
		for (int n = 1; n <= 48; n++) {
			assertThat(s.engine().turn() + 1).isEqualTo(n);
			out.put(n, turn(s, choiceFor(path, leaveAt17, n)));
		}
		assertThat(s.boxScene().result).as(path).isEqualTo(Path.valueOf(path));
		if (path.startsWith("R3")) {
			assertThat(s.boxScene().situation).isEqualTo(leaveAt17 ? Situation.OUTSIDE : Situation.EMPTY_HOME);
		} else {
			assertThat(s.boxScene().situation).isEqualTo(Situation.NEW_HOME);
		}
		return out;
	}

	/** ADR-029 §3.2(独立编码,不调用判定函数)。 */
	private static Metal expectedMetal(String path, int n) {
		boolean r1 = path.equals("R1");
		boolean r3 = path.startsWith("R3");
		if (n == 4 || n == 9) {
			return Metal.OLD_HOME;
		}
		if (n >= 11 && n <= 13) {
			return Metal.BOX;
		}
		if (n == 14) {
			return r1 ? Metal.A1 : Metal.BOX;
		}
		if (n == 15) {
			return r1 ? Metal.A2 : r3 ? Metal.LEFT_AFTERMATH : Metal.A1;
		}
		if (n == 16) {
			return r3 ? Metal.LEFT_AFTERMATH : r1 ? Metal.OTHER : Metal.A2;
		}
		if (n == 17) {
			return r3 ? Metal.LEFT_AFTERMATH : Metal.OTHER;
		}
		if (n == 18) {
			return r3 ? Metal.T18 : Metal.OTHER;
		}
		if (n == 30) {
			return Metal.T30;
		}
		if (n == 45) {
			return Metal.T45;
		}
		return Metal.OTHER;
	}

	/** ADR-029 §3.3(独立编码)。 */
	private static BedFoot expectedBed(String path, boolean leaveAt17, int n) {
		if (n == 6 || n == 10) {
			return BedFoot.ALLOW;
		}
		if (n <= 13 || (n == 14 && !path.equals("R1"))) {
			return BedFoot.OTHER;
		}
		if (!path.startsWith("R3")) {
			return BedFoot.NEW_HOME_OR_OUTSIDE;
		}
		return leaveAt17 && n >= 18 ? BedFoot.NEW_HOME_OR_OUTSIDE : BedFoot.EMPTY_HOME;
	}

	private static final String BLOCK_START = "【逐字句窗口 · 本回合】";
	private static final String BLOCK_END = "\n【叙事人称 · 硬约束】";

	private static String injected(String prompt) {
		int i = prompt.indexOf(BLOCK_START);
		assertThat(i).as("缺注入块").isGreaterThanOrEqualTo(0);
		assertThat(prompt.indexOf(BLOCK_START, i + 1)).as("注入块不止一段").isLessThan(0);
		assertThat(prompt.substring(0, i)).endsWith("它到死都还在。\n");
		return prompt.substring(i, prompt.indexOf(BLOCK_END, i));
	}

	/** 七句原句在整份 prompt 里出现的集合。 */
	private static Set<String> sevenPresent(String prompt) {
		Set<String> out = new LinkedHashSet<>();
		for (String s : VerbatimWindows.SEVEN) {
			if (prompt.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	private static Set<String> allowed(Windows w) {
		Set<String> out = new LinkedHashSet<>();
		for (String o : VerbatimWindows.originals(w)) {
			out.add(o.substring(1, o.length() - 1));
		}
		return out;
	}

	private void checkSchedule(String path, boolean leaveAt17) {
		Map<Integer, String> p = play(path, leaveAt17);
		for (Map.Entry<Integer, String> e : p.entrySet()) {
			int n = e.getKey();
			String why = path + (leaveAt17 ? "·T17走" : "·T17留") + " T" + n;
			Windows w = new Windows(expectedMetal(path, n), expectedBed(path, leaveAt17, n));
			assertThat(injected(e.getValue())).as(why).isEqualTo(VerbatimWindows.render(w));
			assertThat(sevenPresent(e.getValue())).as(why + " 七句集合").isEqualTo(allowed(w));
			assertThat(e.getValue()).as(why).doesNotContain("(6)【逐字不变的句子");
			// 「钥匙」只出现在「正文不许出现「钥匙」」这一句里
			assertThat(e.getValue().split("钥匙", -1).length - 1).as(why + " 钥匙").isEqualTo(1);
			assertThat(e.getValue()).contains("正文不许出现「钥匙」");
		}
	}

	@Test
	void scheduleR1() {
		checkSchedule("R1", false);
	}

	@Test
	void scheduleR2() {
		checkSchedule("R2", false);
	}

	@Test
	void scheduleR3abc_stayAndLeaveAtT17() {
		for (String p : List.of("R3A", "R3B", "R3C")) {
			checkSchedule(p, false);
			checkSchedule(p, true);
		}
	}

	/** 七句集合断言只覆盖引擎注入部分:世界里的 rules 自带原句时,它会经视图 2 出现在任何回合。 */
	@Test
	void sevenSentenceAssertionCoversOnlyTheEngineInjection() {
		GameSession s = fresh(BoxSceneState.fresh(), "听见「是别的门。」就趴回去");
		String t1 = turn(s, "A");
		Windows w = new Windows(Metal.OTHER, BedFoot.OTHER);
		assertThat(injected(t1)).isEqualTo(VerbatimWindows.render(w));
		assertThat(allowed(w)).isEmpty();
		assertThat(sevenPresent(t1)).containsExactly("是别的门。");
	}

	// ── 旧局 / 局已结束 ──────────────────────────────────────────────

	@Test
	void legacyGameGetsNoWindowAndKeepsRule6() {
		GameSession s = fresh(BoxSceneState.legacyMarker());
		for (int n = 1; n <= 20; n++) {
			String p = turn(s, "A");
			assertThat(p).as("T" + n).doesNotContain("【逐字句窗口").contains("(6)【逐字不变的句子 · 硬约束】")
					.contains("「楼道里有金属碰金属的声音。」(不许出现「钥匙」,不许出现「多年以后」)");
		}
	}

	@Test
	void endedGameGetsNoWindow() {
		GameSession s = fresh(BoxSceneState.fresh());
		BoxSceneWiringTest.ScriptedLlm llm = new BoxSceneWiringTest.ScriptedLlm();
		llm.script(ok(0)); // 身子归零 → 引擎兜底撞上
		new EventLoopService(llm, prompts, mapper).execute(s, "A", new BoxSceneWiringTest.Sink());
		assertThat(s.engine().status()).isEqualTo("ended");
		String p = prompts.buildTurnPrompt(s.engine(), "A", "趴着");
		assertThat(p).doesNotContain("【逐字句窗口").doesNotContain("(6)【逐字不变的句子")
				.contains("它到死都还在。\n【叙事人称 · 硬约束】");
	}

	// ── 「钥匙」 ─────────────────────────────────────────────────────

	@Test
	void keyOnlyInTheBanLine_worldGenHasNone_outsideFragmentIsTheRevision() throws Exception {
		assertThat(new WorldGenPromptBuilder(registry).buildWorldPrompt("animal_life")).doesNotContain("钥匙");
		List<String> all = Files.readAllLines(ADR028);
		int i = all.indexOf("### 修订(Felix 2026-10-03)");
		assertThat(i).isGreaterThanOrEqualTo(0);
		String revised = all.subList(i, all.size()).stream().filter(l -> l.startsWith("- `OUTSIDE`：")).findFirst()
				.orElseThrow();
		assertThat(revised).isEqualTo("- `OUTSIDE`：那声音来自别的门，不是它等的那一扇。");
		String frag = BoxSceneTables.situationFragment(Situation.OUTSIDE);
		assertThat(frag).contains("\n   " + revised + "\n").doesNotContain("钥匙");
		assertThat(new WorldGenPromptBuilder(registry).buildWorldPrompt("animal_life")).contains(revised);
	}
}
