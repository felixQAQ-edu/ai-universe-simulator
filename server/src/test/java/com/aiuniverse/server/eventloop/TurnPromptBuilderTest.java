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
	void repairPromptIncludesErrorsAndAsksJsonOnly() {
		String p = builder.buildRepairPrompt("{坏的}", List.of("stateUpdate/hp: 超出范围 [0,100]"));
		assertThat(p).contains("超出范围");
		assertThat(p).contains("{坏的}");
		assertThat(p).contains("纯 JSON");
	}
}
