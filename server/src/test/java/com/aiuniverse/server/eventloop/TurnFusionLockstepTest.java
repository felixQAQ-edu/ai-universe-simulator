package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-013 Slice D · event-loop 融合指令 lockstep 守护:「融合世界 · 每回合裁决与收敛」段的核心短语在两处
 * ({@code prompts/event-loop.md} 融合段 / 运行时 {@code TurnPromptBuilder.FUSION_TURN_DIRECTIVE})逐条一致
 * ——防止只改 .md 运行时失效、或只改一边漂移;并钉「只在 hybrid 局注入,单体 prompt 无此段」。
 */
class TurnFusionLockstepTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(new ArchetypeRegistry());

	/** 融合回合指令的共享核心短语(裁决 + 收敛),.md 与运行时都必须含。 */
	private static final String[] LOCKSTEP_PHRASES = {
			"【融合世界 · 每回合裁决与收敛】",
			"误信心魔伪笔",                     // 裁决:伪笔应伤道心
			"把「辨真伪」做进每一回合的循环",    // 裁决:进循环、守则墙不作背景板
			"不得原地回环",                     // 收敛:不回环
			"每回合至少推进一点",                // 收敛:强制推进
			"不要拖延磨回合",                   // 收敛:condition 接近达成时主动给 ending
			"【通关判定】",                     // E-2:成功 condition 逐项核对,齐则必须提议 ending
			"条件齐了就通关",                   // E-2:治「达成不给通关」(实测胜利线达成拖 99 回合)
			"给出至少一个通往它的选项",          // E-2:仅差一项时叙事+选项向补齐引导
			"【有据恢复】",                     // E-2:恢复手段 stateUpdate 真的上调(F-003 有据恢复)
			"别让恢复手段沦为口头叙事",          // E-2:治「恢复只在嘴上、数值不动」
	};

	@Test
	void fusionDirectiveStaysLockstepBetweenMdAndRuntime() throws IOException {
		String eventMd = readMd("event-loop.md");
		String hybridRuntime = builder.buildTurnPrompt(hybridEngine(), "A", "辨读刻文");

		for (String phrase : LOCKSTEP_PHRASES) {
			assertThat(eventMd).as("event-loop.md 融合段含 lockstep 短语:%s", phrase).contains(phrase);
			assertThat(hybridRuntime).as("TurnPromptBuilder 融合指令含:%s", phrase).contains(phrase);
		}
	}

	@Test
	void singleModePromptCarriesNoFusionDirective() {
		// 单体局(四个基础世界)prompt 无融合段(%8$s 注入空串,逐字不变的 parity 线)。
		for (String archetype : new String[] { "rules_creepy", "apocalypse", "cthulhu", "cultivation" }) {
			ObjectNode world = mapper.createObjectNode();
			world.putArray("archetypes").add(archetype);
			world.putObject("character").putObject("attributes").put("hp", 90);
			world.putArray("rules");
			world.putArray("endings");
			String p = builder.buildTurnPrompt(new Engine(world, mapper), "A", "行动");
			assertThat(p).as("单体 %s 无融合指令", archetype)
					.doesNotContain("【融合世界 · 每回合裁决与收敛】");
		}
	}

	private Engine hybridEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putArray("archetypes").add("cultivation").add("rules_creepy");
		world.putObject("character").putObject("attributes")
				.put("hp", 80).put("mana", 50).put("realm", 15).put("san", 70);
		world.putArray("rules");
		world.putArray("endings");
		return new Engine(world, mapper);
	}

	/** 从 server 模块向上定位仓库根 prompts/<name>(surefire CWD=server 模块目录)。 */
	private String readMd(String name) throws IOException {
		for (Path p : new Path[] { Path.of("..", "prompts", name), Path.of("prompts", name) }) {
			if (Files.exists(p)) {
				return Files.readString(p);
			}
		}
		throw new IOException("找不到 prompts/" + name + "(CWD=" + Path.of(".").toAbsolutePath() + ")");
	}
}
