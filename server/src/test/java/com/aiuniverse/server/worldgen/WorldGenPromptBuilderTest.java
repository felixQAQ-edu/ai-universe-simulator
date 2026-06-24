package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;

/**
 * ④ world-gen 提示词生产化(设计稿 §7、ADR-007、ADR-008 决策 3):钉住通用骨架核心约定不被改丢——
 * id 类型约定(F-001)、泄露硬化、openingNarrative + 初始动作、纯 JSON 无围栏——以及 per-archetype 注入块
 * (worldview/数值轴/中文名/衰减)按 archetype 分派对。
 */
class WorldGenPromptBuilderTest {

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder(new ArchetypeRegistry());

	@Test
	void worldPromptCarriesSchemaAndIdTypeAndLeakHardening() {
		String p = builder.buildWorldPrompt("rules_creepy");

		// 通用骨架:id 类型约定(F-001):rules 整数 / endings snake_case 字符串。
		assertThat(p).contains("整数").contains("snake_case");
		// 泄露硬化:禁止隐藏逻辑进玩家可见字段。
		assertThat(p).contains("hiddenLogic").contains("openingNarrative");
		// 纯 JSON、无围栏(异于回合的哨兵)。
		assertThat(p).contains("纯 JSON").doesNotContain("<<<DELTA>>>");
		// 初始决策圈 + 开场叙事。
		assertThat(p).contains("availableActions").contains("openingNarrative");
		// 透传 archetype + 规则怪谈注入块(数值轴体力/理智)。
		assertThat(p).contains("rules_creepy").contains("规则怪谈");
		assertThat(p).contains("hp(体力").contains("san(理智");
	}

	@Test
	void worldPromptInjectsApocalypseBlockWithHungerDecay() {
		String p = builder.buildWorldPrompt("apocalypse");

		// 通用骨架照旧(单点维护,不per模式重抄)。
		assertThat(p).contains("整数").contains("snake_case").contains("纯 JSON").contains("openingNarrative");
		// 末日注入块:世界观 + 数值轴 hp(体力)/hunger(饥饿,带衰减提示)+ archetype。
		assertThat(p).contains("apocalypse").contains("末日生存");
		assertThat(p).contains("hp(体力").contains("hunger(饥饿");
		assertThat(p).contains("衰减"); // 衰减提示注入(回合 AI 据此持续落 hunger 衰减)
		// 末日不应注入规则怪谈的 san 轴。
		assertThat(p).doesNotContain("san(理智");
	}

	@Test
	void repairPromptCarriesErrorsAndFailedRawAndAxes() {
		String p = builder.buildRepairPrompt("apocalypse", "{\"mode\":\"single\"}", List.of("rules: 缺失或非数组"));
		assertThat(p).contains("校验错误").contains("rules: 缺失或非数组").contains("{\"mode\":\"single\"}");
		assertThat(p).contains("完整 world JSON");
		assertThat(p).contains("hp/hunger"); // 修复点名本模式数值轴
	}
}
