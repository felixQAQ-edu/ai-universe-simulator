package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 钉住 ADR-006 线上格式由提示词侧产出:prose 先行 + 哨兵 + 尾巴去 narrative。正确性最终靠真 key 冒烟,
 * 此处只锁住「格式契约不被悄悄改坏」。
 */
class TurnPromptBuilderTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	/** 规则怪谈引擎(无显式 archetypes → resolveMeta 回落 rules_creepy)。 */
	private Engine engine() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/** 末日引擎:archetypes=["apocalypse"],attributes={hp,hunger}。 */
	private Engine apocalypseEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("apocalypse");
		world.putObject("character").putObject("attributes").put("hp", 100).put("hunger", 100);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/** 克苏鲁引擎:archetypes=["cthulhu"],attributes={hp,san,knowledge}。 */
	private Engine cthulhuEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cthulhu");
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 90).put("knowledge", 10);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	@Test
	void turnPromptCarriesSentinelContextAndAction() {
		String p = builder.buildTurnPrompt(engine(), "A", "查看告示");
		assertThat(p).contains(SentinelSplitter.SENTINEL);
		assertThat(p).contains("不含 narrative"); // 尾巴去 narrative 字段
		assertThat(p).contains("第 1 回合");        // engine.turn()+1
		assertThat(p).contains("A · 查看告示");
		assertThat(p).contains("\"hp\""); // 内嵌 context_json 真理之源 + stateUpdate 数值轴
		// 规则怪谈注入:模式名 + san 轴,无 hunger。
		assertThat(p).contains("规则怪谈").contains("\"san\"").doesNotContain("hunger");
	}

	@Test
	void turnPromptInjectsApocalypseAxesAndHungerDecayReminder() {
		String p = builder.buildTurnPrompt(apocalypseEngine(), "A", "搜寻补给");
		// 模式名 + 末日数值轴 hp/hunger(stateUpdate 字段),无规则怪谈 san。
		assertThat(p).contains("末日生存");
		assertThat(p).contains("\"hp\"").contains("\"hunger\"").doesNotContain("\"san\"");
		// 衰减提醒:回合 AI 须每回合落 hunger 自然衰减(决策 2 的提示词侧落地)。
		assertThat(p).contains("衰减").contains("每回合");
		// 叙事清洁度硬约束保留(禁内部字段名)。
		assertThat(p).contains("破第四面墙");
	}

	@Test
	void turnPromptInjectsCthulhuKnowledgeAxisAndSanLinkageReminder() {
		String p = builder.buildTurnPrompt(cthulhuEngine(), "A", "继续研读");
		// 模式名 + 克苏鲁三轴 hp/san/knowledge(stateUpdate 字段),无末日 hunger。
		assertThat(p).contains("克苏鲁");
		assertThat(p).contains("\"hp\"").contains("\"san\"").contains("\"knowledge\"").doesNotContain("hunger");
		// 行为提醒(泛化自衰减):knowledge 累积 + knowledge↔san 联动(本批最关键、AI 须落)。
		assertThat(p).contains("累积").contains("联动");
		// 叙事清洁度:knowledge 也进禁用字段名清单(禁直呼内部 key)。
		assertThat(p).contains("破第四面墙").contains("knowledge");
	}

	@Test
	void turnPromptCarriesQualitativeActionHintDirective() {
		// #1 选择反馈定性版(ADR-011):event-loop 此前完全没提 hint,本版补齐每选项一句定性提示。
		String p = builder.buildTurnPrompt(engine(), "A", "查看告示");
		assertThat(p).contains("hint 必给");
		assertThat(p).contains("一句定性的风险/代价/张力提示");
		// 不掷骰边界:不写精确成功率数字 + hint 是叙事提示不据此判定(呼应引擎只读透传)。
		assertThat(p).contains("不写精确成功率数字");
		assertThat(p).contains("hint 是叙事提示,不代表引擎会据此判定");
		// A-1 叙事长度约束仍在(加 hint 指令不顶掉正文长度约束)。
		assertThat(p).contains("2-4 句").contains("280 字");
	}

	// ── ADR-013 Slice D:融合局注入(治 event-loop 对融合失明)──────────────

	/** 融合引擎:archetypes=["cultivation","rules_creepy"](已登记组合),attributes 4 融合轴。 */
	private Engine hybridEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cultivation").add("rules_creepy");
		world.putObject("character").putObject("attributes")
				.put("hp", 80).put("mana", 50).put("realm", 15).put("san", 70);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	@Test
	void fusionTurnPromptInjectsFusedAxesWithDaoxinAcrossFourPoints() {
		String p = builder.buildTurnPrompt(hybridEngine(), "A", "辨读刻文");

		// 模式名 = 融合语境(非单体「修仙」)。
		assertThat(p).contains("修仙 × 规则怪谈(融合世界)");
		// (1) stateUpdate 规格含全部 4 融合轴——尤其 san(道心)在场(症状①根因:此前缺席 → AI 永不回传)。
		assertThat(p).contains("\"hp\": <0-100 绝对值>").contains("\"mana\": <0-100 绝对值>")
				.contains("\"realm\": <0-100 绝对值>").contains("\"san\": <0-100 绝对值>");
		// (2) 意象:san 用道心换皮口吻(非单体「神智/理智」口吻)。
		assertThat(p).contains("san(道心)").contains("道心一颤");
		assertThat(p).doesNotContain("神智几近崩断"); // 单体 san 意象不应出现在融合局
		// (3) 状态档:道心档注入(san=70 → 清明)。
		assertThat(p).contains("san(道心)当前处于【清明】档");
		// (4) 禁用字段名清单含 san。
		assertThat(p).contains("hp / mana / realm / san / stateUpdate");
	}

	@Test
	void fusionTurnPromptCarriesAdjudicationAndConvergenceDirectives() {
		String p = builder.buildTurnPrompt(hybridEngine(), "A", "辨读刻文");
		// D-2 融合指令:守则裁决(辨真伪进循环)+ 张力收敛(不回环、主动给 ending)。
		assertThat(p).contains("【融合世界 · 每回合裁决与收敛】");
		assertThat(p).contains("误信心魔伪笔");
		assertThat(p).contains("把「辨真伪」做进每一回合的循环");
		assertThat(p).contains("不得原地回环");
		assertThat(p).contains("不要拖延磨回合");
		// 不写死回合数上限(硬上限是引擎层决策,不混入)。
		assertThat(p).doesNotContain("回合数上限").doesNotContain("最多 20 回合");
		// A-1 长度 / ADR-011 hint 边界照旧。
		assertThat(p).contains("2-4 句").contains("280 字").contains("不写精确成功率数字");
	}

	@Test
	void singleCultivationPromptUnchangedByFusionSupport() {
		// 单体修仙局:无融合痕迹——模式名单体、无道心、stateUpdate 无 san、无融合指令(parity 线)。
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cultivation");
		world.putObject("character").putObject("attributes").put("hp", 90).put("mana", 60).put("realm", 20);
		world.putArray("rules");
		world.putArray("endings");
		String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "打坐修炼");

		assertThat(p).contains("推进一局修仙。").doesNotContain("融合世界");
		assertThat(p).doesNotContain("道心").doesNotContain("\"san\"");
		assertThat(p).doesNotContain("【融合世界 · 每回合裁决与收敛】");
	}

	@Test
	void unregisteredPairFallsBackToFirstArchetypeSinglePath() {
		// 反向组合(host=规则怪谈)未登记 → 回落 [0] 单体路径(规则怪谈,双轴 hp/san,无 mana、无融合指令)。
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("rules_creepy").add("cultivation");
		world.putObject("character").putObject("attributes").put("hp", 80).put("san", 70);
		world.putArray("rules");
		world.putArray("endings");
		String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "查看告示");

		assertThat(p).contains("推进一局规则怪谈。").doesNotContain("融合世界").doesNotContain("mana");
		assertThat(p).contains("\"san\": <0-100 绝对值>"); // 规则怪谈本就有 san(理智口吻)
		assertThat(p).contains("神智/理智/心神").doesNotContain("道心");
	}

	@Test
	void repairPromptIncludesErrorsAndAsksJsonOnly() {
		String p = builder.buildRepairPrompt("{坏的}", List.of("stateUpdate/hp: 超出范围 [0,100]"));
		assertThat(p).contains("超出范围");
		assertThat(p).contains("{坏的}");
		assertThat(p).contains("纯 JSON");
	}

	// ── ADR-020 刀 3 · event-loop 侧 per-archetype 指令槽(%9$s)──────────────

	/** 单体局引擎(指定 archetype,attributes 按其轴集给中位值)。 */
	private Engine engineFor(String archetype) {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add(archetype);
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		new ArchetypeRegistry().meta(archetype).attributes().forEach(a -> attrs.put(a.key(), 66));
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/**
	 * 槽的 parity 线:四个既有世界拿空串,且 {@code %9$s} 挂骨架<b>末行行尾</b>而非独占一行——
	 * 独占一行时空串会多留一个换行,四世界回合 prompt 当场不再逐字节相同。
	 */
	@Test
	void turnDirectiveSlotIsEmptyForFourWorldsAndLeavesNoBlankLine_ADR020() {
		for (String a : List.of("rules_creepy", "apocalypse", "cthulhu", "cultivation")) {
			String p = builder.buildTurnPrompt(engineFor(a), "A", "行动");
			assertThat(p).as("%s 不该拿到 per-archetype 回合指令", a)
					.doesNotContain("【一生制 · 每回合写作标准");
			// 空串槽不得多留换行:骨架文本块自带一个结尾 \n,拼接处再加 "\n\n" → 恰好三个。
			// %9$s 若独占一行,这里会变成四个,parity 当场破。
			assertThat(p).contains("与死活状态矛盾的结局。\n\n\n世界设定与当前状态");
		}
	}

	/** 《寻常》拿到逐回合写作标准,六件齐(措辞铁律六条另由 lockstep 用例逐条守)。 */
	@Test
	void ordinaryLifeTurnDirectiveCarriesAllSixItems_ADR020() {
		String p = builder.buildTurnPrompt(engineFor("life_sim"), "A", "行动");
		String slot = p.substring(p.indexOf("【一生制 · 每回合写作标准"));

		// (1) 一生制时钟(刀 5 起:回合号→阶段的**机制**,取代原「年龄段→占几回合」的**描述**)。
		// ⚠️ 原断言查的是被删掉的那张描述表(「0-6 岁压进 1 个回合」等)——F-020 证伪的正是它,
		// 两个口径不得并存,故断言随之换成兑现语义的三件:回合号 / 阶段名 / 必须推进。
		assertThat(slot).contains("现在是第 1 回合").contains("【幼年】")
				.contains("本回合必须推进时间,这是硬要求不是风格建议")
				.contains("比上一回合更晚")
				.contains("绝不显示岁数");
		// (2) 措辞铁律六条在场(逐条 lockstep 见 writingStandardsLockstepBetweenDocAndTurnSlot_ADR020)。
		assertThat(slot).contains("【措辞铁律六条】").contains("自带年代刻度").contains("饭盒");
		// (7) 回收的埋与中(文件 §三)。
		assertThat(slot).contains("命中三四处").contains("写了不用可惜");
		// (3) 退化判据可数 + 两条语义锁 + 路口写法不同。
		// ⚠️ F-023 续:判据由「归零后连续 5 个回合」改为**挂 bands 三档逐档收紧** ——
		// 原触发点(归零)四次冒烟一次都没达成过(从 72 到 0 需要的回合数超过局长本身),
		// 「一条永远等不到触发条件的立字,等于不存在」。故此处断言随之换成分档三条,
		// 原措辞的断言一并删除(两个口径不得并存,同刀 5 换掉密度表那次)。
		assertThat(slot).contains("【炽热】").contains("【转淡】").contains("【熄了】")
				.contains("至多 1 个").contains("一个都不得")
				.contains("新的人、新的地点、或新的时间约定")
				.contains("「照常上班」不算引入").contains("给老同学回个电话")
				.contains("每回合数一遍") // 可数性:本条唯一不能丢的性质
				.contains("仍是四个真实的动作")
				.contains("你还想不想选择").contains("人生还给不给你大的选择");
		assertThat(slot).as("归零触发的旧口径必须删干净,不得与分档并存")
				.doesNotContain("已归零,此后连续 5 个回合");
		// (4) 三处留白 + 口头禅只写一次、系统不得复述。
		assertThat(slot).contains("备注的两个字").contains("那个谁").contains("口头禅")
				.contains("系统不得复述或指认它");
		// (5) 承诺作用域:数值承诺必兑现 / 人生承诺可落空。
		assertThat(slot).contains("数值承诺必兑现").contains("人生承诺可落空");
		// (6) 收束气力下限。
		assertThat(slot).contains("气力不得低于 15");

		// 一辈子只读一次的东西不许混进本槽(「读几次」判据的守护点,它们在 world-gen 侧)。
		assertThat(slot).as("结局池属 world-gen 槽").doesNotContain("outcome=success");
		assertThat(slot).as("早逝三段式属 world-gen 槽").doesNotContain("早逝三段式");
	}

	/** 《寻常》四条新轴有专属中文意象,不落到寡淡的「<中文名>的具体感受」缺省。 */
	@Test
	void ordinaryLifeAxesHaveOwnImagery_ADR020() {
		String p = builder.buildTurnPrompt(engineFor("life_sim"), "A", "行动");
		for (String key : List.of("vigor", "longing", "crossroads", "ties")) {
			assertThat(p).doesNotContain(key + "):" + "气力的具体感受");
		}
		assertThat(p).contains("爬到三楼要停一下")   // vigor
				.contains("电视开着,没在看")          // longing
				.contains("那扇门关上时没听见声音")     // crossroads
				.contains("碗一直摆四副");            // ties
		assertThat(p).doesNotContain("的具体感受");
	}

	/**
	 * lockstep 守护:措辞铁律六条的真理源是 {@code docs/world-ordinary-life-writing-standards.md} §一,
	 * 逐字注入本槽。文件那句「两处不得漂移」若只写在文档与注释里,就只是**期待**——这里把它变成会红的测试:
	 * 六条的标题句必须在两处一字不差地都在。(同 prompts/*.md 与运行时副本的 lockstep 惯例。)
	 */
	@Test
	void writingStandardsLockstepBetweenDocAndTurnSlot_ADR020() throws java.io.IOException {
		String doc = null;
		for (java.nio.file.Path p : List.of(
				java.nio.file.Path.of("..", "docs", "world-ordinary-life-writing-standards.md"),
				java.nio.file.Path.of("docs", "world-ordinary-life-writing-standards.md"))) {
			if (java.nio.file.Files.exists(p)) {
				doc = java.nio.file.Files.readString(p);
				break;
			}
		}
		assertThat(doc).as("写作标准文件必须在(它是真理源)").isNotNull();

		String prompt = builder.buildTurnPrompt(engineFor("life_sim"), "A", "行动");
		for (String law : List.of(
				"选项里不出现态度词。",
				"每个回合的第一句话必须自带年代刻度。",
				"四个选项里至少有一个是「最常见的那种做法」。",
				"回收种子必须藏在最不起眼的选项位置。",
				"重要的事不给专门回合。",
				"留白优先于精确。")) {
			assertThat(doc).as("文件 §一 应含铁律:%s", law).contains(law);
			assertThat(prompt).as("槽内应逐字含铁律:%s", law).contains(law);
		}
		// §三 回收命中率同样两处一致(本刀补进槽的那条)。
		assertThat(doc).contains("命中三四处");
		assertThat(prompt).contains("命中三四处").contains("写了不用可惜");
	}

	/** 两个槽互斥:融合局只出 %8$s,单体局只出 %9$s —— 任何一局都不会同时拿到两段(ADR-020 §10 不得合并)。 */
	@Test
	void fusionAndArchetypeSlotsAreMutuallyExclusive_ADR020() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cultivation").add("rules_creepy");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		new ArchetypeRegistry().fusedAxes("cultivation", "rules_creepy")
				.forEach(a -> attrs.put(a.key(), 66));
		world.putArray("rules");
		world.putArray("endings");
		String fused = builder.buildTurnPrompt(new Engine(world, mapper), "A", "行动");
		assertThat(fused).contains("【融合世界 · 每回合裁决与收敛】")
				.doesNotContain("【一生制 · 每回合写作标准");

		String single = builder.buildTurnPrompt(engineFor("life_sim"), "A", "行动");
		assertThat(single).contains("【一生制 · 每回合写作标准")
				.doesNotContain("【融合世界 · 每回合裁决与收敛】");
	}
}
