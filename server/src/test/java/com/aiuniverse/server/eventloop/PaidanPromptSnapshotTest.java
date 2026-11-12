package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-038 · 守《派单》(cyberpunk)的 world-gen 与回合提示词逐字节不变。
 *
 * <p><b>为什么要另起这组快照</b>:既有的 {@code golden/world-gen-prompts/} 与 {@code golden/turn-prompts/} 取自《派单》上线之前,
 * 只覆盖其余六个单体与三个融合(PaidanWorldGenTest ① / PaidanTurnTest ①);《派单》自己没有逐字节快照。
 * ADR-038 要改两处《派单》也走的共享代码 —— {@code WorldGenService.worldSpecificErrors} 改按 archetype 分派、
 * {@code SINGLE_TURN_CLOCK} / {@code SINGLE_TURN_STATUS} 各加一条 —— 故在改之前({@code cf29c31},ADR-038 Slice A 之前)
 * 把《派单》的提示词存下来,之后每一刀都与它逐字节比。
 *
 * <p>world-gen 截到最后一个「场景种子:」之前(种子随机);回合取与 PaidanTurnTest 同一组回合号、同一个最小存档(各轴 50)。
 * <b>快照怎么更新</b>:只在有意改动《派单》提示词时,在改动前的提交上重新生成。
 */
class PaidanPromptSnapshotTest {

	private static final String SEED_MARK = "场景种子:";
	private static final int[] TURNS = { 0, 6, 7, 8, 15, 16, 17, 25, 26, 27 };

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();

	@Test
	void paidanWorldGenPromptIsByteIdenticalExceptTheSeedLine() throws IOException {
		WorldGenPromptBuilder builder = new WorldGenPromptBuilder(registry);
		String expected = snapshot("world-gen.txt");
		for (int i = 0; i < 5; i++) {
			String p = builder.buildWorldPrompt("cyberpunk");
			assertThat(p.substring(0, p.lastIndexOf(SEED_MARK))).isEqualTo(expected);
		}
	}

	@Test
	void paidanTurnPromptsAreByteIdentical() throws IOException {
		TurnPromptBuilder builder = new TurnPromptBuilder(registry);
		List<AttributeAxis> axes = registry.meta("cyberpunk").attributes();
		int compared = 0;
		for (int t : TURNS) {
			String name = "turn_T" + (t + 1) + ".txt";
			assertThat(builder.buildTurnPrompt(engine(axes, t), "A", "测试行动")).as(name).isEqualTo(snapshot(name));
			compared++;
		}
		assertThat(compared).isEqualTo(10);
	}

	private String snapshot(String name) throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/golden/cyberpunk/" + name)) {
			assertThat(in).as("快照缺失:" + name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** 与快照生成时完全相同的最小存档:各轴 50,state.turn = 给定值(同 PaidanTurnTest)。 */
	private Engine engine(List<AttributeAxis> axes, int turn) {
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", "single");
		ArrayNode arr = world.putArray("archetypes");
		arr.add("cyberpunk");
		world.putObject("world").put("title", "测").put("background", "测").put("dangerLevel", "high").put("tone", "测");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		axes.forEach(a -> attrs.put(a.key(), 50));
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "x_end").put("title", "终").put("condition", "测")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode p = mapper.createObjectNode();
		p.put("schemaVersion", "0.4");
		p.set("world", world);
		p.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "测").put("logSummary", "")
				.putArray("log");
		p.putArray("triggered");
		p.putArray("issues");
		return Engine.restore(p, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}
}
