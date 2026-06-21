package com.aiuniverse.server.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ④ world-gen 提示词生产化(设计稿 §7、ADR-007):钉住核心约定不被改丢——
 * id 类型约定(F-001)、泄露硬化、openingNarrative + 初始动作、纯 JSON 无围栏。
 */
class WorldGenPromptBuilderTest {

	private final WorldGenPromptBuilder builder = new WorldGenPromptBuilder();

	@Test
	void worldPromptCarriesSchemaAndIdTypeAndLeakHardening() {
		String p = builder.buildWorldPrompt("rules_creepy");

		// id 类型约定(F-001):rules 整数 / endings snake_case 字符串。
		assertThat(p).contains("整数").contains("snake_case");
		// 泄露硬化:禁止隐藏逻辑进玩家可见字段。
		assertThat(p).contains("hiddenLogic").contains("openingNarrative");
		// 纯 JSON、无围栏(异于回合的哨兵)。
		assertThat(p).contains("纯 JSON").doesNotContain("<<<DELTA>>>");
		// 初始决策圈 + 开场叙事。
		assertThat(p).contains("availableActions").contains("openingNarrative");
		// 透传 archetype。
		assertThat(p).contains("rules_creepy");
	}

	@Test
	void repairPromptCarriesErrorsAndFailedRaw() {
		String p = builder.buildRepairPrompt("{\"mode\":\"single\"}", List.of("rules: 缺失或非数组"));
		assertThat(p).contains("校验错误").contains("rules: 缺失或非数组").contains("{\"mode\":\"single\"}");
		assertThat(p).contains("完整 world JSON");
	}
}
