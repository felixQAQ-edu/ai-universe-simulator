package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 《动物人生》断裂段素材的守护(F-028 修法)。
 *
 * <p><b>它在守什么</b>:刀 4 冒烟里断裂三回合(T15-17)被整段跳过,而勘察实证
 * <b>整份 prompt 里没有任何一句说这三个回合该发生什么</b> —— 时钟表只给了时间刻度、
 * world-gen 只给了一条「绝不解释断裂的原因」。<b>模型没有跳过断裂,它不知道断裂是什么。</b>
 * 本文件钉住那份素材清单与转折条件确实被渲染进回合 prompt。
 *
 * <p><b>⚠️ 这些是渲染级真断言,不是探针</b>(ADR-018 §4.13 / §4.14):抽掉素材清单或删掉转折条件,
 * 用例必须变红 —— 两条都做过变异验证(见各自注释)。刀 4 的教训正是
 * <b>「没有任何断言在看的探针不是守护」</b>。
 */
class AnimalLifeRuptureDirectiveTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);

	/** 素材清单逐条(取自创意稿 B 段;每条取一个不会与别处撞车的片段)。 */
	private static final List<String> RUPTURE_MATERIAL = List.of(
			"纸箱的味道",
			"撕胶带",
			"椅子上的东西被拿下来了",
			"两只手托起一个箱子",
			"一高一低",
			"放了一下,很久没有拿开",
			"门开着,没有合上",
			"四个凹进去的印子",
			"碗还在原来的地方");

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

	/**
	 * <b>断裂段素材必须在回合 prompt 里</b> —— F-028 的根因就是它一条都不在。
	 *
	 * <p><b>变异验证</b>:把第 (8) 条的素材清单整块删掉 → 本用例变红。
	 */
	@Test
	void ruptureMaterialIsInjectedIntoTheTurnPrompt() {
		String p = animalPrompt();
		for (String item : RUPTURE_MATERIAL) {
			assertThat(p).as("断裂段素材「%s」不在回合 prompt 里(F-028 的根因)", item).contains(item);
		}
	}

	/**
	 * <b>转折条件必须可数</b>:这三个回合结束时动物必须已经不在屋里,第 18 回合仍在屋里即为写错。
	 *
	 * <p><b>变异验证</b>:删掉这一句 → 本用例变红。
	 * <p>它挂在<b>回合号</b>上而不是「断裂之后」这类阶段措辞(F-025:软约束要挂在模型
	 * 每回合已经拿得到的东西上;回合号本来就在 prompt 里)。
	 */
	@Test
	void ruptureCarriesACountableTransitionCondition() {
		String p = animalPrompt();
		assertThat(p).contains("这三个回合结束时");
		assertThat(p).contains("已经不在屋里");
		assertThat(p).contains("第 18 回合的场景若仍在屋里");
	}

	/**
	 * <b>给物,不给顺序</b> —— 这条边界是本刀最容易写歪的地方:
	 * 写死「T15 写纸箱、T16 写那只手」等于给模型排剧本,每一局都会长得一样。
	 *
	 * <p>正面断言那句「由你定」;反面只挡住最可能的剧本形态(逐回合点名 16/17)。
	 * <b>⚠️ 它挡不住所有剧本写法</b>(比如改用「先……再……」),故正面那句才是主守护 ——
	 * 如实记,不假装这条断言比它实际做到的更强。
	 */
	@Test
	void ruptureMaterialIsUnorderedNotAScript() {
		String p = animalPrompt();
		assertThat(p).contains("怎么分配到哪一回合、按什么次序出现,由你定");
		assertThat(p).doesNotContain("第 16 回合").doesNotContain("第 17 回合");
	}

	/** 全段禁止引号 + 不许解释(后者引用铁律 2,不在本槽再抄一份词)。 */
	@Test
	void ruptureForbidsDialogueAndExplanation() {
		String p = animalPrompt();
		assertThat(p).contains("全段禁止引号").contains("不许有台词");
		assertThat(p).contains("不写他们为什么搬、要去哪、还回不回来");
	}

	/**
	 * 素材只属于《动物人生》 —— 别的世界不得沾上(同 {@code noLifetimeWorldLeaksAnotherWorldsStageNames}
	 * 的形状:per-world 的东西漏进别人的 prompt 不会有任何信号)。
	 */
	@Test
	void ruptureMaterialDoesNotLeakIntoOtherWorlds() {
		for (String archetype : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation", "life_sim")) {
			String p = builder.buildTurnPrompt(engineFor(archetype), "A", "行动");
			assertThat(p).as("断裂段素材漏进了 %s 的回合 prompt", archetype)
					.doesNotContain("撕胶带").doesNotContain("四个凹进去的印子");
		}
	}
}
