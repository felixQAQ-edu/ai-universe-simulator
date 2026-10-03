package com.aiuniverse.server.eventloop;

import static com.aiuniverse.server.eventloop.BoxSceneTables.ANIMAL_LIFE_BOX;
import static com.aiuniverse.server.eventloop.BoxSceneTables.ANIMAL_LIFE_POOLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.BoxScene.Beat;
import com.aiuniverse.server.eventloop.BoxScene.Path;
import com.aiuniverse.server.eventloop.BoxScene.PoolIntent;
import com.aiuniverse.server.eventloop.BoxScene.Situation;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-028 §已决 K(F-033):叙事素材在注入回合 prompt 时由「它」转「你」,外加人称硬约束。
 *
 * <p>转换<b>按字段</b>确定(阶段征兆含阶段 4 分叉、反馈事实、记忆事实与共同兜底、两种余波征兆、
 * 离开反馈、补位征兆、两个意图池的习惯短语),不按条目数;数据表原文、落盘内容不变;
 * 设计标注、状态标题、处境片段里的「它」不被改写(不得全局替换)。
 */
class BoxSceneNarrativePersonTest {

	/** Felix 2026-10-03 原文(ADR-028 §已决 K),逐字。 */
	static final String PERSON_RULE = "【叙事人称 · 硬约束】本回合叙事正文一律使用第二人称「你」指代玩家所扮演的动物，"
			+ "不得改用「它」，也不得在同一段正文里混用「你」与「它」。提示中的设计说明、状态标题和处境边界可以使用「它」；"
			+ "其中的「它」仍指这只动物，写进正文时必须改成「你」。";

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	private static BoxSceneTurn.Plan planOf(String feedback, String omen, List<String> facts, String habit) {
		return new BoxSceneTurn.Plan(feedback, omen, facts, habit, null, null, false,
				0, null, null, null, null, null);
	}

	/** 局面块里每条素材行冒号之后的内容(结构标题在冒号之前,允许带「它」)。 */
	private static List<String> materialParts(String block) {
		List<String> out = new ArrayList<>();
		for (String line : block.split("\n")) {
			if (line.startsWith("- ") && line.contains("):")) {
				out.add(line.substring(line.indexOf("):") + 2));
			}
		}
		return out;
	}

	// ── 1. 全部叙事素材渲染后不含「它」(按字段遍历)──────────────────────

	@Test
	void everyNarrativeMaterialRendersWithoutIt_viaThePromptBlock() {
		List<String> materials = BoxSceneTurn.narrativeMaterials(ANIMAL_LIFE_BOX, ANIMAL_LIFE_POOLS);
		// 字段覆盖(按字段而不是条目数):阶段 4 两个分叉、两种余波、R1 补位、两个池的全部习惯短语、离开反馈、共同兜底
		List<String> expected = new ArrayList<>();
		for (Beat b : List.of(ANIMAL_LIFE_BOX.stage4().high(), ANIMAL_LIFE_BOX.stage4().low(),
				ANIMAL_LIFE_BOX.r1Turn17Fill())) {
			expected.add(b.omen());
		}
		ANIMAL_LIFE_BOX.takenAftermath().forEach(b -> expected.add(b.omen()));
		ANIMAL_LIFE_BOX.leftAftermath().forEach(b -> expected.add(b.omen()));
		ANIMAL_LIFE_BOX.stages().forEach(b -> {
			expected.add(b.omen());
			b.options().forEach(o -> expected.add(o.feedback()));
		});
		ANIMAL_LIFE_POOLS.newHome().forEach(p -> expected.add(p.habit()));
		ANIMAL_LIFE_POOLS.emptyHome().forEach(p -> expected.add(p.habit()));
		expected.addAll(ANIMAL_LIFE_BOX.memoryFacts().values());
		expected.add(ANIMAL_LIFE_BOX.leftCommonFact());
		expected.add(ANIMAL_LIFE_BOX.leaveFeedback());
		assertThat(materials).containsExactlyInAnyOrderElementsOf(expected);
		assertThat(materials).anyMatch(m -> m.contains("它")); // 正对照:确实有要转换的东西

		for (String m : materials) {
			assertThat(BoxSceneTurn.narrated(m)).as(m).doesNotContain("它");
			String block = BoxSceneTurn.promptBlock(planOf(m, m, List.of(m, m), m));
			List<String> parts = materialParts(block);
			assertThat(parts).as(m).hasSize(4).allSatisfy(part -> assertThat(part).doesNotContain("它"));
			assertThat(block).as(m).contains(BoxSceneTurn.narrated(m));
		}
	}

	@Test
	void habitPhrasesAreConverted() {
		for (PoolIntent p : ANIMAL_LIFE_POOLS.emptyHome()) {
			assertThat(BoxSceneTurn.promptBlock(planOf(null, null, List.of(), p.habit())))
					.contains(BoxSceneTurn.narrated(p.habit())).doesNotContain(p.habit());
		}
		assertThat(BoxSceneTurn.narrated("这些天，它常跟在孩子身后。")).isEqualTo("这些天，你常跟在孩子身后。");
	}

	// ── 2 / 3. 两条点名的原句 ─────────────────────────────────────────

	@Test
	void namedSentencesRenderAsSpecified() {
		assertThat(BoxSceneTurn.promptBlock(planOf("孩子叫了它的名字", null, List.of(), null)))
				.contains("孩子叫了你的名字").doesNotContain("孩子叫了它的名字");
		String r1 = ANIMAL_LIFE_BOX.memoryFacts().get(Path.R1);
		assertThat(r1).isEqualTo("门外响着车声时，它自己跳进了那个正被人抱走的箱子。");
		assertThat(BoxSceneTurn.promptBlock(planOf(null, null, List.of(r1), null)))
				.contains("门外响着车声时，你自己跳进了那个正被人抱走的箱子。");
	}

	// ── 4. 设计标注、状态标题、处境片段里的「它」不被改写 ──────────────────

	@Test
	void designNotesTitlesAndSituationFragmentsKeepIt() {
		Engine engine = AnimalLifeLegacyGoldenTest.engine(mapper, 15, 40);
		BoxSceneTurn.Plan plan = planOf("孩子叫了它的名字", ANIMAL_LIFE_BOX.leftAftermath().get(1).omen(),
				BoxScene.memoryFacts(ANIMAL_LIFE_BOX, Path.R3A), ANIMAL_LIFE_POOLS.emptyHome().get(0).habit());
		BoxSceneTables.StageText left = BoxSceneTables.aftermathStage("animal_life", Path.R3A);
		String fragment = BoxSceneTables.situationFragment(Situation.EMPTY_HOME);
		String prompt = builder.buildTurnPrompt(engine, "A", "趴着", BoxSceneTurn.promptBlock(plan), fragment,
				false, left);

		assertThat(left.spanNote()).contains("它");
		assertThat(prompt).contains(left.spanNote());                 // 设计标注原样
		assertThat(fragment).contains("它");
		assertThat(prompt).contains(fragment);                         // 处境片段原样
		assertThat(prompt).contains("- 它带着的记忆(").contains("- 它这些天反复做的事("); // 结构标题原样
		assertThat(prompt).contains(PERSON_RULE);                      // 规则里的「它」原样
		assertThat(prompt).contains("孩子叫了你的名字").doesNotContain("孩子叫了它的名字");
		assertThat(BoxSceneTables.situationFragment(Situation.OUTSIDE)).contains("它"); // 其他片段也未被改动
	}

	// ── 人称规则:只进新局主干,所有回合都带、恰好一次;旧局不带 ─────────────────

	@Test
	void personRuleAppearsExactlyOnceInEveryNewGameTurn_andNeverInLegacy() {
		for (int turn : new int[] { 0, 5, 9, 12, 16, 20, 30, 44 }) {
			Engine e = AnimalLifeLegacyGoldenTest.engine(mapper, turn, 40);
			String fresh = builder.buildTurnPrompt(e, "A", "趴着", "", "", false);
			assertThat(fresh.split(java.util.regex.Pattern.quote(PERSON_RULE), -1)).as("T%d", turn).hasSize(2);
			String legacy = builder.buildTurnPrompt(e, "A", "趴着", "", "", true);
			assertThat(legacy).as("T%d legacy", turn).doesNotContain("【叙事人称");
		}
	}

	// ── 安全阀 ───────────────────────────────────────────────────────

	@Test
	void materialContainingThemIsRejected() {
		assertThatThrownBy(() -> BoxSceneTurn.narrated("它们从门口走过"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("它们");
		assertThatThrownBy(() -> BoxSceneTurn.promptBlock(planOf("它们从门口走过", null, List.of(), null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(BoxSceneTurn.narrated(null)).isNull();
	}

	// ── 数据表原文不变(转换是渲染时的纯函数)───────────────────────────

	@Test
	void tablesKeepTheOriginalIt() {
		BoxSceneTurn.promptBlock(planOf(null, null, BoxScene.memoryFacts(ANIMAL_LIFE_BOX, Path.R3A), null));
		assertThat(ANIMAL_LIFE_BOX.memoryFacts().get(Path.R1)).contains("它自己跳进");
		assertThat(ANIMAL_LIFE_BOX.leaveFeedback()).startsWith("它从门缝挤了出去");
		assertThat(BoxSceneTables.aftermathStage("animal_life", Path.R1).spanNote()).contains("它");
	}
}
