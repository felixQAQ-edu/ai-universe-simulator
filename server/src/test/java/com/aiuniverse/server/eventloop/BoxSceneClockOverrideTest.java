package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.BoxScene.Category;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxSceneTables.StageText;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-028 刀 2 补充 · T1–17 时钟段:时钟表放纸箱段(第一组),结算后、窗口内的段信息由局面层按<b>结果</b>覆盖
 * (被带走 → 第二组,被留下 → 第三组);时钟表 15–17 那一格对新局永远不渲染;旧局不走覆盖。
 *
 * <p>三组文案逐字取自 ADR-028「附录 · 第二刀补充文案 · T11–17 时钟段」—— 只接受「附录 → 表」一个方向。
 */
class BoxSceneClockOverrideTest {

	private static final java.nio.file.Path ADR =
			java.nio.file.Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md");

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder prompts = new TurnPromptBuilder(new ArchetypeRegistry());

	// ── 附录 → 表 ───────────────────────────────────────────────────────

	/** 读附录某一组:四级标题之下,「##### 段名 / 设计标注 / 推进语」各取下一行非空正文。 */
	private static StageText appendixGroup(String heading) throws Exception {
		List<String> all = Files.readAllLines(ADR);
		int start = all.indexOf("### 第二刀补充文案 · T11–17 时钟段(Felix 2026-10-01 定稿,照录)");
		assertThat(start).as("ADR 里找不到补充文案一节").isGreaterThanOrEqualTo(0);
		int h = all.subList(start, all.size()).indexOf(heading);
		assertThat(h).as("ADR 里找不到「%s」", heading).isGreaterThanOrEqualTo(0);
		Map<String, String> fields = new LinkedHashMap<>();
		String key = null;
		for (int k = start + h + 1; k < all.size() && !all.get(k).startsWith("#### "); k++) {
			String x = all.get(k);
			if (x.startsWith("##### ")) {
				key = x.substring(6);
			} else if (!x.isBlank() && key != null && !fields.containsKey(key)) {
				fields.put(key, x);
			}
		}
		assertThat(fields.keySet()).containsExactly("段名", "设计标注", "推进语");
		return new StageText(fields.get("段名"), fields.get("设计标注"), fields.get("推进语"));
	}

	private static final String G1 = "#### 一、纸箱局面｜T11–14";
	private static final String G2 = "#### 二、余波·被带走｜R1：T14–17；R2：T15–17";
	private static final String G3 = "#### 三、余波·被留下｜R3a、R3b、R3c：T15–17";

	private static StageText of(LifeStage s) {
		return new StageText(s.label(), s.spanNote(), s.advanceClause());
	}

	@Test
	void clockTableT11To14IsGroup1_andAftermathGroupsLiveInTheSceneTable_verbatim() throws Exception {
		LifeStageTable t = LifeStageTables.of("animal_life");
		StageText g1 = appendixGroup(G1);
		for (int turn = 11; turn <= 14; turn++) {
			assertThat(of(t.stageAt(turn))).as("T" + turn).isEqualTo(g1);
		}
		assertThat(BoxSceneTables.aftermathStage("animal_life", Path.R1)).isEqualTo(appendixGroup(G2));
		assertThat(BoxSceneTables.aftermathStage("animal_life", Path.R2)).isEqualTo(appendixGroup(G2));
		for (Path p : List.of(Path.R3A, Path.R3B, Path.R3C)) {
			assertThat(BoxSceneTables.aftermathStage("animal_life", p)).as(p.name()).isEqualTo(appendixGroup(G3));
		}
		// 15–17 那一格是占位,只能取三组之一(取的是第一组,理由见 LifeStageTables 注释)。
		for (int turn = 15; turn <= 17; turn++) {
			assertThat(of(t.stageAt(turn))).as("T" + turn).isEqualTo(g1);
		}
	}

	@Test
	void t1To10KeepTheOldThreeSentences_t18OnwardUnchanged_convergenceUnchanged() {
		LifeStageTable t = LifeStageTables.of("animal_life");
		LifeStageTable legacy = LifeStageTables.legacyOf("animal_life");
		for (int turn = 1; turn <= 10; turn++) {
			assertThat(of(t.stageAt(turn))).as("T" + turn).isEqualTo(of(legacy.stageAt(turn)));
		}
		assertThat(t.stageAt(10).toTurn()).isEqualTo(10);
		assertThat(t.stageAt(18).label()).isEqualTo("变动初期");
		assertThat(t.stageAt(42).label()).isEqualTo("末段");
		assertThat(t.convergeFrom()).isEqualTo(45);
		assertThat(t.convergeTo()).isEqualTo(48);
		assertThat(t.finalStageFromTurn()).isEqualTo(42);
		// 旧局表一个字不动:T11–17 仍是「屋里」与「断裂」。
		assertThat(legacy.stageAt(11).label()).isEqualTo("屋里");
		assertThat(legacy.stageAt(15).label()).isEqualTo("断裂");
	}

	// ── 回合路径:按结果覆盖 ──────────────────────────────────────────────

	private ObjectNode world() {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4").put("mode", "single");
		world.putArray("archetypes").add("animal_life");
		world.putObject("world").put("title", "屋里的灯").put("background", "…")
				.put("dangerLevel", "low").put("tone", "克制");
		world.putObject("character").putObject("attributes")
				.put("body", 80).put("warmth", 60).put("ground", 50).put("close", 50);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "hit").put("title", "撞上")
				.put("condition", "【身子】归零").put("outcome", "failure").put("reached", false);
		return world;
	}

	private GameSession sessionAt(int turn, BoxSceneState st) {
		Engine engine = new Engine(world(), mapper);
		for (int i = 0; i < turn; i++) {
			engine.applyNoOp("屋里的日子", "A");
		}
		ArrayNode acts = mapper.createArrayNode();
		acts.addObject().put("id", "A").put("text", "趴着");
		acts.addObject().put("id", "B").put("text", "闻闻");
		GameSession s = new GameSession("save-clock", engine, acts);
		s.setBoxScene(st);
		return s;
	}

	private static String ok() {
		return "它把鼻子贴近地面。" + SentinelSplitter.SENTINEL
				+ "{\"stateUpdate\":{\"body\":80,\"warmth\":60,\"ground\":50,\"close\":50,\"timeline\":\"日子\"},"
				+ "\"availableActions\":[{\"id\":\"A\",\"text\":\"a\",\"hint\":\"h\"},"
				+ "{\"id\":\"B\",\"text\":\"b\",\"hint\":\"h\"},{\"id\":\"C\",\"text\":\"c\",\"hint\":\"h\"}],"
				+ "\"ending\":null}";
	}

	/** 从第 10 回合起按 {@code choices} 逐回合推进(第 i 个选择生成第 11+i 回合),返回每回合 prompt。 */
	private Map<Integer, String> run(BoxSceneState st, String... choices) {
		GameSession s = sessionAt(10, st);
		Map<Integer, String> out = new LinkedHashMap<>();
		for (String c : choices) {
			int next = s.engine().turn() + 1;
			BoxSceneWiringTest.ScriptedLlm llm = new BoxSceneWiringTest.ScriptedLlm();
			llm.script(ok());
			new EventLoopService(llm, prompts, mapper).execute(s, c, new BoxSceneWiringTest.Sink());
			out.put(next, llm.prompts.get(0));
		}
		return out;
	}

	/** 时钟契约里那一句:「…正处于【段名】(设计标注:…)」+ 推进语。 */
	private static void assertGroup(String prompt, StageText g, String why) {
		assertThat(prompt).as(why).contains("正处于【" + g.label() + "】(设计标注:" + g.spanNote() + ")")
				.contains(g.advanceClause());
	}

	private static void assertNotGroup(String prompt, StageText g, String why) {
		assertThat(prompt).as(why).doesNotContain(g.label()).doesNotContain(g.spanNote())
				.doesNotContain(g.advanceClause());
	}

	private static final List<String> OLD_RUPTURE = List.of("【断裂】", "被丢下的那几天");

	private void checkPath(Map<Integer, String> p, int firstAftermath, StageText expect, StageText other,
			StageText box, Path path) {
		for (Map.Entry<Integer, String> e : p.entrySet()) {
			int turn = e.getKey();
			String why = path + " T" + turn;
			for (String old : OLD_RUPTURE) {
				assertThat(e.getValue()).as(why).doesNotContain(old);
			}
			if (turn < firstAftermath) {
				assertGroup(e.getValue(), box, why);
				assertNotGroup(e.getValue(), expect, why);
				assertNotGroup(e.getValue(), other, why);
			} else if (turn <= 17) {
				assertGroup(e.getValue(), expect, why);
				assertNotGroup(e.getValue(), other, why);
				assertNotGroup(e.getValue(), box, why); // 时钟表 15–17 那一格(第一组占位)从不渲染
			} else {
				assertThat(e.getValue()).as(why).contains("正处于【变动初期】");
				assertNotGroup(e.getValue(), expect, why);
				assertNotGroup(e.getValue(), box, why);
			}
		}
	}

	@Test
	void r1_t14To17UseGroup2() throws Exception {
		BoxSceneState st = BoxSceneState.fresh();
		Map<Integer, String> p = run(st, "A", "A", "A", "A", "A", "A", "A", "A");
		assertThat(st.result).isEqualTo(Path.R1);
		assertThat(st.settledTurn).isEqualTo(14);
		assertThat(p.keySet()).containsExactly(11, 12, 13, 14, 15, 16, 17, 18);
		checkPath(p, 14, appendixGroup(G2), appendixGroup(G3), appendixGroup(G1), Path.R1);
	}

	@Test
	void r2_t15To17UseGroup2() throws Exception {
		BoxSceneState st = BoxSceneState.fresh();
		Map<Integer, String> p = run(st, "A", "A", "A", "B", "A", "A", "A", "A");
		assertThat(st.result).isEqualTo(Path.R2);
		assertThat(st.settledTurn).isEqualTo(15);
		checkPath(p, 15, appendixGroup(G2), appendixGroup(G3), appendixGroup(G1), Path.R2);
	}

	@Test
	void r3abc_t15To17UseGroup3() throws Exception {
		Map<String, Path> byChoice = Map.of("A", Path.R3A, "B", Path.R3B, "C", Path.R3C);
		for (Map.Entry<String, Path> c : byChoice.entrySet()) {
			BoxSceneState st = BoxSceneState.fresh();
			Map<Integer, String> p = run(st, "A", "C", "C", "C", c.getKey(), "A", "A", "B");
			assertThat(st.result).as(c.getKey()).isEqualTo(c.getValue());
			assertThat(st.settledTurn).isEqualTo(15);
			assertThat(c.getValue().category).isEqualTo(Category.LEFT);
			// ADR-028 §已决 L 第 2 条(及其修正):B1–B3 回合按渲染文本(设计标注删一句、推进语 = 第三组推进语前半句);
			// 原断言用附录第三组原文,已按新口径改写。附录原文本身由 clockTableT11To14IsGroup1_andAftermathGroupsLiveInTheSceneTable_verbatim 照旧守。
			StageText g3 = appendixGroup(G3);
			StageText rendered = new StageText(g3.label(),
					g3.spanNote().replace(BoxSceneDecisionLTest.REMOVED_SENTENCE, ""), BoxSceneDecisionLTest.BEAT_ADVANCE);
			checkPath(p, 15, rendered, appendixGroup(G2), appendixGroup(G1), c.getValue());
			for (int turn = 15; turn <= 17; turn++) {
				assertThat(p.get(turn)).as("T" + turn).doesNotContain(BoxSceneDecisionLTest.REMOVED_SENTENCE)
						.doesNotContain(g3.advanceClause());
			}
		}
	}

	@Test
	void legacyGamesNeverTakeTheOverride() {
		// 旧局:局面层整局跳过 → 编排为 null → 不覆盖;时钟走旧表的「断裂」。
		Map<Integer, String> p = run(BoxSceneState.legacyMarker(), "A", "A", "A", "A", "A", "A", "A");
		assertThat(p.get(15)).contains("正处于【断裂】").doesNotContain("新屋子·最初的几天")
				.doesNotContain("旧屋·空下来的第一天").doesNotContain("屋里·纸箱出现的几天");
		// 直接给构建器一个覆盖值,旧局也忽略它。
		Engine e = AnimalLifeLegacyGoldenTest.engine(mapper, 15, 40);
		String legacy = prompts.buildTurnPrompt(e, "A", "趴着", "", "", true,
				BoxSceneTables.aftermathStage("animal_life", Path.R1));
		assertThat(legacy).isEqualTo(prompts.buildTurnPrompt(e, "A", "趴着", "", "", true));
	}

	@Test
	void overrideOnlyReplacesTheThreeItemsInTheClockContract() {
		Engine e = AnimalLifeLegacyGoldenTest.engine(mapper, 15, 40);
		StageText g = BoxSceneTables.aftermathStage("animal_life", Path.R3A);
		String plain = prompts.buildTurnPrompt(e, "A", "趴着", "", "", false);
		String over = prompts.buildTurnPrompt(e, "A", "趴着", "", "", false, g);
		LifeStage cell = LifeStageTables.of("animal_life").stageAt(16);
		List<String> diffs = new ArrayList<>();
		// 把三项换回占位值后两份必须逐字节相同 —— 证明别处一个字节都没动。
		String back = over.replace(g.label(), cell.label()).replace(g.spanNote(), cell.spanNote())
				.replace(g.advanceClause(), cell.advanceClause());
		if (!back.equals(plain)) {
			diffs.add("覆盖改动了三项以外的内容");
		}
		assertThat(diffs).isEmpty();
		assertThat(over).isNotEqualTo(plain);
	}
}
