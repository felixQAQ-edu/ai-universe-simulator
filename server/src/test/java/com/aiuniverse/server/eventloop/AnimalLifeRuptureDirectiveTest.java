package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 《动物人生》回合侧主干与处境片段的守护(ADR-028 刀 2b;前身是 F-028 的断裂段素材守护)。
 *
 * <p><b>刀 2b 之后它在守什么</b>:旧第 (8) 条的断裂素材与「第 18 回合仍在屋里即为写错」<b>已从新主干删除</b>
 * —— 那条结论在纸箱局面之后不成立(被带走的分支第 18 回合就在新屋里)。断裂改由局面层的征兆与反馈承担,
 * 地点由<b>会话保存的权威处境</b>决定,不由时钟决定。原 (7)(9) 移入屋外片段。
 *
 * <p><b>旧局</b>(有旧局标记的存档)仍走旧指令 —— 素材与转折条件都还在,逐字节由
 * {@code AnimalLifeLegacyGoldenTest} 守住;这里只验「旧素材只在旧局里」。
 */
class AnimalLifeRuptureDirectiveTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	/** 旧第 (8) 条的断裂素材(逐条取不会与别处撞车的片段)。 */
	private static final List<String> OLD_RUPTURE_MATERIAL = List.of(
			"纸箱的味道",
			"撕胶带",
			"椅子上的东西被拿下来了",
			"两只手托起一个箱子",
			"一高一低",
			"放了一下,很久没有拿开",
			"门开着,没有合上",
			"四个凹进去的印子",
			"碗还在原来的地方");

	private static final String OLD_TRANSITION = "第 18 回合的场景若仍在屋里,即为写错";

	/** 只在屋外片段里的三样:(7) 推字分区、(9) 楼道口、屋外专属结局。 */
	private static final String RULE7 = "（7）【`OUTSIDE` · 接触种类与动词分区】";
	private static final String PUSH = "正文与选项中不得出现“推”字";
	private static final String RULE9 = "（9）【`OUTSIDE` 末段 · 楼道口边界】";
	private static final String STAIRWELL = "楼道口";
	private static final String OUTSIDE_ENDINGS = "【`OUTSIDE` 专属结局】";

	private static final String NEW_HOME_HEADER = "【处境片段 · 当前处境:新屋(以下只在这一处境下成立)】";
	private static final String EMPTY_HOME_HEADER = "【处境片段 · 当前处境:空下来的旧屋(以下只在这一处境下成立)】";
	private static final String OUTSIDE_HEADER = "【处境片段 · 当前处境:屋外(以下只在这一处境下成立)】";

	private Engine engineFor(String archetype) {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add(archetype);
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		registry.meta(archetype).attributes().forEach(ax -> attrs.put(ax.key(), 50));
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	private String animalPrompt() {
		return builder.buildTurnPrompt(engineFor("animal_life"), "A", "行动");
	}

	private String animalPrompt(BoxScene.Situation s) {
		return builder.buildTurnPrompt(engineFor("animal_life"), "A", "行动", "",
				BoxSceneTables.situationFragment(s), false);
	}

	private String legacyPrompt() {
		return builder.buildTurnPrompt(engineFor("animal_life"), "A", "行动", "", "", true);
	}

	@Test
	void newTrunkNoLongerCarriesTheOldRuptureMaterialOrTheForcedTransition() {
		for (BoxScene.Situation s : new BoxScene.Situation[] { null, BoxScene.Situation.NEW_HOME,
				BoxScene.Situation.EMPTY_HOME, BoxScene.Situation.OUTSIDE }) {
			String p = animalPrompt(s);
			for (String item : OLD_RUPTURE_MATERIAL) {
				assertThat(p).as("%s:旧断裂素材「%s」仍在新主干里", s, item).doesNotContain(item);
			}
			assertThat(p).as(String.valueOf(s)).doesNotContain(OLD_TRANSITION).doesNotContain("这三个回合结束时");
		}
	}

	/** 新主干逐字换上 7-D 的 (5)(8);(6)(10) 仍在。 */
	@Test
	void newTrunkCarriesReplacedRules5And8_andKeeps6And10() {
		String p = animalPrompt(null);
		assertThat(p).contains("**（5）【误读回收 · 按权威处境裁决】**")
				.contains("不得根据回合号、模型刚写出的地点或叙事中的一句话反推处境。")
				.contains("**（8）【局面与处境不得由时钟代替】**")
				.contains("空的处境不是上述三种处境中的任何一种；不得替它猜默认值。")
				.contains("(6)【逐字不变的句子 · 硬约束】")
				.contains("(10)【最后一回合是活着的】");
		assertThat(p).doesNotContain("(5)【误读回收 · 每回合的裁决】").doesNotContain("(7)【动词分区")
				.doesNotContain("(9)【末段落在楼道口");
	}

	@Test
	void emptySituationGetsNoFragment() {
		String p = animalPrompt(null);
		assertThat(p).doesNotContain("【处境片段").doesNotContain(RULE7).doesNotContain(RULE9)
				.doesNotContain(OUTSIDE_ENDINGS).doesNotContain("新屋共同边界").doesNotContain("旧屋共同边界");
	}

	@Test
	void newHomeGetsOnlyItsOwnFragment() {
		String p = animalPrompt(BoxScene.Situation.NEW_HOME);
		assertThat(p).contains(NEW_HOME_HEADER).contains("新屋共同边界:").contains("\n   - `NEW_HOME`：");
		assertThat(p).doesNotContain(EMPTY_HOME_HEADER).doesNotContain(OUTSIDE_HEADER)
				.doesNotContain("旧屋共同边界").doesNotContain("\n   - `EMPTY_HOME`：").doesNotContain("\n   - `OUTSIDE`：")
				.doesNotContain(RULE7).doesNotContain(PUSH).doesNotContain(RULE9).doesNotContain(STAIRWELL)
				.doesNotContain(OUTSIDE_ENDINGS);
	}

	@Test
	void emptyHomeGetsOnlyItsOwnFragment() {
		String p = animalPrompt(BoxScene.Situation.EMPTY_HOME);
		assertThat(p).contains(EMPTY_HOME_HEADER).contains("旧屋共同边界:").contains("\n   - `EMPTY_HOME`：");
		assertThat(p).doesNotContain(NEW_HOME_HEADER).doesNotContain(OUTSIDE_HEADER)
				.doesNotContain("新屋共同边界").doesNotContain("\n   - `NEW_HOME`：").doesNotContain("\n   - `OUTSIDE`：")
				.doesNotContain(RULE7).doesNotContain(PUSH).doesNotContain(RULE9).doesNotContain(STAIRWELL)
				.doesNotContain(OUTSIDE_ENDINGS);
	}

	@Test
	void outsideGetsItsFragmentWithRules7And9AndTheOutsideOnlyEndings() {
		String p = animalPrompt(BoxScene.Situation.OUTSIDE);
		assertThat(p).contains(OUTSIDE_HEADER).contains("\n   - `OUTSIDE`：")
				.contains(RULE7).contains(PUSH).contains(RULE9).contains(STAIRWELL).contains(OUTSIDE_ENDINGS);
		assertThat(p).doesNotContain(NEW_HOME_HEADER).doesNotContain(EMPTY_HOME_HEADER)
				.doesNotContain("新屋共同边界").doesNotContain("旧屋共同边界")
				.doesNotContain("\n   - `NEW_HOME`：").doesNotContain("\n   - `EMPTY_HOME`：");
	}

	/** 片段接在主干之后(主干最后一条是 (10))。 */
	@Test
	void fragmentComesAfterTheTrunk() {
		for (BoxScene.Situation s : BoxScene.Situation.values()) {
			String p = animalPrompt(s);
			assertThat(p.indexOf("【处境片段")).as(s.name()).isGreaterThan(p.indexOf("(10)【最后一回合是活着的】"));
		}
	}

	/** 旧局仍是旧指令:旧素材与旧转折条件都在,且不接任何片段。 */
	@Test
	void legacyKeepsTheOldDirectiveWithItsRuptureMaterial() {
		String p = legacyPrompt();
		for (String item : OLD_RUPTURE_MATERIAL) {
			assertThat(p).contains(item);
		}
		assertThat(p).contains(OLD_TRANSITION).doesNotContain("【处境片段").doesNotContain("**（5）");
	}

	/** 主干与片段都只属于《动物人生》 —— 别的世界不得沾上。 */
	@Test
	void nothingLeaksIntoOtherWorlds() {
		for (String archetype : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim")) {
			String p = builder.buildTurnPrompt(engineFor(archetype), "A", "行动");
			assertThat(p).as(archetype).doesNotContain("撕胶带").doesNotContain("【处境片段")
					.doesNotContain("按权威处境裁决");
		}
	}
}
