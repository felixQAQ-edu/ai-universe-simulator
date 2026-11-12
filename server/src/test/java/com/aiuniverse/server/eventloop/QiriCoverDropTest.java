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
 * ADR-038 决策 6 / 审定 6 ·《七日开张》跨档台词:落账后底细 ≤ 20、落账前 &gt; 20,且本局还没记过 → 记引擎 issue
 * 「T{n} 底细跌破 20 a→b」,下一回合注入【记忆点】「写出这一句」;<b>每局只浮一次</b>。
 * 走真实落账路径 {@code EventLoopService.landSettled} 落一回合,再渲染下一回合提示词(同 PaidanMemoryLineTest)。
 */
class QiriCoverDropTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ArchetypeRegistry registry = new ArchetypeRegistry();
	private final TurnPromptBuilder builder = new TurnPromptBuilder(registry);
	private final EventLoopService service = new EventLoopService((req, sink) -> {
		throw new LlmException("本测试不调模型");
	}, builder, mapper);

	@Test
	void droppingToTwentyOrBelowInjectsTheLineNextTurn() {
		Engine e = landCover(10, 25, 15);
		assertThat(e.issues()).containsExactly("T11 底细跌破 20 25→15");
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_ON);
	}

	@Test
	void exactlyTwentyCountsAsBelow() {
		// 与档界一致:20 归低档(快露馅)。
		Engine e = landCover(4, 21, 20);
		assertThat(e.issues()).containsExactly("T5 底细跌破 20 21→20");
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_ON);
	}

	@Test
	void noDropForbidsTheLine() {
		for (int[] c : new int[][] { { 80, 74 }, { 30, 21 }, { 20, 14 }, { 15, 21 }, { 10, 10 } }) {
			Engine e = landCover(10, c[0], c[1]);
			assertThat(e.issues()).as(c[0] + "→" + c[1]).noneMatch(s -> s.contains("底细跌破 20"));
			assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_OFF);
		}
	}

	@Test
	void flagLastsOnlyOneTurn() {
		Engine e = landCover(10, 26, 18);
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_ON);
		landOn(e, 17); // 第 12 回合:仍在 20 以下,没有「跌破」
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_OFF);
	}

	/** 审定 6:每局只浮一次 —— 圆回来回升到 20 以上后再跌破,不再记、不再浮。 */
	@Test
	void secondDropBelowTwentyInTheSameGameDoesNotShowTheLineAgain() {
		Engine e = landCover(10, 26, 18); // T11:第一次跌破
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_ON);
		landOn(e, 24); // T12:圆回来,回到 20 以上
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_OFF);
		landOn(e, 16); // T13:第二次跌破
		assertThat(e.issues().stream().filter(s -> s.contains("底细跌破 20"))).containsExactly("T11 底细跌破 20 26→18");
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_OFF);
		landOn(e, 30); // T14
		landOn(e, 12); // T15:第三次跌破,同样不浮
		assertThat(e.issues().stream().filter(s -> s.contains("底细跌破 20"))).hasSize(1);
		assertNextTurnLine(e, TurnPromptBuilder.QIRI_MEMORY_OFF);
	}

	@Test
	void onceFlagSurvivesSaveAndRestore() {
		// 标记在 issues 里、随存档落盘:重启后既能在下一回合浮出,也能挡住之后的第二次跌破(不靠会话内存)。
		Engine e = landCover(10, 50, 19);
		Engine restored = restore(mapper, registry, e.toPersistedState());
		assertNextTurnLine(restored, TurnPromptBuilder.QIRI_MEMORY_ON);
		landOn(restored, 40);
		landOn(restored, 5);
		assertThat(restored.issues().stream().filter(s -> s.contains("底细跌破 20"))).hasSize(1);
		assertNextTurnLine(restored, TurnPromptBuilder.QIRI_MEMORY_OFF);
	}

	@Test
	void turnOneDoesNotMatchTurnTwelvesFlag() {
		Engine e = engine(mapper, registry, "time_travel", 1, Map.of("cover", 40));
		e.recordIssue("T12 底细跌破 20 25→15");
		assertThat(TurnPromptBuilder.qiriMemoryLine(e)).isEqualTo(TurnPromptBuilder.QIRI_MEMORY_OFF);
	}

	@Test
	void onceJudgementIsPureAndKeyedOnTheMarker() {
		assertThat(TurnPromptBuilder.qiriCoverDropped(21, 20, List.of())).isTrue();
		assertThat(TurnPromptBuilder.qiriCoverDropped(20, 19, List.of())).isFalse();
		assertThat(TurnPromptBuilder.qiriCoverDropped(25, 15, List.of("T3 底细跌破 20 22→18"))).isFalse();
		// 别的 issue 不算标记(引擎跳变、结局核对改判、《派单》跨档)。
		assertThat(TurnPromptBuilder.qiriCoverDropped(25, 15, List.of("T3 跳变过大 cover 70->25(需复核)",
				"T4 结局核对改判 无 → taoist_summoned", "T5 画像向上跨档 29→30"))).isTrue();
	}

	@Test
	void otherWorldsRecordNothing() {
		for (String id : List.of("rules_creepy", "cthulhu", "cultivation", "apocalypse", "cyberpunk", "life_sim")) {
			Engine e = engine(mapper, registry, id, 10, Map.of());
			ObjectNode parsed = parsed(Map.of());
			registry.meta(id).attributes().forEach(a -> ((ObjectNode) parsed.get("stateUpdate")).put(a.key(), 15.0));
			service.landSettled(new GameSession("s", e, actions()), parsed, "A", null, leak -> {
			});
			assertThat(e.issues()).as(id).noneMatch(s -> s.contains("底细跌破 20"));
			assertThat(builder.buildTurnPrompt(e, "A", "测试行动")).as(id).doesNotContain("隔壁摊的大娘");
		}
	}

	// ── 夹具 ──────────────────────────────────────────────────────────

	private void assertNextTurnLine(Engine e, String line) {
		int n = e.turn() + 1;
		String p = builder.buildTurnPrompt(e, "A", "测试行动");
		assertThat(p).as("T" + n).contains(TurnPromptBuilder.qiriTurnClock(n) + line + "\n\n请推进第 " + n + " 回合。")
				.containsOnlyOnce("【记忆点】本回合");
	}

	private Engine landCover(int turn, int before, int after) {
		Engine e = engine(mapper, registry, "time_travel", turn, Map.of("cover", before));
		landOn(e, after);
		return e;
	}

	private void landOn(Engine e, int cover) {
		service.landSettled(new GameSession("save-qiri", e, actions()), parsed(Map.of("cover", cover)), "A", null,
				leak -> {
				});
	}

	/** 最小存档:各轴 50(再按 {@code attrs} 覆盖),state.turn = {@code turn}。供 QiriTurnTest 共用。 */
	static Engine engine(ObjectMapper mapper, ArchetypeRegistry registry, String id, int turn, Map<String, Integer> attrs) {
		List<AttributeAxis> axes = registry.meta(id).attributes();
		ObjectNode world = mapper.createObjectNode();
		world.put("schemaVersion", "0.4");
		world.put("mode", "single");
		world.putArray("archetypes").add(id);
		world.putObject("world").put("title", "测").put("background", "测").put("dangerLevel", "low").put("tone", "测");
		ObjectNode a = world.putObject("character").putObject("attributes");
		axes.forEach(x -> a.put(x.key(), 50));
		attrs.forEach(a::put);
		world.putArray("rules");
		world.putArray("endings").addObject().put("id", "open_as_usual").put("title", "明日照常开张").put("condition", "测")
				.put("outcome", "neutral").put("reached", false);
		ObjectNode p = mapper.createObjectNode();
		p.put("schemaVersion", "0.4");
		p.set("world", world);
		p.putObject("state").put("turn", turn).put("status", "ongoing").put("timeline", "测").put("logSummary", "")
				.putArray("log");
		p.putArray("triggered");
		p.putArray("issues");
		return restore(mapper, registry, p);
	}

	static Engine restore(ObjectMapper mapper, ArchetypeRegistry registry, tools.jackson.databind.JsonNode persisted) {
		String id = persisted.path("world").path("archetypes").path(0).asString("");
		List<AttributeAxis> axes = registry.meta(id).attributes();
		return Engine.restore(persisted, mapper, ArchetypeRegistry.accumulationKeys(axes),
				ArchetypeRegistry.axisDisplayNames(axes), ArchetypeRegistry.nonLethalKeys(axes));
	}

	private ObjectNode parsed(Map<String, Integer> upd) {
		ObjectNode parsed = mapper.createObjectNode();
		parsed.put("narrative", "街上起了风。");
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
		a.addObject().put("id", "A").put("text", "吆喝一声").put("hint", "街坊会看你");
		a.addObject().put("id", "B").put("text", "先把账算清").put("hint", "别写错字");
		return a;
	}
}
