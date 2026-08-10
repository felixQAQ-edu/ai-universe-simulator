package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一生制时钟(ADR-020 刀 5,根治 F-020)。
 *
 * <p><b>钉的是「回合号 → 阶段」这张表本身</b>——它一旦定死会长期约束《寻常》,
 * 且下一个一生制世界(动物人生)会照着它的<b>形状</b>而不是它的<b>数字</b>来写。
 * 边界逐个钉:每段的首回合与末回合都取到,防「差一」悄悄漂移。
 */
class LifeStageClockTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	// ── 表本身(纯函数)────────────────────────────────────────────────

	@Test
	void stageTableBoundariesAreExact_ADR020Knife5() {
		// 反解自 ADR-020 §2 密度表;⚠️ 暮年段(T36-43)是刀 5 新增的,原表没有(见 §2 订正块)。
		assertThat(LifeStage.of(1)).isEqualTo(LifeStage.CHILDHOOD);
		assertThat(LifeStage.of(2)).isEqualTo(LifeStage.ADOLESCENCE);
		assertThat(LifeStage.of(7)).isEqualTo(LifeStage.ADOLESCENCE);
		assertThat(LifeStage.of(8)).isEqualTo(LifeStage.YOUTH);
		assertThat(LifeStage.of(19)).isEqualTo(LifeStage.YOUTH);
		assertThat(LifeStage.of(20)).isEqualTo(LifeStage.MIDLIFE);
		assertThat(LifeStage.of(27)).isEqualTo(LifeStage.MIDLIFE);
		assertThat(LifeStage.of(28)).isEqualTo(LifeStage.OLD_AGE);
		assertThat(LifeStage.of(35)).isEqualTo(LifeStage.OLD_AGE);
		assertThat(LifeStage.of(36)).isEqualTo(LifeStage.TWILIGHT);
		assertThat(LifeStage.of(43)).isEqualTo(LifeStage.TWILIGHT);
		assertThat(LifeStage.of(44)).isEqualTo(LifeStage.FINAL);
		// 刀 4 那局跑到 200 回合仍未收束 —— 时钟对越界回合必须仍有定义(不抛、不回落幼年)。
		assertThat(LifeStage.of(200)).isEqualTo(LifeStage.FINAL);
	}

	@Test
	void stageTableCoversEveryTurnWithoutGapOrOverlap() {
		for (int t = 1; t <= 300; t++) {
			assertThat(LifeStage.of(t)).as("T%d 必须落在且只落在一个阶段", t).isNotNull();
		}
	}

	@Test
	void exitAbsentInChildhoodAndAdolescenceOnly_AndWordingIsUnique() {
		// ADR-020 §2 补记第 1 条的修订(Felix 2026-08-10):出口的前提是你有一个可以放弃的人生。
		assertThat(LifeStage.CHILDHOOD.hasExit()).isFalse();
		assertThat(LifeStage.ADOLESCENCE.hasExit()).isFalse();

		Set<String> wordings = new HashSet<>();
		for (LifeStage s : LifeStage.values()) {
			if (s.hasExit()) {
				assertThat(s.exitText()).isNotBlank();
				assertThat(wordings.add(s.exitText())).as("%s 的出口措辞与别的阶段重复了", s).isTrue();
			}
		}
		assertThat(wordings).hasSize(5); // 青年 / 中年 / 老年 / 暮年 / 末段
	}

	/**
	 * ⚠️ <b>这条是「时钟是否生效」的可见证据</b>(ADR-020 §2 补记第 2 条):
	 * 若 T10 与 T90 的出口文字相同,说明时钟没走。它是刀 6 冒烟第一眼要看的东西,
	 * 故在单测里也钉一遍——出口措辞由查表得出,两者<b>必然</b>不同。
	 */
	@Test
	void exitWordingDiffersAcrossDistantTurns_visibleClockProbe() {
		assertThat(LifeStage.of(10).exitText()).isNotEqualTo(LifeStage.of(90).exitText());
		assertThat(LifeStage.of(10).exitText()).isEqualTo("不再往下想了,就这样过"); // 青年
		assertThat(LifeStage.of(90).exitText()).isEqualTo("不等了");               // 末段
	}

	@Test
	void exitCopyIsAnInWorldActionNotAUiControl_ADR020Decision4() {
		// §2 补记第 4 条:是选项不是菜单项。UI 措辞一律不得出现。
		for (LifeStage s : LifeStage.values()) {
			if (s.hasExit()) {
				assertThat(s.exitText())
						.doesNotContain("结束").doesNotContain("退出").doesNotContain("游戏")
						.doesNotContain("菜单").doesNotContain("放弃本局");
			}
		}
		assertThat(LifeStage.EXIT_HINT).doesNotContain("结束").doesNotContain("游戏");
	}

	// ── 注入(prompt 侧)──────────────────────────────────────────────

	private Engine lifeSimEngineAtTurn(int completedTurns) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.putArray("archetypes").add("life_sim");
		world.putObject("character").putObject("attributes")
				.put("vigor", 60).put("longing", 50).put("crossroads", 40).put("ties", 50);
		world.putArray("rules");
		world.putArray("endings");
		if (completedTurns == 0) {
			return new Engine(world, mapper);
		}
		ObjectNode doc = mapper.createObjectNode();
		doc.put("schemaVersion", "0.4");
		doc.set("world", world);
		ObjectNode state = doc.putObject("state");
		state.put("turn", completedTurns);
		state.put("status", "ongoing");
		state.put("timeline", "");
		state.put("logSummary", "");
		state.putArray("log");
		doc.putArray("triggered");
		doc.putArray("issues");
		return Engine.restore(doc, mapper, Set.of("ties"), Map.of("vigor", "气力"),
				Set.of("longing", "crossroads"));
	}

	@Test
	void clockInjectsCurrentTurnAndStage_deliveredNotDescribed() {
		String p = builder.buildTurnPrompt(lifeSimEngineAtTurn(0), "A", "行动");
		// 兑现语义三件:回合号 / 阶段 / 必须推进(不是「密度随生命呼吸」那种描述)。
		assertThat(p).contains("现在是第 1 回合").contains("【幼年】")
				.contains("本回合必须推进时间,这是硬要求不是风格建议")
				.contains("本回合一口气覆盖约 7 年");
		// 被 F-020 证伪的那张描述表不得残留(两个口径不得并存)。
		assertThat(p).doesNotContain("密度不匀速").doesNotContain("0-6 岁压进 1 个回合");
	}

	@Test
	void clockFollowsTurnNumberAcrossStages() {
		// 注入的是**正在生成的那一回合** = turn()+1,与骨架末行「请推进第 N 回合」同一个 N。
		assertThat(builder.buildTurnPrompt(lifeSimEngineAtTurn(19), "A", "行动"))
				.contains("现在是第 20 回合").contains("【中年】").contains("晚约 3 年");
		assertThat(builder.buildTurnPrompt(lifeSimEngineAtTurn(43), "A", "行动"))
				.contains("现在是第 44 回合").contains("【末段】").contains("晚一天到数日");
	}

	@Test
	void clockNeverLeaksAgeNumbersDirective() {
		String p = builder.buildTurnPrompt(lifeSimEngineAtTurn(19), "A", "行动");
		assertThat(p).contains("绝不显示岁数"); // 年龄区间只作设计标注注入,禁止写给玩家
	}

	@Test
	void convergenceWindowInjectedWithoutHardCap_followsFusionPrecedent() {
		String p = builder.buildTurnPrompt(lifeSimEngineAtTurn(0), "A", "行动");
		// 照融合先例:给收敛窗口,不给引擎强制(FUSION_TURN_DIRECTIVE 逐字「不写死回合数上限」)。
		assertThat(p).contains("全局预期在第 45-55 回合走到寿终");
		assertThat(p).doesNotContain("强制结束").doesNotContain("必须在第 55 回合结束");
	}

	@Test
	void closingArcAndActionEchoDirectivesPresent() {
		String p = builder.buildTurnPrompt(lifeSimEngineAtTurn(0), "A", "行动");
		// B:按下不是立刻结局 + timeline 是唯一跨回合载体(勘察项 4/5:无处可挂,只有 timeline)。
		assertThat(p).contains("【「就到这里」与收束段】").contains("不立刻结局")
				.contains("3-5 个回合走到寿终结局").contains("timeline 里写明")
				.contains("漏写等于收束段丢失");
		// D:动作回声(F-020 独立故障)。
		assertThat(p).contains("不得回声刚发生过的动作").contains("不得作为本回合的可选项再次出现");
		// §8 气力下限的锚点由「最后 3-5 个回合」改为可数的回合号(F-020:以阶段为条件者无从生效)。
		assertThat(p).contains("第 44 回合起").contains("气力不得低于 15");
	}

	@Test
	void fourExistingWorldsGetNoClock_parityGuard() {
		for (String a : new String[] { "rules_creepy", "apocalypse", "cthulhu", "cultivation" }) {
			ObjectNode world = mapper.createObjectNode();
			world.putArray("archetypes").add(a);
			world.putObject("character").putObject("attributes").put("hp", 50);
			world.putArray("rules");
			world.putArray("endings");
			String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "行动");
			assertThat(p).as("%s 不得拿到一生制时钟", a)
					.doesNotContain("一生制时钟").doesNotContain("就到这里")
					.doesNotContain("不得回声刚发生过的动作");
		}
	}
}
