package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.archetype.LifetimeFamily;

/**
 * 一生制时钟参数化的<b>结构守护</b>(ADR-021 刀 2)。
 *
 * <p><b>为什么必须有源码级断言</b>(ADR-018 §4.13,刀 1 立的那条常驻纪律):
 * <b>渲染级守护验的是结果,源码级守护验的是结构;本刀改的是结构,所以只有渲染级守护等于没守。</b>
 * 刀 2 的成功定义正是「七个世界的 prompt 一个字节都不变」—— 在这种刀次里,
 * 任何只看输出的断言<b>天然测不出任何东西</b>。故本文件直接看结构本身:
 * 机制文件里不许有任何世界的字面量。
 */
class LifeStageParameterizationTest {

	/** 纯机制文件:一个 per-world 字面量都不许有。 */
	private static final List<Path> MECHANISM_SOURCES = List.of(
			Path.of("src/main/java/com/aiuniverse/server/eventloop/LifeStage.java"),
			Path.of("src/main/java/com/aiuniverse/server/eventloop/LifeStageTable.java"));

	/**
	 * <b>机制文件里不得残留人类年龄字面量</b> —— 参数化没做彻底的最刺眼形态。
	 *
	 * <p>原 {@code enum LifeStage} 把「0–6 岁」「31–55 岁」这些写死在<b>枚举值</b>里,
	 * 而 {@code values()} 是<b>类级单例</b> —— 一个 JVM 里只有一份,不可能同时是人的表和动物的表。
	 * 参数化之后这些字面量必须<b>只</b>存在于 {@link LifeStageTables} 的 per-world 表里;
	 * 机制文件一旦再出现「岁」或任何阶段名,说明有人又把某个世界焊了回去。
	 */
	@Test
	void mechanismSourcesCarryNoPerWorldLiterals() throws Exception {
		List<String> banned = List.of("岁", "幼年", "少年", "青年", "中年", "老年", "暮年", "末段", "寿终");
		for (Path src : MECHANISM_SOURCES) {
			String code = Files.readString(src);
			// 注释里允许出现说明性文字,故只看代码行(粗判:去掉以 * 或 // 开头的行)。
			String codeOnly = code.lines()
					.map(String::strip)
					.filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
					.reduce("", (a, b) -> a + "\n" + b);
			for (String word : banned) {
				assertThat(codeOnly)
						.as("机制文件 %s 不得含 per-world 字面量「%s」(参数化没做彻底)", src.getFileName(), word)
						.doesNotContain(word);
			}
		}
	}

	/**
	 * <b>致命轴 key 不得再硬编码</b> —— 源码级,因为<b>行为级测不出来</b>。
	 *
	 * <p><b>⚠️ 这一条是变异验证逼出来的第二次</b>(同刀 1,ADR-018 §4.13 那条常驻纪律):
	 * 把 {@code vigorKey} 退回硬编码 {@code "vigor"} 时,<b>全部行为测试照样是绿的</b> ——
	 * 因为今天唯一注册的一生制世界的命轴<b>恰好就叫 vigor</b>,行为上完全等价。
	 * 而这条硬编码正是本刀要修的静默失效(动物的命轴不叫 vigor → 钳制直接 return 且不报错)。
	 * <b>改结构的刀次,只有渲染级/行为级守护等于没守。</b>
	 */
	@Test
	void lethalAxisKeyIsNotHardCodedInEventLoop() throws Exception {
		String code = Files.readString(
				Path.of("src/main/java/com/aiuniverse/server/eventloop/EventLoopService.java"));
		String codeOnly = code.lines().map(String::strip)
				.filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
				.reduce("", (a, b) -> a + "\n" + b);
		assertThat(codeOnly)
				.as("收束下限钳制不得硬编码轴 key,必须从 registry 取(否则对动物人生静默失效)")
				.doesNotContain("\"vigor\"");
	}

	/** 《寻常》七段与四个常量原样搬过来 —— 刀 2 是纯重构,内容一字不改。 */
	@Test
	void ordinaryLifeTableIsTheKnife5TableVerbatim() {
		LifeStageTable t = LifeStageTables.of("life_sim");
		assertThat(t).isNotNull();
		assertThat(t.stages().stream().map(LifeStage::label))
				.containsExactly("幼年", "少年", "青年", "中年", "老年", "暮年", "末段");
		assertThat(t.stages().stream().map(LifeStage::fromTurn)).containsExactly(1, 2, 8, 20, 28, 36, 44);
		assertThat(t.convergeFrom()).isEqualTo(45);
		assertThat(t.convergeTo()).isEqualTo(55);
		assertThat(t.finalStageFromTurn()).isEqualTo(44);
		assertThat(t.pronoun()).isEqualTo("他");
		assertThat(t.terminalWord()).isEqualTo("寿终");
	}

	/**
	 * 时钟表登记面必须覆盖全部族成员 —— <b>少一张表 = 那个世界没有时钟,而那正是本刀在修的那类静默失效</b>。
	 * 由 {@link LifeStageTables} 的静态块保证,本测试只是把它钉在测试套件里(类加载即触发)。
	 */
	@Test
	void everyLifetimeMemberHasAClockTable() {
		assertThat(LifetimeFamily.lifetimeMembers()).isNotEmpty();
		for (String archetype : LifetimeFamily.lifetimeMembers()) {
			assertThat(LifeStageTables.of(archetype))
					.as("一生制世界 %s 没有登记时钟表(= 它没有时钟,且不会报错)", archetype)
					.isNotNull();
		}
	}

	/** 非一生制世界不得有时钟表(反向)。 */
	@Test
	void nonLifetimeWorldsHaveNoClockTable() {
		for (String archetype : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation")) {
			assertThat(LifeStageTables.of(archetype)).isNull();
			assertThat(LifetimeFamily.isLifetime(archetype)).isFalse();
		}
	}

	/**
	 * <b>「谁是一生制世界」只有一处答案</b>(刀 2 · B):原 {@code EventLoopService.LIFETIME_EXIT_ARCHETYPES}
	 * 与族成员表同集同义,已合并。本条钉住合并后的唯一真理源确实被两个消费方共用。
	 */
	@Test
	void lifetimeMembershipHasASingleSourceOfTruth() {
		assertThat(LifetimeFamily.lifetimeMembers()).containsExactly("life_sim");
		// 出口 id 留全局:它是 id 不是措辞(裁定一)——玩家看不见,换世界没有理由换。
		assertThat(LifeStageTable.EXIT_ACTION_ID).isEqualTo("X");
	}

	/**
	 * <b>一生制世界恰好一条致命轴,否则构造期抛</b>(裁定二)。
	 *
	 * <p>⚠️ 本刀存在的一半理由就是修 {@code VIGOR_KEY} 那个静默失效(动物的致命轴不叫 vigor,
	 * 钳制对它直接 return 且不报错)—— <b>若修完换来另一个静默失效,等于白修</b>。
	 * 故把「多致命轴的一生制世界」做成<b>加载即响</b>。
	 * 约束是<b>一生制族的</b>,不是全局:{@code rules_creepy} 两条致命轴照常。
	 */
	@Test
	void lifetimeWorldsHaveExactlyOneLethalAxis_andOthersAreUnconstrained() {
		ArchetypeRegistry registry = new ArchetypeRegistry(); // 构造期已断言,能 new 出来即通过
		for (String archetype : LifetimeFamily.lifetimeMembers()) {
			assertThat(ArchetypeRegistry.lethalKeys(registry.meta(archetype).attributes()))
					.as("一生制世界 %s 的致命轴", archetype)
					.hasSize(1);
		}
		// 反向:非一生制世界不受这条约束(规则怪谈两条致命轴,照常)。
		assertThat(ArchetypeRegistry.lethalKeys(registry.meta("rules_creepy").attributes()))
				.containsExactly("hp", "san");
	}

	/**
	 * <b>致命轴 key 的唯一来源是 registry</b>(刀 2 course correction,如实记):
	 * 初版让 {@code Engine} 自己派生(全部轴 − 累积轴 − 非致命轴),<b>被测试当场证伪</b> ——
	 * 那样会依赖「这一局的引擎被正确播种」,而 2 参构造的引擎(golden parity 默认:全 depletion 全致命)
	 * 会反推出 4 条轴,钳制随即跳过。<b>「哪条是这个世界的命轴」是世界元数据,不是会话状态</b> ——
	 * 故改走 registry,{@code Engine} 一行未动。
	 */
	@Test
	void lethalKeysComeFromRegistryNotFromEngineSeeding() {
		ArchetypeRegistry registry = new ArchetypeRegistry();
		List<AttributeAxis> axes = registry.meta("life_sim").attributes();
		assertThat(ArchetypeRegistry.lethalKeys(axes)).containsExactly("vigor");
		// 归零不死两轴与累积轴都不在致命集里(ADR-020 §3 / ADR-021 刀 1 登记)。
		assertThat(ArchetypeRegistry.nonLethalKeys(axes)).containsExactlyInAnyOrder("longing", "crossroads");
		assertThat(ArchetypeRegistry.accumulationKeys(axes)).containsExactly("ties");
	}

	/** 表结构良构由构造器保证:断段 / 末段不开放 → 加载即抛,不留给运行时。 */
	@Test
	void tableConstructorRejectsMalformedStageLists() {
		assertThatThrownBy(() -> new LifeStageTable("x", List.of(), 1, 2, 3, "h", "t", "ha", "他", "寿终"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new LifeStageTable("x",
				List.of(new LifeStage(1, 3, "a", "n", "c", null), new LifeStage(5, Integer.MAX_VALUE, "b", "n", "c", null)),
				1, 2, 3, "h", "t", "ha", "他", "寿终"))
				.as("区间断了(3 → 5)必须加载即抛")
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new LifeStageTable("x",
				List.of(new LifeStage(1, 40, "a", "n", "c", null)), 1, 2, 3, "h", "t", "ha", "他", "寿终"))
				.as("末段不开放到 MAX_VALUE → 长局落不到任何阶段")
				.isInstanceOf(IllegalStateException.class);
	}
}
