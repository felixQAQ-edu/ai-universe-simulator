package com.aiuniverse.server.eventloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiuniverse.server.archetype.ArchetypeRegistry;
import com.aiuniverse.server.archetype.AttributeAxis;
import com.aiuniverse.server.engine.Engine;
import com.aiuniverse.server.llm.LlmException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADR-036「订正(2026-11-10 三)」订正 1 ·《派单》记忆点服务端注入。走真实落账路径 {@code EventLoopService.landSettled}
 * 落一回合,再渲染下一回合提示词,断言【腿况】之后注入的是哪一句【记忆点】。
 */
class PaidanMemoryLineTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, builder, mapper);

	@Test
	void crossing30InjectsTheLineNextTurn() {
		Engine e = landProfile("cyberpunk", 10, 29, 30);
		assertThat(e.issues()).containsExactly("T11 画像向上跨档 29→30");
		assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_ON);
	}

	@Test
	void crossing60InjectsTheLineNextTurn() {
		// 冒烟原样:画像 59 → 68。
		Engine e = landProfile("cyberpunk", 3, 59, 68);
		assertThat(e.issues()).containsExactly("T4 画像向上跨档 59→68");
		assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_ON);
	}

	@Test
	void noCrossingForbidsTheLine() {
		for (int[] c : new int[][] { { 30, 59 }, { 10, 29 }, { 60, 94 }, { 45, 45 } }) {
			Engine e = landProfile("cyberpunk", 10, c[0], c[1]);
			assertThat(e.issues()).as(c[0] + "→" + c[1]).noneMatch(s -> s.contains("画像向上跨档"));
			assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_OFF);
		}
	}

	@Test
	void droppingBackAcrossABandForbidsTheLine() {
		for (int[] c : new int[][] { { 65, 55 }, { 31, 20 }, { 70, 10 } }) {
			Engine e = landProfile("cyberpunk", 10, c[0], c[1]);
			assertThat(e.issues()).as(c[0] + "→" + c[1]).noneMatch(s -> s.contains("画像向上跨档"));
			assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_OFF);
		}
	}

	@Test
	void crossingBoth30And60InOneTurnShowsTheLineOnce() {
		Engine e = landProfile("cyberpunk", 10, 25, 64); // 跳 39,不触发引擎跳变 issue
		assertThat(e.issues()).containsExactly("T11 画像向上跨档 25→64");
		String p = assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_ON);
		assertThat(p).containsOnlyOnce("【记忆点】本回合").doesNotContain(TurnPromptBuilder.PAIDAN_MEMORY_OFF);
	}

	@Test
	void flagLastsOnlyOneTurn() {
		Engine e = landProfile("cyberpunk", 10, 29, 31);
		assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_ON);
		landOn(e, 33); // 第 12 回合:31 → 33,没跨
		assertNextTurnLine(e, TurnPromptBuilder.PAIDAN_MEMORY_OFF);
	}

	@Test
	void flagSurvivesSaveAndRestore() {
		Engine e = landProfile("cyberpunk", 10, 50, 61);
		List<AttributeAxis> axes = registry.meta("cyberpunk").attributes();
		Engine restored = Engine.restore(e.toPersistedState(), mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
		assertNextTurnLine(restored, TurnPromptBuilder.PAIDAN_MEMORY_ON);
	}

	@Test
	void turnOneDoesNotMatchTurnTwelvesFlag() {
		Engine e = engine("cyberpunk", 1, 40);
		e.recordIssue("T12 画像向上跨档 50→61");
		assertThat(TurnPromptBuilder.paidanMemoryLine(e)).isEqualTo(TurnPromptBuilder.PAIDAN_MEMORY_OFF);
	}

	@Test
	void otherWorldsRecordNothingAndInjectNothing() {
		for (String id : List.of("rules_creepy", "cthulhu", "cultivation", "apocalypse")) {
			Engine e = engine(id, 10, 20);
			ObjectNode parsed = parsed(Map.of());
			registry.meta(id).attributes().forEach(a -> ((ObjectNode) parsed.get("stateUpdate")).put(a.key(), 70.0));
			service.landSettled(new GameSession("s", e, actions()), parsed, "A", null, leak -> {
			});
			assertThat(e.issues()).as(id).noneMatch(s -> s.contains("画像向上跨档"));
			assertThat(builder.buildTurnPrompt(e, "A", "测试行动")).as(id).doesNotContain("【记忆点】")
					.doesNotContain("已为你优化时限");
		}
	}

	@Test
	void linesAreVerbatimFromTheBrief() {
		assertThat(TurnPromptBuilder.PAIDAN_MEMORY_ON)
				.isEqualTo("\n【记忆点】本回合叙事开头逐字浮出一次「已为你优化时限。」,不解释,只写这一单的时限比上一单短。");
		assertThat(TurnPromptBuilder.PAIDAN_MEMORY_OFF).isEqualTo("\n【记忆点】本回合不得出现「已为你优化时限。」这句话。");
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	/** 下一回合提示词里【腿况】之后紧跟 {@code line},再接时钟之后的推进句;返回整段提示词。 */
	private String assertNextTurnLine(Engine e, String line) {
		int n = e.turn() + 1;
		String p = builder.buildTurnPrompt(e, "A", "测试行动");
		assertThat(p).as("T" + n).contains(TurnPromptBuilder.paidanLegStatus(e) + line + "\n\n请推进第 " + n + " 回合。")
				.containsOnlyOnce("【记忆点】本回合");
		return p;
	}

	/** state.turn = {@code turn},画像 = {@code before};落一回合把画像改成 {@code after}。 */
	private Engine landProfile(String id, int turn, int before, int after) {
		Engine e = engine(id, turn, before);
		landOn(e, after);
		return e;
	}

	private void landOn(Engine e, int profile) {
		service.landSettled(new GameSession("save-paidan", e, actions()), parsed(Map.of("profile", profile)), "A", null,
				leak -> {
				});
	}

	private Engine engine(String id, int turn, int profile) {
		List<AttributeAxis> axes = registry.meta(id).attributes();
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", "single");
		world.putArray("archetypes").add(id);
		world.putObject("world").put("title", "测").put("background", "测").put("dangerLevel", "high").put("tone", "测");
		ObjectNode attrs = world.putObject("character").putObject("attributes");
		axes.forEach(a -> attrs.put(a.key(), 50));
		if (attrs.has("profile")) {
			attrs.put("profile", profile);
		}
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "almost").put("title", "差一点").put("condition", "测")
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

	private ObjectNode parsed(Map<String, Integer> upd) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "雨还在下。");
		ObjectNode su = parsed.putObject("stateUpdate");
		upd.forEach((k, v) -> su.put(k, v.doubleValue()));
		su.put("timeline", "t");
		parsed.putArray("triggeredRuleIds");
		parsed.putArray("discoveredRuleIds");
		parsed.set("availableActions", actions());
		parsed.putNull("ending");
		return parsed;
	}

	private ArrayNode actions() {
		ArrayNode a = mapper.createArrayNode();
		a.addObject().put("id", "A").put("text", "接下一单").put("hint", "时限很紧");
		a.addObject().put("id", "B").put("text", "歇一口气").put("hint", "评分会掉");
		return a;
	}
}
