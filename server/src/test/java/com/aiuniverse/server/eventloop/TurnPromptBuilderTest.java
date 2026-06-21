package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 钉住 ADR-006 线上格式由提示词侧产出:prose 先行 + 哨兵 + 尾巴去 narrative。正确性最终靠真 key 冒烟,
 * 此处只锁住「格式契约不被悄悄改坏」。
 */
class TurnPromptBuilderTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private Engine engine() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	@Test
	void turnPromptCarriesSentinelContextAndAction() {
		String p = new TurnPromptBuilder().buildTurnPrompt(engine(), "A", "查看告示");
		assertThat(p).contains(SentinelSplitter.SENTINEL);
		assertThat(p).contains("不含 narrative"); // 尾巴去 narrative 字段
		assertThat(p).contains("第 1 回合");        // engine.turn()+1
		assertThat(p).contains("A · 查看告示");
		assertThat(p).contains("\"hp\""); // 内嵌 context_json 真理之源
	}

	@Test
	void repairPromptIncludesErrorsAndAsksJsonOnly() {
		String p = new TurnPromptBuilder().buildRepairPrompt("{坏的}", List.of("stateUpdate/hp: 超出范围 [0,100]"));
		assertThat(p).contains("超出范围");
		assertThat(p).contains("{坏的}");
		assertThat(p).contains("纯 JSON");
	}
}
