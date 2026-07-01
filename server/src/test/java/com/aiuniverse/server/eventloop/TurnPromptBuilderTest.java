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

	@Test
	void repairPromptIncludesErrorsAndAsksJsonOnly() {
		String p = builder.buildRepairPrompt("{坏的}", List.of("stateUpdate/hp: 超出范围 [0,100]"));
		assertThat(p).contains("超出范围");
		assertThat(p).contains("{坏的}");
		assertThat(p).contains("纯 JSON");
	}
}
