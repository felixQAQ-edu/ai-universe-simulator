package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.worldgen.WorldGenPromptBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * #1 选择反馈定性版(ADR-011)· hint 指令 lockstep + 消毒守护。
 *
 * <p><b>lockstep</b>:hint 指令的核心短语在四处({@code prompts/world-gen.md} / {@code WorldGenPromptBuilder} /
 * {@code prompts/event-loop.md} / {@code TurnPromptBuilder})逐条一致——防止只改 .md 运行时失效、或只改一边漂移。
 *
 * <p><b>消毒</b>:hint 是玩家可见字段,但它所在的选项对象若混入 {@code isTrue}/{@code hiddenLogic},出网投影
 * ({@link Engine#toClientState}) 必须把隐藏字段剥掉、hint 保留(三视图消毒覆盖 hint 路径,ADR-011 约束 3)。
 */
class ActionHintPromptLockstepTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder turnBuilder = new TurnPromptBuilder(registry);
	private final WorldGenPromptBuilder worldBuilder = new WorldGenPromptBuilder(registry);

	/** hint 指令的共享核心短语(去 markdown 加粗后仍是子串)——四处都必须含。 */
	private static final String[] LOCKSTEP_PHRASES = {
			"一句定性的风险/代价/张力提示",
			"不写精确成功率数字",
			"hint 是叙事提示,不代表引擎会据此判定",
	};

	@Test
	void hintDirectiveStaysLockstepAcrossFourPlaces() throws IOException {
		String worldMd = readMd("world-gen.md");
		String eventMd = readMd("event-loop.md");
		String worldRuntime = worldBuilder.buildWorldPrompt("rules_creepy");
		String turnRuntime = turnBuilder.buildTurnPrompt(rulesEngine(), "A", "x");

		for (String phrase : LOCKSTEP_PHRASES) {
			assertThat(worldMd).as("world-gen.md 含 hint lockstep 短语:%s", phrase).contains(phrase);
			assertThat(worldRuntime).as("WorldGenPromptBuilder 含:%s", phrase).contains(phrase);
			assertThat(eventMd).as("event-loop.md 含 hint lockstep 短语:%s", phrase).contains(phrase);
			assertThat(turnRuntime).as("TurnPromptBuilder 含:%s", phrase).contains(phrase);
		}
	}

	@Test
	void toClientStateStripsHiddenFieldsButKeepsHintOnActions() {
		// 造一个混入 isTrue/hiddenLogic 的选项对象(极端情形:模型误吐),出网投影须剥隐藏字段、保 hint。
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 80).put("san", 70);
		world.putArray("rules");
		world.putArray("endings");
		ArrayNode actions = world.putArray("availableActions");
		ObjectNode a = actions.addObject();
		a.put("id", "A");
		a.put("text", "硬闯禁区");
		a.put("hint", "越靠近,那低语越清晰");
		a.put("isTrue", true);              // 不该出网
		a.put("hiddenLogic", "san -30");    // 不该出网

		ObjectNode client = new Engine(world, mapper).toClientState();
		ObjectNode outAction = (ObjectNode) client.get("availableActions").get(0);
		assertThat(outAction.get("hint").asString()).isEqualTo("越靠近,那低语越清晰"); // hint 保留
		assertThat(outAction.has("isTrue")).as("isTrue 已剥").isFalse();
		assertThat(outAction.has("hiddenLogic")).as("hiddenLogic 已剥").isFalse();
	}

	private Engine rulesEngine() {
		ObjectNode world = mapper.createObjectNode();
		world.putObject("character").putObject("attributes").put("hp", 100).put("san", 100);
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
