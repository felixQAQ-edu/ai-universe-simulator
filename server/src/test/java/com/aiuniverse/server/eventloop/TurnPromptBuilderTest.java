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
}
