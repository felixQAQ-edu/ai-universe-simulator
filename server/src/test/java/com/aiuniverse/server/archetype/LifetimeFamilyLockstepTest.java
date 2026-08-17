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
	 * 归一化:去掉 markdown 引用符 {@code >} 与全部空白——比的是<b>词</b>,不是排版。
	 *
	 * <p><b>⚠️ 引用符必须去掉</b>:真理源文件把原则写成 blockquote,续行开头带 {@code >};
	 * 只去空白的话,两行之间会残留一个 {@code >} 把词切断(刀 1 第一版正是漏了这一条而变红 ——
	 * <b>断言写错、不是代码错</b>,同 ADR-018 §4.18)。片段本身不含 {@code >},故无损。
	 */
	private static String words(String s) {
		return s.replace(">", "").replaceAll("\\s+", "");
	}

	/**
	 * 把带槽的片段拆成<b>固定段</b>(槽与槽之间的字面文本)。
	 *
	 * <p><b>⚠️ 刀 2 逼出来的</b>:刀 1 的片段只有<b>换行槽</b>(槽里是空白),整条掐掉槽即可对拍;
	 * 刀 2 的时钟契约有<b>内容槽</b>(回合号 / 人称 / 阶段名 / 终点词),
	 * 掐掉之后剩下的串<b>根本不是渲染结果的子串</b>——渲染结果那些位置填的是值。
	 * 故改为逐<b>固定段</b>比对:每一段都必须在目标文本里出现。
	 */
	private static List<String> fixedSegments(String fragment) {
		return java.util.Arrays.stream(fragment.split("%\\d+\\$[dsu]"))
				.map(LifetimeFamilyLockstepTest::words)
				.filter(seg -> seg.length() >= MIN_SEGMENT)
				.toList();
	}

	/**
	 * 固定段的最小长度。<b>⚠️ 由一次误报定下来的</b>:阈值取 4 时,时钟契约切出的碎片
	 * 「回合走到」<b>在世界层模板里撞了车</b> —— 那处是《寻常》收束段那句
	 * 「用 3-5 个回合走到寿终结局」,和族级片段<b>毫无关系,只是四个汉字恰好重合</b>。
	 * 中文里四字串的区分度太低,守护会<b>喊狼来了</b>。
	 *
	 * <p>取 8 之后保留的都是有区分度的长段(如「每往后一个回合,都要比上一个更靠近一生的尽头。」),
	 * 而<b>「把词抄回去」这种回退必然会复现整条片段、因而必然复现这些长段</b> —— 守护强度不减。
	 * 同 ADR-018 §4.18:<b>调的是指标,不是守护的意图</b>。
	 */
	private static final int MIN_SEGMENT = 8;

	/**
	 * <b>族级片段清单本身必须被钉住</b> —— 防「把某一条从清单里摘掉」这种回退。
	 *
	 * <p><b>⚠️ 这是变异验证逼出来的第三次</b>:把时钟契约从 {@link LifetimeFamily#fragmentWords()}
	 * 里删掉时,<b>全部 lockstep 照样绿</b> —— 因为它们全都遍历这份清单,<b>清单短了就不再检查那一条</b>。
	 * 这与「守护测试假绿」同族但更隐蔽:<b>守护没坏,是它的检查范围被悄悄缩小了</b>。
	 * 故按身份(不是按数量)逐条钉死。
	 */
	@Test
	void fragmentRosterIsPinnedByIdentity() {
		assertThat(LifetimeFamily.fragmentWords())
				.as("族级片段清单被削减 = 那一条从此不再被任何 lockstep 检查")
				.containsExactlyInAnyOrder(
						LifetimeFamily.NO_AGE_DISPLAY,
						LifetimeFamily.NO_DEDICATED_TURN,
						LifetimeFamily.ALIVE_AT_THE_END,
						LifetimeFamily.CLOCK_CONTRACT);
	}

	@Test
	void fragmentsMatchTheStandardsDocument() throws Exception {
		String doc = words(Files.readString(STANDARDS));
		assertThat(LifetimeFamily.fragmentWords())
				.as("族级片段非空(空表会让本测试假绿)")
				.isNotEmpty();
		for (String fragment : LifetimeFamily.fragmentWords()) {
			for (String seg : fixedSegments(fragment)) {
				assertThat(doc)
						.as("族级片段的词必须逐字出现在真理源文件里(两处不得漂移):%s", seg)
						.contains(seg);
			}
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
				for (String seg : fixedSegments(fragment)) {
					assertThat(words(both))
							.as("族成员 %s 的世界层模板漏引了族级片段:%s", archetype, seg)
							.contains(seg);
				}
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
				for (String seg : fixedSegments(fragment)) {
					assertThat(code)
							.as("族级片段的词不得出现在世界层源文件 %s 里(必须经 LifetimeFamily 引用):%s",
									src.getFileName(), seg)
							.doesNotContain(seg);
				}
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
				for (String seg : fixedSegments(fragment)) {
					assertThat(words(both))
							.as("非族成员 %s 不该出现族级片段:%s", archetype, seg)
							.doesNotContain(seg);
				}
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
