package com.aiuniverse.server.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.eventloop.TurnPromptBuilder;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 族层 lockstep(ADR-021 刀 1)——<b>「原位引用」损失的自动性,由本测试补回</b>。
 *
 * <p><b>为什么需要它</b>:立字二原稿是「拼接顺序 骨架 → 族 → 世界」,那种形态下新世界<b>自动</b>拿到族层;
 * 刀 1 实测证明顺序拼接与逐字节零回归不可同时满足(三条原则分别在世界层槽的槽头/中段/后段,
 * 上提必然重排),故改为「族层供具名片段、世界层原位引用」。代价是引用要靠模板<b>主动</b>引 ——
 * 本测试就是那个强制:<b>族成员的世界层模板漏引任一族级片段 → 变红</b>。
 *
 * <p><b>它比自动拼接强的一点</b>:<b>漏引会响,而错序不会</b>。自动拼接下,新世界若需要把族级原则
 * 放在别的位置,根本发现不了——它只会默默地把族层整块堆在最前面,而没有任何断言在看那件事。
 *
 * <p>两个方向:(1) 片段的词与真理源文件 {@code docs/lifetime-family-writing-standards.md} 不得漂移;
 * (2) 每个族成员的两侧 prompt 都必须真的含有每一条片段。
 */
class LifetimeFamilyLockstepTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder turn = new TurnPromptBuilder(registry);
	private final WorldGenPromptBuilder worldGen = new WorldGenPromptBuilder(registry);

	/** 真理源文件(仓库根的相对路径;测试工作目录是 {@code server/})。 */
	private static final Path STANDARDS = Path.of("..", "docs", "lifetime-family-writing-standards.md");

	/**
	 * 归一化:去掉换行槽 {@code %N$s}、markdown 引用符 {@code >} 与全部空白——比的是<b>词</b>,不是排版。
	 *
	 * <p><b>⚠️ 引用符必须去掉</b>:真理源文件把三条原则写成 blockquote,续行开头带 {@code >};
	 * 只去空白的话,两行之间会残留一个 {@code >} 把词切断。第一版正是漏了这一条而变红 ——
	 * <b>是断言写错、不是代码错</b>(同 ADR-018 §4.18 一族:指标与被测对象不匹配)。
	 * 片段本身不含 {@code >},故去掉它对片段侧是无损的。
	 */
	private static String words(String s) {
		return s.replaceAll("%\\d+\\$s", "").replace(">", "").replaceAll("\\s+", "");
	}

	@Test
	void fragmentsMatchTheStandardsDocument() throws Exception {
		String doc = words(Files.readString(STANDARDS));
		assertThat(LifetimeFamily.fragmentWords())
				.as("族级片段非空(空表会让本测试假绿)")
				.isNotEmpty();
		for (String fragment : LifetimeFamily.fragmentWords()) {
			assertThat(doc)
					.as("族级片段的词必须逐字出现在真理源文件里(两处不得漂移):%s", fragment)
					.contains(words(fragment));
		}
	}

	@Test
	void everyLifetimeMemberReferencesEveryFragment() {
		assertThat(LifetimeFamily.lifetimeMembers()).as("族成员表非空").isNotEmpty();
		for (String archetype : LifetimeFamily.lifetimeMembers()) {
			String turnPrompt = turn.buildTurnPrompt(engine(archetype), "A", "行动");
			String worldPrompt = worldGen.buildWorldPrompt(archetype);
			String both = turnPrompt + "\n" + worldPrompt;
			for (String fragment : LifetimeFamily.fragmentWords()) {
				assertThat(words(both))
						.as("族成员 %s 的世界层模板漏引了族级片段:%s", archetype, fragment)
						.contains(words(fragment));
			}
		}
	}

	/**
	 * <b>族级片段的词只许出现在族层源文件里</b> —— 防「世界层把词抄回去」。
	 *
	 * <p><b>⚠️ 这一条是变异验证逼出来的</b>(ADR-018 §4.13):第一版只有
	 * {@link #everyLifetimeMemberReferencesEveryFragment},而它查的是「词在不在渲染结果里」——
	 * 于是把世界层模板的 {@code %10$s} 退回硬编码原文时,**测试照样是绿的**:词确实还在,
	 * 只是又变回了一份拷贝。**那正是本刀要治的病,而守护看不见它。**
	 * 故补这一条源码级断言:词只能来自族层,世界层模板里不许有第二份。
	 */
	@Test
	void fragmentWordsLiveOnlyInTheFamilyLayerSource() throws Exception {
		List<Path> worldLayerSources = List.of(
				Path.of("src/main/java/com/aiuniverse/server/eventloop/TurnPromptBuilder.java"),
				Path.of("src/main/java/com/aiuniverse/server/worldgen/WorldGenPromptBuilder.java"));
		for (Path src : worldLayerSources) {
			String code = words(Files.readString(src));
			for (String fragment : LifetimeFamily.fragmentWords()) {
				assertThat(code)
						.as("族级片段的词不得出现在世界层源文件 %s 里(必须经 LifetimeFamily 引用):%s",
								src.getFileName(), fragment)
						.doesNotContain(words(fragment));
			}
		}
	}

	/** 非族成员不得被族层污染(族层对它们恒为无 —— 与阳性对照 ② 的读数同源)。 */
	@Test
	void nonMembersAreUntouchedByTheFamilyLayer() {
		for (String archetype : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation")) {
			assertThat(LifetimeFamily.isLifetime(archetype)).as("%s 不是一生制族成员", archetype).isFalse();
			String both = turn.buildTurnPrompt(engine(archetype), "A", "行动") + "\n"
					+ worldGen.buildWorldPrompt(archetype);
			for (String fragment : LifetimeFamily.fragmentWords()) {
				assertThat(words(both))
						.as("非族成员 %s 不该出现族级片段", archetype)
						.doesNotContain(words(fragment));
			}
		}
	}

	/**
	 * 「最后一回合是活着的」<b>两个引用点用的是同一份词</b>——本刀合并的正是这两份拷贝。
	 * 两处断行不同(回合侧断在「一点;」后、world-gen 侧断在「耗到零,」后),故只比词、不比排版。
	 */
	@Test
	void theTwoAliveCopiesAreNowOneFragment() {
		String turnSide = LifetimeFamily.aliveAtTheEnd("", "\n    ");
		String worldGenSide = LifetimeFamily.aliveAtTheEnd("\n  ", "");
		assertThat(turnSide).isNotEqualTo(worldGenSide); // 排版确实不同
		assertThat(words(turnSide)).isEqualTo(words(worldGenSide)); // 词确实同一份
		String lifeSimTurn = turn.buildTurnPrompt(engine("life_sim"), "A", "行动");
		String lifeSimWorldGen = worldGen.buildWorldPrompt("life_sim");
		assertThat(lifeSimTurn).as("回合侧按自己的断行落地").contains(turnSide);
		assertThat(lifeSimWorldGen).as("world-gen 侧按自己的断行落地").contains(worldGenSide);
	}

	private static final Map<String, Map<String, Integer>> AXES = Map.of(
			"rules_creepy", Map.of("hp", 100, "san", 100),
			"apocalypse", Map.of("hp", 100, "hunger", 100),
			"cthulhu", Map.of("hp", 100, "san", 90, "knowledge", 10),
			"cultivation", Map.of("hp", 100, "mana", 50, "realm", 20),
			"life_sim", Map.of("vigor", 60, "longing", 50, "crossroads", 40, "ties", 50));

	private Engine engine(String archetype) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.putArray("archetypes").add(archetype);
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		AXES.get(archetype).forEach(attrs::put);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}
}
