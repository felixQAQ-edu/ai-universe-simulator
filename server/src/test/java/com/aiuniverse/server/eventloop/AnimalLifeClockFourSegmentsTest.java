package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;

/**
 * ADR-028 刀 2b · 《动物人生》第 18–41 回合分四段(§已决 C),末段与收敛不动;旧局仍用旧五段表。
 * 四段说明与三条共同限制逐字取自 ADR(只接受 ADR → 代码一个方向)。
 */
class AnimalLifeClockFourSegmentsTest {

	private static final Path ADR = Path.of("../docs/adr/ADR-028-box-scene-changeable-left-behind.md");

	private final LifeStageTable t = LifeStageTables.of("animal_life");

	@Test
	void fourSegmentsWithLabelsSpanNotesAndAdvanceClauses() {
		assertThat(List.of(t.stageAt(18), t.stageAt(21), t.stageAt(22), t.stageAt(27), t.stageAt(28),
				t.stageAt(35), t.stageAt(36), t.stageAt(41)).stream().map(LifeStage::label))
				.containsExactly("变动初期", "变动初期", "旧规律松动", "旧规律松动", "试出新的办法", "试出新的办法",
						"新处境中的日常", "新处境中的日常");
		assertThat(t.stageAt(18).spanNote()).isEqualTo("熟悉秩序消失后的早期");
		assertThat(t.stageAt(22).spanNote()).isEqualTo("旧规律连续失效");
		assertThat(t.stageAt(28).spanNote()).isEqualTo("尝试建立新规律");
		assertThat(t.stageAt(36).spanNote()).isEqualTo("在新处境中生活");
		LifeStageTable legacy = LifeStageTables.legacyOf("animal_life");
		assertThat(t.stageAt(18).advanceClause()).isEqualTo("本回合比上一回合晚约数天");
		assertThat(t.stageAt(22).advanceClause()).isEqualTo(legacy.stageAt(18).advanceClause());
		assertThat(t.stageAt(28).advanceClause()).isEqualTo(legacy.stageAt(28).advanceClause());
		assertThat(t.stageAt(36).advanceClause()).isEqualTo(legacy.stageAt(28).advanceClause());
	}

	/**
	 * 末段与收敛不动。刀 2 补充起 T1–17 不再与旧表相同:T1–10 三句沿用旧「屋里」原文(只收窄范围),
	 * T11–17 换成纸箱段 —— 那部分由 {@code BoxSceneClockOverrideTest} 逐字守;这里只守「末段照旧」与 T1–10 三句。
	 */
	@Test
	void finalStageAndConvergenceUnchanged_andT1To10KeepTheOldThreeSentences() {
		assertThat(t.finalStageFromTurn()).isEqualTo(42);
		assertThat(t.stageAt(42).label()).isEqualTo("末段");
		assertThat(t.convergeFrom()).isEqualTo(45);
		assertThat(t.convergeTo()).isEqualTo(48);
		LifeStageTable legacy = LifeStageTables.legacyOf("animal_life");
		for (int turn : List.of(42, 60)) {
			assertThat(t.stageAt(turn)).as("T" + turn).isEqualTo(legacy.stageAt(turn));
		}
		for (int turn : List.of(1, 10)) {
			LifeStage now = t.stageAt(turn);
			LifeStage old = legacy.stageAt(turn);
			assertThat(List.of(now.label(), now.spanNote(), now.advanceClause())).as("T" + turn)
					.isEqualTo(List.of(old.label(), old.spanNote(), old.advanceClause()));
		}
		assertThat(t.stageAt(11).label()).isNotEqualTo(legacy.stageAt(11).label());
	}

	@Test
	void guidanceIsTheAdrDescriptionPlusTheThreeLimits_verbatim() throws Exception {
		List<String> adr = Files.readAllLines(ADR);
		List<String> limits = adr.subList(adr.indexOf("三条共同限制(照录):") + 2, adr.indexOf("三条共同限制(照录):") + 5);
		for (int turn : List.of(18, 22, 28, 36)) {
			LifeStage s = t.stageAt(turn);
			String desc = adr.stream().filter(x -> x.contains("|" + s.label() + "**:")).findFirst().orElseThrow();
			desc = desc.substring(desc.indexOf("**:") + 3);
			assertThat(s.guidance()).startsWith("【阶段说明 · " + s.label() + "】" + desc + "\n共同限制:\n")
					.endsWith(String.join("\n", limits));
		}
		for (int turn : List.of(1, 15, 42)) {
			assertThat(t.stageAt(turn).guidance()).as("T" + turn).isNull();
		}
	}

	@Test
	void guidanceIsRenderedAfterTheTrunkAndBeforeTheFragment_onlyForNewGames() {
		ObjectMapper mapper = new ObjectMapper();
		TurnPromptBuilder b = new TurnPromptBuilder(new ArchetypeRegistry());
		Engine e = AnimalLifeLegacyGoldenTest.engine(mapper, 24, 40); // 正在生成第 25 回合 = 旧规律松动
		String p = b.buildTurnPrompt(e, "A", "趴着", "",
				BoxSceneTables.situationFragment(BoxScene.Situation.NEW_HOME), false);
		int trunkEnd = p.indexOf("(10)【最后一回合是活着的】");
		int guide = p.indexOf("【阶段说明 · 旧规律松动】");
		int frag = p.indexOf("【处境片段");
		assertThat(trunkEnd).isPositive();
		assertThat(guide).isGreaterThan(trunkEnd);
		assertThat(frag).isGreaterThan(guide);
		assertThat(b.buildTurnPrompt(e, "A", "趴着", "", "", true)).doesNotContain("【阶段说明");
	}
}
